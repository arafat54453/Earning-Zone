package com.arafat.tun2socks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arafat.tun2socks.data.DNS_PROVIDERS
import com.arafat.tun2socks.data.DnsMode
import com.arafat.tun2socks.data.ProxyType
import androidx.compose.material.icons.filled.Security
import com.arafat.tun2socks.data.Settings
import com.arafat.tun2socks.data.ThemeMode

@Composable
fun ConfigScreen(
    settings: Settings,
    locked: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onOpenProxyList: () -> Unit,
    onTor: () -> Unit = {},
) {
    val profile = settings.selectedProfile
    val effective = settings.effective()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        SectionCard {
            CardHeader("Appearance", Icons.Filled.Palette)
            ChoiceChips(ThemeMode.entries, settings.themeMode, { it.label }) { mode ->
                onUpdate { it.copy(themeMode = mode) }
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(16.dp))
        if (locked) {
            Text(
                "Disconnect to change connection settings",
                color = Muted,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        SectionCard {
            CardHeader("Proxy", Icons.Filled.Public) {
                IconButton(onClick = onOpenProxyList) {
                    Icon(Icons.Filled.Menu, contentDescription = "Proxy list")
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    if (settings.chainActive) {
                        InfoRow("Proxy chain", "${settings.chainProfiles.size} hops", Icons.Filled.Link)
                        InfoRow("Route", settings.routeLabel(), Icons.Filled.Route)
                    } else {
                        InfoRow("${profile?.type?.label ?: "Proxy"} Address", profile?.address ?: "—", Icons.Filled.Language)
                        if (profile?.type == ProxyType.SHADOWSOCKS) {
                            InfoRow("Cipher", profile.cipher, Icons.Filled.EnhancedEncryption)
                        } else {
                            InfoRow("Username", profile?.username?.ifBlank { "—" } ?: "—", Icons.Filled.Person)
                        }
                        InfoRow("Password", if (profile?.password.isNullOrEmpty()) "—" else "••••••", Icons.Filled.Lock)
                    }
                }
            }
            ToggleRow(
                title = "Proxy UDP",
                subtitle = "Relay UDP (games, calls) via SOCKS5/Shadowsocks UDP. Off drops UDP instead of leaking it. DNS never needs it.",
                checked = settings.proxyUdp,
                enabled = !locked,
                onCheckedChange = { value -> onUpdate { it.copy(proxyUdp = value) } },
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionCard {
            CardHeader("Tor", Icons.Filled.Security)
            NavRow(
                if (settings.usesTor) "Connected via Tor (Orbot)" else "Connect via Tor (Orbot)",
                "One tap: starts Orbot and routes through its Tor SOCKS port. Add \"Tor (Orbot)\" to a proxy chain to use Tor as the first hop.",
                Icons.Filled.Security,
                onTor,
            )
        }
        Spacer(Modifier.height(16.dp))
        SectionCard {
            CardHeader("Encrypted DNS", Icons.Filled.Dns)
            ToggleRow(
                title = "Secure DNS",
                subtitle = "Answer every DNS query inside the tunnel and send it encrypted over the proxy's TCP path. Works on mobile data even when the proxy has no UDP.",
                checked = effective.remoteDns,
                enabled = !locked && !settings.privacyMode,
                onCheckedChange = { value -> onUpdate { it.copy(remoteDns = value) } },
            )
            AnimatedVisibility(effective.remoteDns) {
                Column {
                    ChoiceChips(
                        if (settings.privacyMode) listOf(DnsMode.DOH, DnsMode.DOT) else DnsMode.entries,
                        effective.dnsMode,
                        { it.label },
                        enabled = !locked,
                    ) { mode -> onUpdate { it.copy(dnsMode = mode) } }
                    ChoiceChips(DNS_PROVIDERS, settings.dnsProviderInfo, { it.name }, enabled = !locked) { p ->
                        onUpdate { it.copy(dnsProvider = p.id) }
                    }
                    HintText(settings.dnsProviderInfo.note)
                    if (settings.dnsProviderInfo.id == "custom") {
                        when (effective.dnsMode) {
                            DnsMode.DOH -> SettingField("DoH URL", settings.customDohUrl, !locked, supporting = "e.g. https://doh.example.com/dns-query") { v ->
                                onUpdate { it.copy(customDohUrl = v) }
                            }
                            DnsMode.DOT -> {
                                SettingField("DoT server (IP or host[:port])", settings.customDotServer, !locked) { v -> onUpdate { it.copy(customDotServer = v) } }
                                SettingField("TLS server name (SNI)", settings.customDotSni, !locked) { v -> onUpdate { it.copy(customDotSni = v) } }
                            }
                            DnsMode.TCP -> Unit
                        }
                    }
                    if (effective.dnsMode == DnsMode.TCP) {
                        SettingField("DNS server", settings.ipv4Dns, !locked, supporting = "Plain DNS over TCP through the proxy (not encrypted end-to-end)") { v ->
                            onUpdate { it.copy(ipv4Dns = v) }
                        }
                    }
                    ToggleRow(
                        title = "Encrypted fallback",
                        subtitle = "If the chosen resolver fails, retry via Cloudflare/Quad9 DoH",
                        checked = settings.dnsFallback,
                        enabled = !locked,
                        onCheckedChange = { value -> onUpdate { it.copy(dnsFallback = value) } },
                    )
                }
            }
            AnimatedVisibility(!effective.remoteDns) {
                Column {
                    SettingField("IPv4 DNS", settings.ipv4Dns, !locked) { v -> onUpdate { it.copy(ipv4Dns = v) } }
                    SettingField("IPv6 DNS", settings.ipv6Dns, !locked) { v -> onUpdate { it.copy(ipv6Dns = v) } }
                    HintText("Plain DNS goes through proxy UDP and fails if the proxy lacks UDP support.")
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(16.dp))
        SectionCard {
            CardHeader("Network", Icons.Filled.Wifi)
            ToggleRow(
                title = "IPv4",
                subtitle = "Enable IPv4 routing",
                checked = settings.ipv4,
                enabled = !locked,
                onCheckedChange = { value -> onUpdate { it.copy(ipv4 = value) } },
            )
            ToggleRow(
                title = "IPv6",
                subtitle = "Route IPv6 through the proxy",
                checked = settings.ipv6,
                enabled = !locked,
                onCheckedChange = { value -> onUpdate { it.copy(ipv6 = value) } },
            )
            ToggleRow(
                title = "Block IPv6 leaks",
                subtitle = "When IPv6 is off, capture and drop it so it can't bypass the tunnel",
                checked = effective.blockIpv6Leaks,
                enabled = !locked && !settings.ipv6 && !settings.privacyMode,
                onCheckedChange = { value -> onUpdate { it.copy(blockIpv6Leaks = value) } },
            )
            SettingField("MTU", settings.mtu.toString(), !locked, KeyboardType.Number) { v ->
                v.toIntOrNull()?.let { mtu -> onUpdate { it.copy(mtu = mtu) } }
            }
            SettingField("UDP Timeout (seconds)", settings.udpTimeoutSec.toString(), !locked, KeyboardType.Number) { v ->
                v.toIntOrNull()?.let { t -> onUpdate { it.copy(udpTimeoutSec = t) } }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))
        SectionCard {
            CardHeader("Automation", Icons.Filled.Autorenew)
            ToggleRow(
                title = "Connect on boot",
                subtitle = "Reconnect automatically after the phone restarts",
                checked = settings.autoConnectOnBoot,
                onCheckedChange = { value -> onUpdate { it.copy(autoConnectOnBoot = value) } },
            )
            ToggleRow(
                title = "Recover on network change",
                subtitle = "Reset sockets and DNS cache when switching Wi-Fi ↔ mobile data",
                checked = settings.reconnectOnNetworkChange,
                enabled = !locked,
                onCheckedChange = { value -> onUpdate { it.copy(reconnectOnNetworkChange = value) } },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
