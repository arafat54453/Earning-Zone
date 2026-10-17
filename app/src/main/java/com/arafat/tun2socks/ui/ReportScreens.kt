package com.arafat.tun2socks.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arafat.tun2socks.data.ActivityStore
import com.arafat.tun2socks.data.Settings
import com.arafat.tun2socks.data.TrackerStore
import com.arafat.tun2socks.vpn.AppIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun TrackerReportScreen(settings: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by TrackerStore.entries.collectAsState()
    LaunchedLoad(settings)
    SubScreen("App Tracker Report", onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val total = entries.sumOf { it.count }
            SectionCard {
                CardHeader("Summary", Icons.Filled.Policy)
                CheckRow("Tracking attempts blocked", formatCount(total), true)
                CheckRow("Apps with trackers", entries.map { it.pkg }.distinct().size.toString(), null)
                CheckRow("Tracker companies", entries.map { it.company }.distinct().size.toString(), null)
                HintText(
                    if (settings.noLogsMode) "No-logs mode: this report lives in memory only and is erased on disconnect."
                    else "Stored encrypted on this phone only and deleted automatically after ${settings.trackerLogDays} days."
                )
            }
            if (entries.isEmpty()) HintText("No tracking attempts yet. Connect and use your apps; blocked trackers appear here.")
            entries.groupBy { it.pkg }.entries.sortedByDescending { e -> e.value.sumOf { it.count } }.forEach { (pkg, list) ->
                SectionCard {
                    CardHeader("${AppIds.label(context, pkg)} · ${formatCount(list.sumOf { it.count })} blocked", Icons.Filled.Apps)
                    list.groupBy { it.company }.entries.sortedByDescending { e -> e.value.sumOf { it.count } }.forEach { (company, hits) ->
                        val domains = hits.map { if (it.cname.isNotBlank()) "${it.domain} → ${it.cname}" else it.domain }.distinct()
                        CheckRow(company, "${formatCount(hits.sumOf { it.count })}× · ${domains.take(4).joinToString()}", false)
                    }
                }
            }
            if (entries.isNotEmpty()) {
                TextButton(onClick = { TrackerStore.clear(context) }, modifier = Modifier.fillMaxWidth()) { Text("Clear report") }
            }
        }
    }
}

@Composable
private fun LaunchedLoad(settings: Settings) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { TrackerStore.load(context, settings.trackerLogDays, settings.noLogsMode) }
    }
}

@Composable
fun DataUsageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val apps by ActivityStore.apps.collectAsState()
    var expanded by remember { mutableStateOf<Int?>(null) }
    SubScreen("Per-App Data Usage", onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (apps.isEmpty()) HintText("No traffic recorded in this session yet. Usage is kept in memory only.")
            apps.forEach { app ->
                SectionCard {
                    Column(Modifier.clickable { expanded = if (expanded == app.uid) null else app.uid }) {
                        CardHeader(AppIds.label(context, app.pkg), Icons.Filled.DataUsage)
                        InfoRow("Sent", formatBytes(app.up), Icons.Filled.DataUsage)
                        InfoRow("Received", formatBytes(app.down), Icons.Filled.DataUsage)
                        InfoRow("Connections", "${app.conns}" + if (app.blocked > 0) " · ${app.blocked} blocked" else "", Icons.Filled.Block)
                    }
                    val dests = if (expanded == app.uid) app.dests else app.dests.take(3)
                    dests.forEach { d ->
                        CheckRow(
                            d.host,
                            if (d.conns == 0L && d.blocked > 0) "Blocked ${d.blocked}×" else "↑ ${formatBytes(d.up)}  ↓ ${formatBytes(d.down)} · ${d.conns} conn",
                            if (d.blocked > 0 && d.conns == 0L) false else null,
                        )
                    }
                    if (app.dests.size > 3 && expanded != app.uid) HintText("Tap to show all ${app.dests.size} destinations")
                }
            }
        }
    }
}

@Composable
fun AnonymityScreen(settings: Settings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    SubScreen("Proxy Anonymity", onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = {
                    running = true
                    error = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { JSONObject(t2s.T2s.anonymityTest(settings.routeSpec(), 15)) } }
                        result = r.getOrNull()
                        error = r.exceptionOrNull()?.message
                        running = false
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                AnimatedContent(running, label = "anon") { busy ->
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(if (result == null) "Run Anonymity Test" else "Run Again")
                }
            }
            HintText("Route: ${settings.routeLabel().ifBlank { "no proxy" }}")
            error?.let { SectionCard { CheckRow("Test failed", it, false) } }
            result?.let { r ->
                val level = r.optString("level")
                SectionCard {
                    CardHeader("Anonymity", Icons.Filled.VisibilityOff)
                    CheckRow(
                        "Level",
                        when (level) {
                            "elite" -> "Elite – no proxy headers, real IP hidden"
                            "anonymous" -> "Anonymous – real IP hidden, but headers reveal a proxy"
                            "transparent" -> "Transparent – your real IP is exposed"
                            else -> "Unknown – header check failed"
                        },
                        when (level) { "elite" -> true; "unknown" -> null; else -> false },
                    )
                    CheckRow("Real IP", r.optString("realIp").ifBlank { "unavailable" }, null)
                    CheckRow("Exit IP", r.optString("exitIp"), !r.optBoolean("realIpLeaked"))
                    val leaks = r.optJSONArray("leakHeaders")
                    CheckRow("Real IP in HTTP headers", if (leaks == null || leaks.length() == 0) "No" else "Yes: ${leaks.join(", ").replace("\"", "")}", leaks == null || leaks.length() == 0)
                    val ph = r.optJSONArray("proxyHeaders")
                    CheckRow("Proxy-revealing headers", if (ph == null || ph.length() == 0) "None" else ph.join(", ").replace("\"", ""), ph == null || ph.length() == 0)
                    r.optString("error").takeIf { it.isNotBlank() }?.let { CheckRow("Header check", it, null) }
                }
                SectionCard {
                    CardHeader("Blacklists", Icons.Filled.Block)
                    val listed = r.optInt("listed")
                    CheckRow("Exit IP blacklisted", if (listed > 0) "Listed on $listed of ${r.optInt("checked")} lists" else "Not listed (${r.optInt("checked")} lists checked)", listed == 0)
                    val bl = r.optJSONArray("blacklists")
                    if (bl != null) for (i in 0 until bl.length()) {
                        val b = bl.getJSONObject(i)
                        CheckRow(
                            b.optString("zone"),
                            when { b.optBoolean("listed") -> "Listed (${b.optString("answer")})"; b.optString("error").isNotBlank() -> "Not checked: ${b.optString("error")}"; else -> "Clean" },
                            when { b.optBoolean("listed") -> false; b.optString("error").isNotBlank() -> null; else -> true },
                        )
                    }
                }
            }
        }
    }
}
