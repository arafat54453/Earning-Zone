package com.arafat.tun2socks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.arafat.tun2socks.data.ProxyProfile
import com.arafat.tun2socks.data.ProxyType
import com.arafat.tun2socks.data.SS_CIPHERS
import com.arafat.tun2socks.data.Settings
import com.arafat.tun2socks.data.parseProxyLinks
import com.arafat.tun2socks.data.shareLink
import com.arafat.tun2socks.data.url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyListScreen(
    settings: Settings,
    locked: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<ProxyProfile?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    val selectedId = settings.selectedProfile?.id
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latency = remember { mutableStateMapOf<String, String>() }
    var testing by remember { mutableStateOf(false) }
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }

    fun testAll() {
        testing = true
        scope.launch {
            settings.profiles.filter { it.isValid }.forEach { latency[it.id] = "…" }
            settings.profiles.filter { it.isValid }.map { p ->
                async(Dispatchers.IO) {
                    val r = runCatching { t2s.T2s.latency(p.url, 6) }
                    withContext(Dispatchers.Main) { latency[p.id] = r.fold({ "$it ms" }, { "timeout" }) }
                }
            }.awaitAll()
            if (settings.chainActive) {
                val r = withContext(Dispatchers.IO) { runCatching { t2s.T2s.latency(settings.routeSpec(), 10) } }
                Toast.makeText(context, "Chain: " + r.fold({ "$it ms" }, { "failed (${it.message})" }), Toast.LENGTH_LONG).show()
            }
            testing = false
        }
    }

    fun importFromClipboard() {
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val found = parseProxyLinks(text)
        if (found.isEmpty()) {
            Toast.makeText(context, "No proxy links found in clipboard", Toast.LENGTH_SHORT).show()
        } else {
            onUpdate { s -> s.copy(profiles = s.profiles + found, selectedProfileId = s.selectedProfileId.ifBlank { found.first().id }) }
            Toast.makeText(context, "Imported ${found.size} proxy(s)", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(profile: ProxyProfile) {
        val clip = ClipData.newPlainText("proxy", profile.shareLink)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        val guard = settings.effective().clipboardGuard
        if (guard && Build.VERSION.SDK_INT >= 28) {
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { clipboard.clearPrimaryClip() } }, 60_000)
        }
        Toast.makeText(context, if (guard) "Link copied (clears in 60 s)" else "Link copied", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = { Text("Proxy List") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = ::importFromClipboard, enabled = !locked) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Import from clipboard")
                    }
                    IconButton(onClick = ::testAll, enabled = !testing) {
                        Icon(Icons.Filled.Speed, contentDescription = "Test latency")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add proxy")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ToggleRow(
                        title = "Proxy Chain",
                        subtitle = if (settings.chainEnabled) "Tap the link icon to add hops in order. Route: ${settings.routeLabel().ifBlank { "pick at least 2" }}" else "Tunnel through several proxies in sequence (TCP)",
                        icon = Icons.Filled.Link,
                        checked = settings.chainEnabled,
                        enabled = !locked,
                        onCheckedChange = { v -> onUpdate { it.copy(chainEnabled = v) } },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    ToggleRow(
                        title = "Auto Failover",
                        subtitle = "Health-check the active proxy every ${settings.healthCheckSec}s and switch to the fastest working one without disconnecting",
                        icon = Icons.Filled.Speed,
                        checked = settings.autoFailover,
                        enabled = !locked && !settings.chainEnabled,
                        onCheckedChange = { v -> onUpdate { it.copy(autoFailover = v) } },
                    )
                }
            }
            items(settings.profiles, key = { it.id }) { profile ->
                val selected = profile.id == selectedId
                Row(
                    Modifier.animateItem().fillMaxWidth()
                        .clickable(enabled = !locked) { onUpdate { it.copy(selectedProfileId = profile.id) } }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) Accent else Muted,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                Text(profile.type.label, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                profile.address,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                        val sub = listOfNotNull(profile.name.ifBlank { null }, latency[profile.id]).joinToString(" · ")
                        if (sub.isNotBlank()) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                    if (settings.chainEnabled) {
                        val hop = settings.chainIds.indexOf(profile.id)
                        IconButton(
                            onClick = {
                                onUpdate { s ->
                                    s.copy(chainIds = if (profile.id in s.chainIds) s.chainIds - profile.id else s.chainIds + profile.id)
                                }
                            },
                            enabled = !locked,
                        ) {
                            if (hop >= 0) Badge { Text("${hop + 1}") } else Icon(Icons.Filled.Link, contentDescription = "Add to chain", tint = Muted)
                        }
                    }
                    IconButton(onClick = { share(profile) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Copy link", tint = Muted)
                    }
                    IconButton(onClick = { editing = profile; showSheet = true }, enabled = !locked) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = Muted)
                    }
                    IconButton(
                        onClick = {
                            onUpdate { s ->
                                val remaining = s.profiles.filterNot { it.id == profile.id }
                                s.copy(
                                    profiles = remaining,
                                    chainIds = s.chainIds - profile.id,
                                    selectedProfileId = if (s.selectedProfileId == profile.id) remaining.firstOrNull()?.id.orEmpty() else s.selectedProfileId,
                                )
                            }
                        },
                        enabled = !locked && !(selected && locked),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CardBg,
        ) {
            ProxyForm(
                initial = editing,
                onSave = { saved ->
                    onUpdate { s ->
                        val exists = s.profiles.any { it.id == saved.id }
                        val profiles = if (exists) s.profiles.map { if (it.id == saved.id) saved else it } else s.profiles + saved
                        s.copy(profiles = profiles, selectedProfileId = if (s.profiles.isEmpty()) saved.id else s.selectedProfileId)
                    }
                    showSheet = false
                },
            )
        }
    }
}

