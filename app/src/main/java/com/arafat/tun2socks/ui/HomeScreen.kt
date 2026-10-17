package com.arafat.tun2socks.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Shield

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arafat.tun2socks.data.Settings
import com.arafat.tun2socks.vpn.Traffic
import com.arafat.tun2socks.vpn.VpnStatus

@Composable
fun HomeScreen(
    settings: Settings,
    status: VpnStatus,
    traffic: Traffic,
    error: String?,
    onToggleConnection: () -> Unit,
    onUpdate: ((Settings) -> Settings) -> Unit,
) {
    val connected = status == VpnStatus.Connected
    val route by com.arafat.tun2socks.vpn.VpnController.route.collectAsState()
    val event by com.arafat.tun2socks.vpn.VpnController.event.collectAsState()
    val latency by com.arafat.tun2socks.vpn.VpnController.latencyMs.collectAsState()
    val buttonColor by animateColorAsState(
        when (status) {
            VpnStatus.Connected -> Accent
            VpnStatus.Connecting -> Accent.copy(alpha = 0.6f)
            VpnStatus.Error -> MaterialTheme.colorScheme.error
            VpnStatus.Disconnected -> Color(0xFF9E9EA8)
        },
        label = "power",
    )

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            if (pressed) 0.92f else 1f,
            spring(dampingRatio = 0.45f, stiffness = 400f),
            label = "press",
        )
        val pulse = rememberInfiniteTransition(label = "pulse")
        val ring by pulse.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearOutSlowInEasing), RepeatMode.Restart),
            label = "ring",
        )
        val showRing = status == VpnStatus.Connected || status == VpnStatus.Connecting
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            if (showRing) {
                Box(
                    Modifier.size(180.dp)
                        .graphicsLayer {
                            scaleX = 1f + ring * 0.33f
                            scaleY = scaleX
                            alpha = (1f - ring) * 0.5f
                        }
                        .background(buttonColor, CircleShape)
                )
            }
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .shadow(28.dp, CircleShape, ambientColor = buttonColor, spotColor = buttonColor)
                    .background(buttonColor, CircleShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onToggleConnection,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = status == VpnStatus.Connecting,
                    transitionSpec = {
                        (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.6f))
                    },
                    label = "icon",
                ) { connecting ->
                    if (connecting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(56.dp))
                    } else {
                        Icon(Icons.Filled.Power, contentDescription = "Toggle VPN", tint = Color.White, modifier = Modifier.size(76.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 })
            },
            label = "status",
        ) { s ->
            Text(
                text = when (s) {
                    VpnStatus.Connected -> "Connected"
                    VpnStatus.Connecting -> "Connecting…"
                    VpnStatus.Error -> "Error"
                    VpnStatus.Disconnected -> "Not Connected"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (s == VpnStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = error ?: run {
                val r = route.ifBlank { settings.routeLabel() }.ifBlank { "No proxy configured" }
                if (connected && latency != null) "$r · $latency ms" else r
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            modifier = Modifier.padding(top = 4.dp),
        )

        androidx.compose.animation.AnimatedVisibility(event != null && connected) {
            Text(
                event.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = Accent,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        androidx.compose.animation.AnimatedVisibility(settings.privacyMode) {
            androidx.compose.material3.AssistChip(
                onClick = {},
                label = { Text("Privacy Mode active") },
                leadingIcon = { Icon(Icons.Filled.Shield, contentDescription = null, tint = DownloadGreen) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionCard {
            Text(
                "Traffic Statistics",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TrafficColumn("Upload", Icons.Filled.ArrowUpward, UploadBlue, traffic.uploadBytes, traffic.uploadPackets)
                TrafficColumn("Download", Icons.Filled.ArrowDownward, DownloadGreen, traffic.downloadBytes, traffic.downloadPackets)
            }
        }

        androidx.compose.animation.AnimatedVisibility(connected && settings.remoteDns) {
            Column {
                Spacer(Modifier.height(16.dp))
                SectionCard {
                    Text(
                        "Secure DNS · ${settings.effective().dnsLabel}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        DnsStat("Queries", traffic.dnsQueries, MaterialTheme.colorScheme.onSurface)
                        DnsStat("Blocked", traffic.dnsBlocked, MaterialTheme.colorScheme.error)
                        DnsStat("Cached", traffic.dnsCached, DownloadGreen)
                        DnsStat("Rules", traffic.blocklistSize, Muted)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard {
            ToggleRow(
                title = "Global Mode",
                subtitle = "All app traffic will go through proxy",
                icon = Icons.Filled.Public,
                checked = settings.globalMode,
                enabled = !connected,
                onCheckedChange = { value -> onUpdate { it.copy(globalMode = value) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ToggleRow(
                title = "IPv6",
                subtitle = "Enable IPv6 routing",
                icon = Icons.Filled.Language,
                checked = settings.ipv6,
                enabled = !connected,
                onCheckedChange = { value -> onUpdate { it.copy(ipv6 = value) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ToggleRow(
                title = "Secure DNS",
                subtitle = "Encrypted DNS inside the tunnel (fixes mobile data)",
                icon = Icons.Filled.Dns,
                checked = settings.effective().remoteDns,
                enabled = !connected && !settings.privacyMode,
                onCheckedChange = { value -> onUpdate { it.copy(remoteDns = value) } },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TrafficColumn(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    bytes: Long,
    packets: Long,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
        AnimatedContent(
            targetState = formatBytes(bytes),
            transitionSpec = { (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut()) },
            label = "bytes",
        ) { text ->
            Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text("${formatCount(packets)} packets", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun DnsStat(label: String, value: Long, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(formatCount(value), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tint)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
