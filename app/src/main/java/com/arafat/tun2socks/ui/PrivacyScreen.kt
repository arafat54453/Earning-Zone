package com.arafat.tun2socks.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arafat.tun2socks.data.BLOCKLIST_PRESETS
import com.arafat.tun2socks.data.Blocklist
import com.arafat.tun2socks.data.Settings
import com.arafat.tun2socks.data.blocklistFile
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val APP_LOCK_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

fun canUseAppLock(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(APP_LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

@Composable
fun PrivacyScreen(
    settings: Settings,
    vpnBusy: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    openLeakTest: () -> Unit,
    openPrivacyCheck: () -> Unit,
    onPanicWipe: () -> Unit,
    openTrackerReport: () -> Unit = {},
    openDataUsage: () -> Unit = {},
    openAnonymity: () -> Unit = {},
    onReconnect: () -> Unit = {},
) {
    val context = LocalContext.current
    val e = settings.effective()
    val pm = settings.privacyMode
    var confirmWipe by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PrivacyModeCard(settings, vpnBusy, onReconnect) { v -> onUpdate { it.copy(privacyMode = v) } }
        SectionCard {
            CardHeader("Protection", Icons.Filled.Shield)
            ToggleRow(
                title = "Kill Switch",
                subtitle = "If the proxy engine fails, keep the tunnel up and block all traffic instead of leaking it",
                icon = Icons.Filled.Block,
                checked = e.killSwitch,
                enabled = !pm,
                onCheckedChange = { v -> onUpdate { it.copy(killSwitch = v) } },
            )
            ToggleRow(
                title = "Block QUIC / HTTP3",
                subtitle = "Forces browsers and apps onto TCP, which is always proxied and filtered",
                icon = Icons.Filled.Speed,
                checked = e.blockQuic,
                enabled = !pm,
                onCheckedChange = { v -> onUpdate { it.copy(blockQuic = v) } },
            )
            ToggleRow(
                title = "Block WebRTC leaks",
                subtitle = "Drops STUN/TURN traffic browsers use to discover your real IP",
                icon = Icons.Filled.VideocamOff,
                checked = e.blockWebRtc,
                enabled = !pm,
                onCheckedChange = { v -> onUpdate { it.copy(blockWebRtc = v) } },
            )
            ToggleRow(
                title = "Stealth notification",
                subtitle = "Hide proxy address in the notification and on the lock screen",
                icon = Icons.Filled.NotificationsOff,
                checked = e.stealthNotification,
                enabled = !pm,
                onCheckedChange = { v -> onUpdate { it.copy(stealthNotification = v) } },
            )
            ToggleRow(
                title = "Block CNAME-cloaked trackers",
                subtitle = "Catches trackers hidden behind a site's own subdomain (e.g. metrics.site.com → tracker)",
                icon = Icons.Filled.FilterAlt,
                checked = e.blockCnameCloaking,
                enabled = !pm,
                onCheckedChange = { v -> onUpdate { it.copy(blockCnameCloaking = v) } },
            )
            ToggleRow(
                title = "Block LAN access",
                subtitle = "While connected, apps cannot reach or be reached by devices on the local network (public Wi-Fi)",
                icon = Icons.Filled.Lock,
                checked = e.blockLan,
                enabled = !pm,
                onCheckedChange = { v -> onUpdate { it.copy(blockLan = v) } },
            )
            ToggleRow(
                title = "Background leak monitor",
                subtitle = "Checks your public IP every ${settings.leakMonitorSec}s; if the real IP shows up you're warned and traffic is cut",
                icon = Icons.Filled.TravelExplore,
                checked = e.leakMonitor,
                enabled = !pm,
                onCheckedChange = { v -> onUpdate { it.copy(leakMonitor = v) } },
            )
            ToggleRow(
                title = "Timezone mismatch warning",
                subtitle = "Warns when the proxy's country doesn't match your phone's timezone, which sites use to detect proxies",
                icon = Icons.Filled.Warning,
                checked = e.timezoneWarning,
                enabled = !pm,
                onCheckedChange = { v -> onUpdate { it.copy(timezoneWarning = v) } },
            )
            NavRow(
                "Always-on VPN",
                "Enable Always-on and \"Block connections without VPN\" in Android settings for full leak protection",
                Icons.Filled.VpnLock,
            ) { context.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }
        }
        BlockingCard(settings, onUpdate)
        SectionCard {
            CardHeader("Logs & Session", Icons.Filled.Insights)
            ToggleRow(
                title = "No-logs mode",
                subtitle = "Keep every log and report in RAM only; erased as soon as you disconnect",
                icon = Icons.Filled.NotificationsOff,
                checked = settings.noLogsMode,
                onCheckedChange = { v -> onUpdate { it.copy(noLogsMode = v) } },
            )
            ToggleRow(
                title = "Ephemeral session",
                subtitle = "On disconnect, clear DNS cache, statistics and recent activity",
                icon = Icons.Filled.DeleteForever,
                checked = settings.ephemeralSession,
                onCheckedChange = { v -> onUpdate { it.copy(ephemeralSession = v) } },
            )
            HintText("Keep App Tracker Report for")
            ChoiceChips(listOf(1, 7, 30), settings.trackerLogDays, { if (it == 1) "1 day" else "$it days" }, enabled = !settings.noLogsMode) { d ->
                onUpdate { it.copy(trackerLogDays = d) }
            }
        }
        SectionCard {
            CardHeader("App Security", Icons.Filled.Lock)
            ToggleRow(
                title = "App Lock",
                subtitle = "Require fingerprint or screen lock to open the app",
                icon = Icons.Filled.Fingerprint,
                checked = settings.appLock,
                onCheckedChange = { v ->
                    if (v && !canUseAppLock(context)) {
                        Toast.makeText(context, "Set up a screen lock or fingerprint first", Toast.LENGTH_LONG).show()
                    } else {
                        onUpdate { it.copy(appLock = v) }
                    }
                },
            )
            ToggleRow(
                title = "Block Screenshots",
                subtitle = "Hide app content from screenshots, screen recording and recent apps",
                icon = Icons.Filled.ScreenLockPortrait,
                checked = e.blockScreenshots,
                enabled = !pm,
                onCheckedChange = { v -> onUpdate { it.copy(blockScreenshots = v) } },
            )
            ToggleRow(
                title = "Clipboard guard",
                subtitle = "Copied proxy links are marked sensitive and cleared after 60 seconds",
                icon = Icons.Filled.ContentPaste,
                checked = e.clipboardGuard,
                enabled = !pm,
                onCheckedChange = { v -> onUpdate { it.copy(clipboardGuard = v) } },
            )
        }
        SectionCard {
            CardHeader("Diagnostics", Icons.Filled.Insights)
            NavRow("Leak Test", "Check exit IP, DNS, IPv6 and UDP leaks", Icons.Filled.TravelExplore, openLeakTest)
            NavRow("Privacy Check", "Find apps that can read your Wi-Fi name or location", Icons.Filled.PrivacyTip, openPrivacyCheck)
            NavRow("App Tracker Report", "Which apps tried to contact which tracking companies, and how often they were blocked", Icons.Filled.Block, openTrackerReport)
            NavRow("Per-App Data Usage", "How much data each app sends and where it goes", Icons.Filled.Insights, openDataUsage)
            NavRow("Proxy Anonymity Test", "Transparent, anonymous or elite? Header leaks and IP blacklists", Icons.Filled.Security, openAnonymity)
        }
        SectionCard {
            CardHeader("Danger Zone", Icons.Filled.Warning)
            HintText("Panic wipe disconnects, erases all proxies, passwords, settings, blocklists and the encryption key, then closes the app.")
            OutlinedButton(
                onClick = { confirmWipe = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Icon(Icons.Filled.DeleteForever, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Panic wipe")
            }
        }
        Text(
            "Android always tells apps inside the tunnel that a VPN is active; no app can hide this without root.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
            title = { Text("Erase everything?") },
            text = { Text("All proxies, passwords and settings will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; onPanicWipe() }) {
                    Text("Erase", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

private val PRIVACY_FEATURES = listOf(
    "Kill switch always on",
    "Encrypted DNS (DoH/DoT) enforced, all port-53 traffic intercepted",
    "Ads, trackers & telemetry blocked",
    "QUIC/HTTP3 blocked so web traffic stays on the proxied TCP path",
    "WebRTC/STUN real-IP leaks blocked",
    "IPv6 leaks blocked",
    "Hidden notification & lock-screen details",
    "Screenshot, screen-recording & recents protection",
    "Clipboard guard for copied secrets",
    "CNAME-cloaked trackers blocked",
    "Local network (LAN) access blocked",
    "Background leak monitor & timezone mismatch warning",
)

@Composable
private fun PrivacyModeCard(settings: Settings, vpnBusy: Boolean, onReconnect: () -> Unit, onToggle: (Boolean) -> Unit) {
    val applied by com.arafat.tun2socks.vpn.VpnController.applied.collectAsState()
    // Only ask for a reconnect when the running tunnel was started with different settings.
    val stale = vpnBusy && applied != null && applied != settings.tunnelSignature()
    val on = settings.privacyMode
    val container by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primaryContainer else CardBg,
        animationSpec = tween(400),
        label = "pm",
    )
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (on) Icons.Filled.Security else Icons.Filled.Shield,
                    contentDescription = null,
                    tint = if (on) DownloadGreen else Accent,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Privacy Mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (on) "Maximum protection is active" else "One tap hardening for every leak path",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Switch(checked = on, onCheckedChange = onToggle)
            }
            AnimatedVisibility(on) {
                Column(Modifier.padding(top = 12.dp)) {
                    PRIVACY_FEATURES.forEach { f ->
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = DownloadGreen, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(f, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (stale) {
                        Text("Reconnect to apply changes.", style = MaterialTheme.typography.bodySmall, color = WarnAmber, modifier = Modifier.padding(top = 8.dp).clickable(onClick = onReconnect))
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockingCard(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val e = settings.effective()

    fun download(name: String, url: String) {
        busy[url] = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    t2s.T2s.downloadToFile(settings.routeSpec(), url, blocklistFile(context, url).absolutePath, 0).toInt()
                }
            }
            busy[url] = false
            result.onSuccess { n ->
                onUpdate { s ->
                    val entry = Blocklist(name, url, true, n, System.currentTimeMillis())
                    s.copy(blocklists = s.blocklists.filterNot { it.url == url } + entry)
                }
                Toast.makeText(context, "$name: ${formatCount(n.toLong())} domains", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    SectionCard {
        CardHeader("Ad & Tracker Blocking", Icons.Filled.FilterAlt)
        ToggleRow(
            title = "Block ads & trackers",
            subtitle = "DNS-level blocking for every app, using the built-in list plus the lists below",
            icon = Icons.Filled.Block,
            checked = e.blockAds,
            enabled = !settings.privacyMode,
            onCheckedChange = { v -> onUpdate { it.copy(blockAds = v) } },
        )
        if (!e.remoteDns) HintText("Requires Secure DNS (Config tab).")
        BLOCKLIST_PRESETS.forEach { (name, url) ->
            val entry = settings.blocklists.firstOrNull { it.url == url }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (entry == null) "Not downloaded" else "${formatCount(entry.domains.toLong())} domains",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                if (busy[url] == true) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { download(name, url) }) {
                        Icon(if (entry == null) Icons.Filled.Download else Icons.Filled.Refresh, contentDescription = "Download")
                    }
                }
                Switch(
                    checked = entry?.enabled == true,
                    enabled = entry != null,
                    onCheckedChange = { v ->
                        onUpdate { s -> s.copy(blocklists = s.blocklists.map { if (it.url == url) it.copy(enabled = v) else it }) }
                    },
                )
            }
        }
        SettingField(
            "Extra blocked domains",
            settings.customBlocked.joinToString("\n"),
            true,
            singleLine = false,
            supporting = "One per line; subdomains are included",
        ) { v -> onUpdate { it.copy(customBlocked = splitDomains(v)) } }
        SettingField(
            "Always allowed domains",
            settings.allowedDomains.joinToString("\n"),
            true,
            singleLine = false,
            supporting = "Fix sites broken by a blocklist",
        ) { v -> onUpdate { it.copy(allowedDomains = splitDomains(v)) } }
        HintText("Lists download through your proxy and apply on the next connect.")
    }
}

private fun splitDomains(text: String) =
    text.split('\n', ',', ' ', '\t').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

@Composable
fun NavRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Muted)
    }
}

/** ok = true (green), false (amber warning), null (neutral info). */
@Composable
fun CheckRow(label: String, value: String, ok: Boolean?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (ok) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Warning; null -> Icons.Filled.Info },
            contentDescription = null,
            tint = when (ok) { true -> DownloadGreen; false -> Color(0xFFFFB74D); null -> Muted },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg),
            )
        },
        content = content,
    )
}
