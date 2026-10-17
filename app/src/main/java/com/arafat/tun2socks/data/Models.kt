package com.arafat.tun2socks.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.Serializable
import org.json.JSONArray
import java.io.File
import java.util.UUID

@Serializable
enum class ProxyType(val scheme: String, val label: String, val defaultPort: Int) {
    DIRECT("direct", "Direct Connection", 0),
    SOCKS5("socks5", "SOCKS5", 1080),
    HTTP("http", "HTTP", 8080),
    SHADOWSOCKS("ss", "Shadowsocks", 8388),
    SOCKS4("socks4", "SOCKS4", 1080),
}

val SS_CIPHERS = listOf("chacha20-ietf-poly1305", "aes-256-gcm", "aes-128-gcm", "xchacha20-ietf-poly1305")

@Serializable
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

@Serializable
enum class DnsMode(val label: String) { DOH("DoH"), DOT("DoT"), TCP("TCP") }

data class DnsProvider(val id: String, val name: String, val dohUrl: String, val dotHost: String, val dotSni: String, val note: String)

val DNS_PROVIDERS = listOf(
    DnsProvider("cloudflare", "Cloudflare", "https://1.1.1.1/dns-query", "1.1.1.1", "one.one.one.one", "Fast, no logging"),
    DnsProvider("quad9", "Quad9", "https://dns.quad9.net/dns-query", "9.9.9.9", "dns.quad9.net", "Blocks malware, Swiss privacy law"),
    DnsProvider("mullvad", "Mullvad", "https://dns.mullvad.net/dns-query", "194.242.2.2", "dns.mullvad.net", "Privacy-first, no logging"),
    DnsProvider("adguard", "AdGuard", "https://dns.adguard-dns.com/dns-query", "94.140.14.14", "dns.adguard-dns.com", "Blocks ads at the resolver"),
    DnsProvider("google", "Google", "https://dns.google/dns-query", "8.8.8.8", "dns.google", "Highly available"),
    DnsProvider("custom", "Custom", "", "", "", "Your own resolver"),
)

val BLOCKLIST_PRESETS = listOf(
    "StevenBlack (ads + malware)" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
    "AdGuard DNS filter" to "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
    "HaGeZi Pro (trackers)" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/pro.txt",
    "OISD small" to "https://small.oisd.nl/domainswild",
)

@Serializable
data class ProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: ProxyType = ProxyType.SOCKS5,
    val host: String = "127.0.0.1",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
    val cipher: String = SS_CIPHERS.first(),
) {
    val address: String get() = if (type == ProxyType.DIRECT) "Direct (Wi-Fi / Mobile Data)" else "${host.trim()}:$port"
    val label: String get() = name.ifBlank { address }
    val isValid: Boolean get() = if (type == ProxyType.DIRECT) true else host.isNotBlank() && port in 1..65535
    /** Orbot's local SOCKS port; Tor must be the first hop of a chain. */
    val isTor: Boolean get() = type == ProxyType.SOCKS5 && port == TOR_SOCKS_PORT && host.trim() in setOf("127.0.0.1", "localhost")
}

const val TOR_SOCKS_PORT = 9050
const val ORBOT_PACKAGE = "org.torproject.android"
fun torProfile() = ProxyProfile(name = "Tor (Orbot)", type = ProxyType.SOCKS5, host = "127.0.0.1", port = TOR_SOCKS_PORT)

