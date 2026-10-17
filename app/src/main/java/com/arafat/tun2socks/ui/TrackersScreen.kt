package com.arafat.tun2socks.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arafat.tun2socks.data.Settings
import com.arafat.tun2socks.data.TrackerEvent
import com.arafat.tun2socks.data.TrackerStore
import com.arafat.tun2socks.vpn.AppIds
import com.arafat.tun2socks.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val BlockedRed = Color(0xFFE53935)

/** Live tracker monitor: what is being blocked right now, by which app, for which company. */
@Composable
fun TrackersScreen(settings: Settings, status: VpnStatus) {
    val context = LocalContext.current
    val entries by TrackerStore.entries.collectAsState()
    val recent by TrackerStore.recent.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var cnameOnly by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { TrackerStore.load(context, settings.trackerLogDays, settings.noLogsMode) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(5000)
        }
    }
    val live = status == VpnStatus.Connected
    val total = entries.sumOf { it.count }
    val apps = entries.map { it.pkg }.distinct().size
    val cloaks = entries.filter { it.cname.isNotBlank() }.sumOf { it.count }
    val companies = entries.groupBy { it.company }.mapValues { e -> e.value.sumOf { it.count } }
        .entries.sortedByDescending { it.value }.take(6)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatCount(total), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = DownloadGreen)
                    Text("Tracking attempts blocked", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (live) DownloadGreen else Muted, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (live) "Live · real-time protection" else "Paused · connect to monitor",
                            style = MaterialTheme.typography.bodySmall, color = Muted,
                        )
                    }
                }
                Box(
                    Modifier.size(56.dp).background(DownloadGreen.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Shield, contentDescription = null, tint = DownloadGreen, modifier = Modifier.size(28.dp)) }
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("$apps Apps protected", DownloadGreen)
                Pill("${formatCount(cloaks)} CNAME cloaks defeated", Accent)
            }
        }

        SectionCard {
            CardHeader("Top blocked tracker networks", Icons.Filled.BarChart)
            if (companies.isEmpty()) {
                HintText("Nothing blocked yet.")
            } else {
                val max = companies.first().value.coerceAtLeast(1)
                companies.forEach { (company, count) ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(company, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${formatCount(count)} times", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { count.toFloat() / max },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = DownloadGreen,
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            drawStopIndicator = {},
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        HintText(
            if (settings.noLogsMode) "No-logs mode: tracking records stay in memory only and are erased on disconnect."
            else "Local storage only: tracking records never leave your phone and are deleted after ${settings.trackerLogDays} days."
        )

        SectionCard(Modifier.animateContentSize()) {
            CardHeader("Recent blocked activity", Icons.Filled.History) {
                TextButton(onClick = { cnameOnly = !cnameOnly }) { Text(if (cnameOnly) "CNAME only" else "All") }
            }
            val shown = recent.filter { !cnameOnly || it.cname.isNotBlank() }.take(100)
            if (shown.isEmpty()) {
                HintText(if (live) "Watching… blocked trackers will appear here instantly." else "Connect and use your apps; blocked trackers appear here live.")
            }
            shown.forEachIndexed { i, e ->
                key(e.time, e.pkg, e.domain) { ActivityRow(AppIds.label(context, e.pkg), e, now) }
                if (i < shown.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (entries.isNotEmpty() || recent.isNotEmpty()) {
            TextButton(onClick = { TrackerStore.clear(context) }, modifier = Modifier.fillMaxWidth()) { Text("Clear tracker history") }
        }
    }
}

@Composable
private fun ActivityRow(app: String, e: TrackerEvent, now: Long) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(app, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.domain, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.cname.isNotBlank()) {
                Text("→ ${e.cname}", style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${e.company} · ${ago(now - e.time)}" + if (e.count > 1) " · ${e.count}×" else "",
                style = MaterialTheme.typography.bodySmall, color = DownloadGreen,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Badge("BLOCKED", BlockedRed)
            if (e.cname.isNotBlank()) Badge("CNAME CLOAKED", Accent)
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

private fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 10 -> "Just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}
