package com.arafat.tun2socks.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** Resolves app identities for the tunnel's per-app reports. */
object AppIds {
    private val cache = ConcurrentHashMap<Int, String>()

    fun packageOf(context: Context, uid: Int): String {
        if (uid < 0) return UNKNOWN
        return cache.getOrPut(uid) {
            when (uid) {
                0 -> "root"
                1000 -> "android"
                else -> context.packageManager.getPackagesForUid(uid)?.sorted()?.firstOrNull() ?: "uid:$uid"
            }
        }
    }

    fun label(context: Context, pkg: String): String = when {
        pkg == UNKNOWN -> "Unknown app"
        pkg.startsWith("uid:") -> "System (${pkg.removePrefix("uid:")})"
        else -> runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    const val UNKNOWN = "unknown"
}

/** Lets the Go engine ask Android which app owns a tunnelled connection. */
class UidResolver(context: Context) : t2s.Platform {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun connectionOwner(protocol: Long, srcIP: String, srcPort: Long, dstIP: String, dstPort: Long): Long = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val proto = if (protocol.toInt() == 17) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
            cm.getConnectionOwnerUid(
                proto,
                InetSocketAddress(InetAddress.getByName(srcIP), srcPort.toInt()),
                InetSocketAddress(InetAddress.getByName(dstIP), dstPort.toInt()),
            ).toLong()
        } else {
            procNetOwner(protocol.toInt() == 17, srcPort.toInt()).toLong()
        }
    }.getOrDefault(-1L)

    /** Android 9 and older expose socket owners in /proc/net. */
    private fun procNetOwner(udp: Boolean, localPort: Int): Int {
        val files = if (udp) listOf("/proc/net/udp", "/proc/net/udp6") else listOf("/proc/net/tcp", "/proc/net/tcp6")
        val hexPort = ":%04X".format(localPort)
        for (path in files) {
            val lines = runCatching { File(path).readLines() }.getOrNull() ?: continue
            for (line in lines.drop(1)) {
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size > 7 && cols[1].endsWith(hexPort)) return cols[7].toIntOrNull() ?: -1
            }
        }
        return -1
    }
}

data class GeoInfo(val ip: String, val country: String, val countryCode: String, val timezone: String)

object PrivacyGuards {
    const val IP_URL = "https://api.ipify.org"
    const val IP6_URL = "https://api64.ipify.org"

    /** Location of the route's exit IP, fetched through the proxy. */
    fun exitGeo(spec: String): GeoInfo? = runCatching {
        val o = JSONObject(t2s.T2s.httpGet(spec, "http://ip-api.com/json/?fields=status,country,countryCode,timezone,query", 12))
        if (o.optString("status") != "success") return null
        GeoInfo(o.optString("query"), o.optString("country"), o.optString("countryCode"), o.optString("timezone"))
    }.getOrNull()

    /**
     * Returns a warning when the phone's timezone does not belong to the proxy's country - a common
     * signal websites use to detect proxies - or null when they match.
     */
    fun timezoneMismatch(geo: GeoInfo, deviceZone: String = TimeZone.getDefault().id): String? {
        if (geo.countryCode.isBlank()) return null
        val zones = countryZones(geo.countryCode)
        val canonical = canonical(deviceZone)
        val match = if (zones.isNotEmpty()) canonical in zones || deviceZone in zones else canonical == canonical(geo.timezone)
        if (match) return null
        val offsetMatch = geo.timezone.isNotBlank() &&
            TimeZone.getTimeZone(geo.timezone).getOffset(System.currentTimeMillis()) == TimeZone.getTimeZone(deviceZone).getOffset(System.currentTimeMillis())
        val hint = if (offsetMatch) " (same UTC offset, but a different region)" else ""
        return "Timezone mismatch: proxy is in ${geo.country.ifBlank { geo.countryCode }} (${geo.timezone}), phone uses $deviceZone$hint"
    }

    private fun countryZones(code: String): Set<String> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.icu.util.TimeZone.getAvailableIDs(code.uppercase()).map { canonical(it) }.toSet()
        } else emptySet()
    }.getOrDefault(emptySet())

    private fun canonical(id: String): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) android.icu.util.TimeZone.getCanonicalID(id) ?: id else id
    }.getOrDefault(id)
}
