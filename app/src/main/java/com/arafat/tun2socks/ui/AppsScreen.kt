package com.arafat.tun2socks.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arafat.tun2socks.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val label: String, val packageName: String, val system: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    settings: Settings,
    locked: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { apps = loadApps(context) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SingleChoiceSegmentedButtonRow(
            Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp),
        ) {
            SegmentedButton(
                selected = settings.globalMode,
                onClick = { onUpdate { it.copy(globalMode = true) } },
                enabled = !locked,
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Global Mode") }
            SegmentedButton(
                selected = !settings.globalMode,
                onClick = { onUpdate { it.copy(globalMode = false) } },
                enabled = !locked,
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Per-App") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (settings.globalMode) {
            Text(
                "Global mode: every app goes through the proxy. Tick apps that should bypass the VPN (${settings.bypassPackages.size} bypassed).",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Text(
                "${settings.perAppPackages.size} app(s) selected",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val selected = if (settings.globalMode) settings.bypassPackages else settings.perAppPackages
            val filtered = list.filter {
                query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
            }.sortedWith(compareByDescending<InstalledApp> { it.packageName in selected }.thenBy { it.label.lowercase() })
            val editable = !locked
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    Row(
                        Modifier.animateItem().fillMaxWidth()
                            .clickable(enabled = editable) { toggle(onUpdate, app.packageName) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Checkbox(
                            checked = checked,
                            enabled = editable,
                            onCheckedChange = { toggle(onUpdate, app.packageName) },
                        )
                    }
                }
            }
        }
    }
}

private fun toggle(onUpdate: ((Settings) -> Settings) -> Unit, pkg: String) {
    onUpdate { s ->
        if (s.globalMode) {
            s.copy(bypassPackages = if (pkg in s.bypassPackages) s.bypassPackages - pkg else s.bypassPackages + pkg)
        } else {
            s.copy(perAppPackages = if (pkg in s.perAppPackages) s.perAppPackages - pkg else s.perAppPackages + pkg)
        }
    }
}

private suspend fun loadApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { it.packageName != context.packageName }
        .map {
            InstalledApp(
                label = pm.getApplicationLabel(it).toString(),
                packageName = it.packageName,
                system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }
}
