package com.arafat.tun2socks.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arafat.tun2socks.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppRisk(val pkg: String, val label: String, val location: Boolean, val nearbyWifi: Boolean)

@Composable
fun PrivacyCheckScreen(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppRisk>?>(null) }
    LaunchedEffect(Unit) { apps = scanApps(context) }

    SubScreen("Privacy Check", onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("How apps learn about your network", style = MaterialTheme.typography.titleMedium, color = Accent)
                        Text("• Apps need Location or Nearby Wi-Fi permission to read your Wi-Fi name. Tap an app below and revoke it.", color = Muted)
                        Text("• Bypassed apps don't use the VPN, so they see a normal network, but they also see your real IP.", color = Muted)
                        Text("• Android always tells apps inside the tunnel that a VPN is active; this cannot be hidden without root.", color = Muted)
                    }
                }
            }
            val list = apps
            if (list == null) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else if (list.isEmpty()) {
                item { Text("No installed apps can read your location or Wi-Fi name.", color = Muted, modifier = Modifier.padding(8.dp)) }
            } else {
                item {
                    Text("${list.size} app(s) can read your location or Wi-Fi name", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                items(list, key = { it.pkg }) { app ->
                    val bypassed = if (settings.globalMode) app.pkg in settings.bypassPackages else app.pkg !in settings.perAppPackages
                    SectionCard(Modifier.animateItem()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { openAppDetails(context, app.pkg) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    listOfNotNull(
                                        "Location".takeIf { app.location },
                                        "Nearby Wi-Fi".takeIf { app.nearbyWifi },
                                    ).joinToString(" · ") + if (bypassed) " · bypasses VPN" else " · through VPN",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            }
                            if (settings.globalMode) {
                                TextButton(onClick = {
                                    onUpdate { s ->
                                        s.copy(bypassPackages = if (app.pkg in s.bypassPackages) s.bypassPackages - app.pkg else s.bypassPackages + app.pkg)
                                    }
                                }) { Text(if (bypassed) "Use VPN" else "Bypass") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private fun openAppDetails(context: Context, pkg: String) {
    context.startActivity(
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private suspend fun scanApps(context: Context): List<AppRisk> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val packages: List<PackageInfo> = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
    packages.mapNotNull { pi ->
        val ai = pi.applicationInfo ?: return@mapNotNull null
        if (pi.packageName == context.packageName) return@mapNotNull null
        val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
        if (system) return@mapNotNull null
        val perms = pi.requestedPermissions ?: return@mapNotNull null
        val flags = pi.requestedPermissionsFlags ?: return@mapNotNull null
        fun granted(p: String) = perms.indices.any { perms[it] == p && flags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
        val location = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val wifi = granted("android.permission.NEARBY_WIFI_DEVICES")
        if (!location && !wifi) null else AppRisk(pi.packageName, ai.loadLabel(pm).toString(), location, wifi)
    }.sortedBy { it.label.lowercase() }
}