@Serializable
data class Blocklist(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val domains: Int = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class Settings(
    val profiles: List<ProxyProfile> = listOf(ProxyProfile(name = "Default")),
    val selectedProfileId: String = "",
    val globalMode: Boolean = true,
    val perAppPackages: Set<String> = emptySet(),
    val ipv4: Boolean = true,
    val ipv6: Boolean = false,
    val ipv4Dns: String = "1.1.1.1",
    val ipv6Dns: String = "2606:4700:4700::1111",
    /** Resolve all DNS inside the engine over the proxy's TCP path (encrypted). Fixes mobile-data DNS. */
    val remoteDns: Boolean = true,
    val proxyUdp: Boolean = true,
    val mtu: Int = 8500,
    val udpTimeoutSec: Int = 60,
    val killSwitch: Boolean = true,
    val appLock: Boolean = false,
    val blockScreenshots: Boolean = true,
    val bypassPackages: Set<String> = emptySet(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dnsMode: DnsMode = DnsMode.DOH,
    val dnsProvider: String = "cloudflare",
    val customDohUrl: String = "",
    val customDotServer: String = "",
    val customDotSni: String = "",
    val dnsFallback: Boolean = true,
    val chainEnabled: Boolean = false,
    val chainIds: List<String> = emptyList(),
    val autoFailover: Boolean = false,
    val healthCheckSec: Int = 30,
    val blockAds: Boolean = true,
    val blocklists: List<Blocklist> = emptyList(),
    val customBlocked: List<String> = emptyList(),
    val allowedDomains: List<String> = emptyList(),
    val privacyMode: Boolean = false,
    val blockQuic: Boolean = false,
    val blockWebRtc: Boolean = false,
    val blockIpv6Leaks: Boolean = true,
    val stealthNotification: Boolean = false,
    val autoConnectOnBoot: Boolean = false,
    val reconnectOnNetworkChange: Boolean = true,
    val clipboardGuard: Boolean = false,
    val blockCnameCloaking: Boolean = true,
    val blockLan: Boolean = false,
    val leakMonitor: Boolean = true,
    val leakMonitorSec: Int = 60,
    val timezoneWarning: Boolean = true,
    val noLogsMode: Boolean = false,
    val ephemeralSession: Boolean = false,
    val trackerLogDays: Int = 7,
) {
    val selectedProfile: ProxyProfile?
        get() = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()

    val chainProfiles: List<ProxyProfile>
        get() = chainIds.mapNotNull { id -> profiles.firstOrNull { it.id == id && it.isValid } }

    val chainActive: Boolean get() = chainEnabled && chainProfiles.size >= 2

    val dnsProviderInfo: DnsProvider
        get() = DNS_PROVIDERS.firstOrNull { it.id == dnsProvider } ?: DNS_PROVIDERS.first()

    /** Settings with Privacy Mode's hardened policy applied on top of the user's choices. */
    fun effective(): Settings = if (!privacyMode) this else copy(
        killSwitch = true,
        remoteDns = true,
        dnsMode = if (dnsMode == DnsMode.TCP) DnsMode.DOH else dnsMode,
        blockAds = true,
        blockQuic = true,
        blockWebRtc = true,
        blockIpv6Leaks = true,
        stealthNotification = true,
        blockScreenshots = true,
        clipboardGuard = true,
        blockCnameCloaking = true,
        blockLan = true,
        leakMonitor = true,
        timezoneWarning = true,
    )

    /** Everything that is baked into a running tunnel; a change here needs a reconnect. */
    fun tunnelSignature(): Settings = effective().copy(
        themeMode = ThemeMode.SYSTEM, appLock = false, blockScreenshots = false, clipboardGuard = false,
        autoConnectOnBoot = false, privacyMode = false, trackerLogDays = 0, noLogsMode = false,
        ephemeralSession = false, leakMonitor = false, leakMonitorSec = 0, timezoneWarning = false,
        blocklists = blocklists.map { it.copy(domains = 0, updatedAt = 0) },
    )

    val usesTor: Boolean get() = if (chainActive) chainProfiles.any { it.isTor } else selectedProfile?.isTor == true

    /** Engine route: a single proxy URL, or a JSON array of URLs when a chain is active. */
    fun routeSpec(primary: ProxyProfile? = selectedProfile): String =
        if (chainActive) JSONArray(chainProfiles.sortedByDescending { it.isTor }.map { it.url }).toString() else primary?.takeIf { it.isValid }?.url.orEmpty()

    fun routeLabel(primary: ProxyProfile? = selectedProfile): String =
        if (chainActive) chainProfiles.sortedByDescending { it.isTor }.joinToString(" → ") { it.label } else primary?.label.orEmpty()

    val dnsLabel: String
        get() = if (!remoteDns) "System" else "${dnsMode.label} · ${dnsProviderInfo.name}"
}

private fun enc(s: String) = Uri.encode(s)

private fun bracketHost(host: String) = host.trim().let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }

/** Engine URL for this profile, e.g. socks5://user:pass@host:1080 or ss://method:pass@host:8388. */
val ProxyProfile.url: String
    get() {
        if (type == ProxyType.DIRECT) return "direct://0.0.0.0:0"
        val credentials = when (type) {
            ProxyType.SHADOWSOCKS -> "${enc(cipher)}:${enc(password)}@"
            ProxyType.SOCKS4 -> if (username.isNotBlank()) "${enc(username)}@" else ""
            else -> if (username.isNotBlank()) "${enc(username)}:${enc(password)}@" else ""
        }
        return "${type.scheme}://$credentials${bracketHost(host)}:$port"
    }

/** Shareable link (SIP002 for Shadowsocks). */
val ProxyProfile.shareLink: String
    get() {
        val tag = if (name.isNotBlank()) "#${enc(name)}" else ""
        return if (type == ProxyType.SHADOWSOCKS) {
            val info = Base64.encodeToString("$cipher:$password".toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            "ss://$info@${bracketHost(host)}:$port$tag"
        } else {
            url + tag
        }
    }

/** Parses socks5://, socks4://, http(s)://, ss:// links (one per line). */
fun parseProxyLinks(text: String): List<ProxyProfile> = text.lines().mapNotNull { parseProxyLink(it) }

fun parseProxyLink(raw: String): ProxyProfile? = runCatching {
    var s = raw.trim()
    if (s.isEmpty()) return null
    val scheme = s.substringBefore("://", "").lowercase()
    val type = when (scheme) {
        "direct" -> ProxyType.DIRECT
        "socks5", "socks5h", "socks" -> ProxyType.SOCKS5
        "socks4", "socks4a" -> ProxyType.SOCKS4
        "http", "https" -> ProxyType.HTTP
        "ss" -> ProxyType.SHADOWSOCKS
        else -> return null
    }
    // Legacy ss://BASE64(method:pass@host:port)#tag
    if (type == ProxyType.SHADOWSOCKS && !s.substringAfter("://").substringBefore("#").contains('@')) {
        val body = s.substringAfter("://").substringBefore("#")
        val tag = s.substringAfter("#", "")
        val decoded = String(Base64.decode(body, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        s = "ss://" + decoded.substringBefore(':') + ":" + enc(decoded.substringAfter(':').substringBeforeLast('@')) +
            "@" + decoded.substringAfterLast('@') + if (tag.isNotEmpty()) "#$tag" else ""
    }
    val uri = Uri.parse(s)
    if (type == ProxyType.DIRECT) {
        val name = uri.fragment.orEmpty()
        return ProxyProfile(name = name, type = ProxyType.DIRECT, host = "0.0.0.0", port = 0)
    }
    val host = uri.host?.trim('[', ']') ?: return null
    val port = uri.port.takeIf { it in 1..65535 } ?: type.defaultPort
    val name = uri.fragment.orEmpty()
    val info = uri.encodedUserInfo.orEmpty()
    var user = Uri.decode(info.substringBefore(':'))
    var pass = if (info.contains(':')) Uri.decode(info.substringAfter(':')) else ""
    var cipher = SS_CIPHERS.first()
    if (type == ProxyType.SHADOWSOCKS) {
        if (!info.contains(':') && info.isNotEmpty()) {
            val decoded = String(Base64.decode(Uri.decode(info), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            user = decoded.substringBefore(':')
            pass = decoded.substringAfter(':')
        }
        cipher = user.lowercase()
        user = ""
    }
    ProxyProfile(name = name, type = type, host = host, port = port, username = user, password = pass, cipher = cipher)
}.getOrNull()

fun blocklistFile(context: Context, url: String): File =
    File(context.filesDir, "blocklists").apply { mkdirs() }.resolve("${url.hashCode().toUInt()}.txt")