@Composable
private fun ProxyForm(initial: ProxyProfile?, onSave: (ProxyProfile) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: ProxyType.SOCKS5) }
    var host by remember { mutableStateOf(initial?.host ?: "127.0.0.1") }
    var port by remember { mutableStateOf((initial?.port ?: 1080).toString()) }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var pass by remember { mutableStateOf(initial?.password.orEmpty()) }
    var cipher by remember { mutableStateOf(initial?.cipher ?: SS_CIPHERS.first()) }
    val portValue = if (type == ProxyType.DIRECT) 0 else port.toIntOrNull()
    val valid = if (type == ProxyType.DIRECT) true else host.isNotBlank() && portValue != null && portValue in 1..65535 &&
        (type != ProxyType.SHADOWSOCKS || pass.isNotEmpty())

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (initial == null) "Add Proxy" else "Edit Proxy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ChoiceChips(ProxyType.entries, type, { it.label }) { t ->
            if (port.toIntOrNull() == type.defaultPort) port = t.defaultPort.toString()
            type = t
        }
        OutlinedTextField(name, { name = it }, label = { Text("Profile Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (type != ProxyType.DIRECT) {
            OutlinedTextField(host, { host = it.trim() }, label = { Text("${type.label} Server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") }, singleLine = true, isError = portValue == null || portValue !in 1..65535,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            if (type == ProxyType.SHADOWSOCKS) {
                Text("Cipher", style = MaterialTheme.typography.bodyMedium, color = Muted)
                ChoiceChips(SS_CIPHERS, cipher, { it }) { cipher = it }
            } else {
                OutlinedTextField(
                    user, { user = it },
                    label = { Text(if (type == ProxyType.SOCKS4) "User ID (optional)" else "Username (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (type != ProxyType.SOCKS4) {
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Button(
            onClick = {
                val base = initial ?: ProxyProfile()
                onSave(
                    base.copy(
                        name = name.trim(), type = type,
                        host = if (type == ProxyType.DIRECT) "0.0.0.0" else host.trim(),
                        port = portValue ?: 0,
                        username = if (type == ProxyType.SHADOWSOCKS || type == ProxyType.DIRECT) "" else user,
                        password = if (type == ProxyType.SOCKS4 || type == ProxyType.DIRECT) "" else pass,
                        cipher = cipher,
                    )
                )
            },
            enabled = valid,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (initial == null) "Add Proxy" else "Save Proxy")
        }
        Spacer(Modifier.height(16.dp))
    }
}
