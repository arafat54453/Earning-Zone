package com.arafat.tun2socks.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arafat.tun2socks.data.Settings
import com.arafat.tun2socks.data.url
import com.arafat.tun2socks.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class LeakResult(
    val realIp: String?,
    val exitIp: String?,
    val exitInfo: String?,
    val flagged: Boolean?,
    val dns: List<String>,
    val error: String?,
)

@Composable
fun LeakTestScreen(settings: Settings, status: VpnStatus, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LeakResult?>(null) }

    SubScreen("Leak Test", onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = {
                    running = true
                    scope.launch {
                        result = runLeakTest(context, settings, status)
                        running = false
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                AnimatedContent(running, label = "run") { busy ->
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(if (result == null) "Run Leak Test" else "Run Again")
                    }
                }
            }
            AnimatedVisibility(result != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                val r = result ?: return@AnimatedVisibility
                val hidden = r.exitIp != null && r.exitIp != r.realIp
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionCard {
                        CheckRow("Real IP (without proxy)", r.realIp ?: "unavailable", null)
                        CheckRow("Exit IP (through proxy)", r.exitIp ?: (r.error ?: "failed"), r.exitIp != null)
                        CheckRow("Real IP hidden", if (hidden) "Yes" else "No", hidden)
                        r.exitInfo?.let { CheckRow("Exit location / ISP", it, null) }
                        r.flagged?.let {
                            CheckRow(
                                "Exit IP flagged as proxy/datacenter",
                                if (it) "Yes – websites and apps may detect the proxy" else "No",
                                !it,
                            )
                        }
                    }
                    SectionCard {
                        val active = status == VpnStatus.Connected
                        CheckRow("VPN tunnel", if (active) "Active" else "Not connected – connect first for full protection", active)
                        CheckRow("DNS servers in tunnel", r.dns.ifEmpty { listOf("none") }.joinToString(), r.dns.isNotEmpty())
                        CheckRow("IPv6", if (settings.ipv6) "Routed through tunnel" else "Blocked (no IPv6 leak)", true)
                        CheckRow("UDP / WebRTC", if (settings.proxyUdp) "Through proxy" else "Blocked", true)
                        CheckRow("Kill Switch", if (settings.killSwitch) "On" else "Off", settings.killSwitch)
                    }
                }
            }
        }
    }
}

private suspend fun runLeakTest(context: Context, settings: Settings, status: VpnStatus): LeakResult =
    withContext(Dispatchers.IO) {
        val realIp = runCatching { t2s.T2s.httpGet("", "https://api.ipify.org", 8).trim() }.getOrNull()
        var exitIp: String? = null
        var info: String? = null
        var flagged: Boolean? = null
        var error: String? = null
        val spec = settings.routeSpec()
        if (spec.isBlank()) {
            error = "No proxy configured"
        } else {
            runCatching {
                JSONObject(
                    t2s.T2s.httpGet(
                        spec,
                        "http://ip-api.com/json/?fields=status,message,country,city,isp,query,proxy,hosting",
                        12,
                    )
                )
            }.onSuccess { o ->
                exitIp = o.optString("query").ifBlank { null }
                info = listOf(o.optString("city"), o.optString("country"), o.optString("isp"))
                    .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
                flagged = if (o.has("proxy")) o.optBoolean("proxy") || o.optBoolean("hosting") else null
            }.onFailure { error = "Proxy unreachable: ${it.message}" }
        }
        LeakResult(realIp, exitIp, info, flagged, tunnelDns(context, settings, status), error)
    }

private fun tunnelDns(context: Context, settings: Settings, status: VpnStatus): List<String> {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    val vpn = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    val fromSystem = vpn?.let { cm.getLinkProperties(it)?.dnsServers?.mapNotNull { a -> a.hostAddress } }.orEmpty()
    if (fromSystem.isNotEmpty()) {
        return fromSystem.map { if (it == "198.18.0.2" || it == "fc00::2") "In-tunnel ${settings.effective().dnsLabel}" else it }.distinct()
    }
    if (status != VpnStatus.Connected || !settings.remoteDns) return emptyList()
    return buildList {
        if (settings.ipv4 && settings.ipv4Dns.isNotBlank()) add(settings.ipv4Dns.trim())
        if (settings.ipv6 && settings.ipv6Dns.isNotBlank()) add(settings.ipv6Dns.trim())
    }
}
