package com.arafat.tun2socks.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.arafat.tun2socks.MainActivity
import com.arafat.tun2socks.R
import com.arafat.tun2socks.data.ActivityStore
import com.arafat.tun2socks.data.DnsMode
import com.arafat.tun2socks.data.ORBOT_PACKAGE
import com.arafat.tun2socks.data.TrackerStore
import com.arafat.tun2socks.data.ProxyProfile
import com.arafat.tun2socks.data.Settings
import com.arafat.tun2socks.data.SettingsRepository
import com.arafat.tun2socks.data.blocklistFile
import com.arafat.tun2socks.data.url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class Tun2SocksVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // The engine owns the detached TUN fd and closes it on stop; closing it here too trips fdsan.
    @Volatile private var engineRunning = false
    // stopSelf(id) is ignored if a newer start arrived meanwhile, so quick off/on toggles don't kill the new session.
    @Volatile private var lastStartId = 0
    // Kill switch: a TUN with no engine behind it, so all routed traffic is dropped instead of leaking.
    private var blockingTun: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private var healthJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var leakJob: Job? = null
    @Volatile private var activeProfileId: String? = null
    private var activeSettings = Settings()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> ENGINE.execute { stopTunnel(startId) }
            ACTION_RESTART -> {
                startForegroundNotification(getString(R.string.status_connecting))
                ENGINE.execute {
                    stopTunnel(startId, keepService = true)
                    startTunnel()
                }
            }
            else -> {
                // Must go foreground promptly: we may have been started via startForegroundService (tile, boot).
                if (!engineRunning) startForegroundNotification(getString(R.string.status_connecting))
                ENGINE.execute { startTunnel() }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        ENGINE.execute { stopTunnel() }
        super.onRevoke()
    }

    override fun onDestroy() {
        ENGINE.execute { stopTunnel() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startTunnel() {
        if (engineRunning) {
            // A duplicate start (tile + app) must not leave the UI stuck on "Connecting".
            VpnController.setStatus(VpnStatus.Connected)
            return
        }
        val raw = SettingsRepository.get(this).settings.value
        val settings = raw.effective()
        activeSettings = settings
        TrackerStore.load(this, settings.trackerLogDays, settings.noLogsMode)
        val profile = settings.selectedProfile
        val spec = settings.routeSpec(profile)
        if (spec.isBlank()) {
            fail("No proxy configured")
            return
        }
        VpnController.setError(null)
        VpnController.setEvent(null)
        VpnController.setLatency(null)
        VpnController.setStatus(VpnStatus.Connecting)

        // The previous engine must have released its TUN fd before a new interface can reuse the number.
        if (!runCatching { t2s.T2s.waitIdle(12) }.getOrDefault(true)) {
            fail("Previous session is still shutting down. Please try again.")
            return
        }
        val descriptor = try {
            buildInterface(settings)
        } catch (e: Exception) {
            fail("Failed to establish VPN interface: ${e.message}")
            return
        }
        if (descriptor == null) {
            fail("VPN permission was revoked")
            return
        }
        val fd = descriptor.detachFd()
        runCatching { t2s.T2s.setPlatform(UidResolver(this)) }
        try {
            val config = engineConfig(settings, spec)
            val start = NATIVE.submit { t2s.T2s.start(fd.toLong(), config) }
            try {
                start.get(START_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw (e.cause as? Exception) ?: e
            } catch (e: java.util.concurrent.TimeoutException) {
                start.cancel(true)
                throw IllegalStateException("timed out after ${START_TIMEOUT_SEC}s")
            }
        } catch (e: Exception) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            if (settings.killSwitch) {
                blockingTun = runCatching { buildInterface(settings) }.getOrNull()
                if (blockingTun != null) {
                    startForegroundNotification("Kill switch active · traffic blocked")
                    fail("Kill switch: engine failed (${e.message}), all traffic blocked", stop = false)
                    return
                }
            }
            fail("Engine failed to start: ${e.message}")
            return
        }
        engineRunning = true
        blockingTun?.let { runCatching { it.close() } }
        blockingTun = null
        activeProfileId = if (settings.chainActive) null else profile?.id
        VpnController.setApplied(raw.tunnelSignature())

        VpnController.setRoute(settings.routeLabel(profile))
        VpnController.setStatus(VpnStatus.Connected)
        startForegroundNotification(connectedText(settings.routeLabel(profile)))
        startStatsPolling()
        if (settings.autoFailover && !settings.chainActive && settings.profiles.count { it.isValid } > 1) startHealthChecks()
        if (settings.reconnectOnNetworkChange) registerNetworkCallback()
        if (settings.leakMonitor || settings.timezoneWarning) startLeakMonitor(settings)
    }

    private fun connectedText(route: String) =
        if (activeSettings.stealthNotification) getString(R.string.status_protected) else "Connected · $route"

    private fun stopTunnel(startId: Int = lastStartId, keepService: Boolean = false) {
        statsJob?.cancel()
        statsJob = null
        leakJob?.cancel()
        leakJob = null
        healthJob?.cancel()
        healthJob = null
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        val wasRunning = engineRunning
        if (engineRunning) {
            if (!activeSettings.noLogsMode && !activeSettings.ephemeralSession) drainActivity()
            runCatching { t2s.T2s.stop() }.onFailure { log("engine stop failed: ${it.message}") }
            engineRunning = false
        }
        blockingTun?.let { runCatching { it.close() } }
        blockingTun = null
        VpnController.setApplied(null)
        endSession(wasRunning)
        VpnController.setTraffic(Traffic())
        VpnController.setLatency(null)
        VpnController.setRoute("")
        if (keepService) return
        VpnController.setStatus(VpnStatus.Disconnected)
        removeForeground()
        stopSelf(startId)
    }

    /** No-logs / ephemeral session: forget DNS cache, statistics and recent activity on disconnect. */
    private fun endSession(wasRunning: Boolean) {
        val s = activeSettings
        if (s.noLogsMode || s.ephemeralSession) {
            runCatching { t2s.T2s.clearActivity() }
            ActivityStore.clear()
            VpnController.setEvent(null)
            if (s.ephemeralSession) TrackerStore.clear(this) else TrackerStore.persist(this, s.trackerLogDays, true)
            if (s.noLogsMode) {
                TrackerStore.clear(this)
                VpnController.setError(null)
            }
        } else if (wasRunning) {
            TrackerStore.persist(this, s.trackerLogDays, false)
        }
    }

    private fun drainTrackerHits() {
        runCatching { t2s.T2s.drainTrackers() }.getOrNull()?.let { raw ->
            TrackerStore.merge(raw, activeSettings.trackerLogDays) { AppIds.packageOf(this, it) }
        }
    }

    private fun drainActivity() {
        drainTrackerHits()
        runCatching { t2s.T2s.activity() }.getOrNull()?.let { raw -> ActivityStore.update(raw) { AppIds.packageOf(this, it) } }
    }

    private fun log(message: String) {
        if (!activeSettings.noLogsMode) Log.w(TAG, message)
    }

    /**
     * Background leak monitor: compares the IP websites see through the tunnel with the real IP of the
     * underlying network. If they match, warns immediately and cuts the connection (traffic blocked).
     * Also warns once when the proxy country does not match the phone's timezone.
     */
    private fun startLeakMonitor(settings: Settings) {
        leakJob = scope.launch {
            delay(4000)
            var realIp: String? = null
            var realIp6: String? = null
            var realCheckedAt = 0L
            var tzChecked = false
            while (isActive && engineRunning) {
                if (settings.timezoneWarning && !tzChecked) {
                    val spec = settings.routeSpec(settings.profiles.firstOrNull { it.id == activeProfileId } ?: settings.selectedProfile)
                    PrivacyGuards.exitGeo(spec)?.let { geo ->
                        tzChecked = true
                        PrivacyGuards.timezoneMismatch(geo)?.let { warning ->
                            VpnController.setEvent(warning)
                            alert("Timezone mismatch", warning, ALERT_TZ_ID)
                        }
                    }
                }
                if (!settings.leakMonitor) {
                    if (tzChecked) break
                    delay(15_000)
                    continue
                }
                val now = System.currentTimeMillis()
                if (realIp == null || now - realCheckedAt > 5 * 60_000) {
                    realIp = runCatching { t2s.T2s.httpGet("", PrivacyGuards.IP_URL, 8).trim() }.getOrNull()?.ifBlank { null } ?: realIp
                    realIp6 = runCatching { t2s.T2s.httpGet("", PrivacyGuards.IP6_URL, 8).trim() }.getOrNull()?.takeIf { it.contains(':') }
                    realCheckedAt = now
                }
                val exit = runCatching { t2s.T2s.exitIP(PrivacyGuards.IP_URL, if (settings.usesTor) 25 else 10).trim() }.getOrNull()
                val leaked = exit != null && exit.isNotBlank() && (exit == realIp || exit == realIp6)
                if (leaked && engineRunning) {
                    ENGINE.execute { onLeak(exit!!) }
                    break
                }
                delay(settings.leakMonitorSec.coerceIn(15, 3600) * 1000L)
            }
        }
    }

    private fun onLeak(ip: String) {
        if (!engineRunning) return
        val message = "Leak detected: your real IP ($ip) was visible. Connection stopped and traffic blocked."
        val settings = activeSettings
        stopTunnel(keepService = true)
        alert("Real IP leak detected", message, ALERT_LEAK_ID)
        blockingTun = runCatching { buildInterface(settings) }.getOrNull()
        if (blockingTun != null) {
            startForegroundNotification("Leak detected · traffic blocked")
            fail(message, stop = false)
        } else {
            fail(message)
        }
    }

    private fun alert(title: String, text: String, id: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, "Privacy alerts", NotificationManager.IMPORTANCE_HIGH))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, ALERT_CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
        val n = builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        runCatching { manager.notify(id, n) }
    }

    private fun removeForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun fail(message: String, stop: Boolean = true) {
        log(message)
        VpnController.setError(message)
        VpnController.setStatus(VpnStatus.Error)
        if (stop) {
            removeForeground()
            stopSelf()
        }
    }

    /** JSON consumed by the Go bridge's Start(). */
    private fun engineConfig(s: Settings, spec: String): String {
        val proxies = if (spec.startsWith("[")) JSONArray(spec) else JSONArray().put(spec)
        val provider = s.dnsProviderInfo
        val custom = provider.id == "custom"
        val dns = JSONObject()
            .put("enabled", s.remoteDns)
            .put("mode", s.dnsMode.name.lowercase())
            .put("url", if (custom) s.customDohUrl.trim() else provider.dohUrl)
            .put(
                "server",
                when (s.dnsMode) {
                    DnsMode.DOT -> if (custom) s.customDotServer.trim() else provider.dotHost
                    DnsMode.TCP -> s.ipv4Dns.trim().ifBlank { "1.1.1.1" }
                    DnsMode.DOH -> ""
                },
            )
            .put("serverName", if (custom) s.customDotSni.trim() else provider.dotSni)
            .put("fallback", s.dnsFallback)
            .put("blockAds", s.blockAds)
            .put("listFiles", JSONArray(s.blocklists.filter { it.enabled }.map { blocklistFile(this, it.url).absolutePath }))
            .put("custom", JSONArray(s.customBlocked))
            .put("allow", JSONArray(s.allowedDomains))
            .put("blockCname", s.blockCnameCloaking)
            .put("fakeIp", true)
        return JSONObject()
            .put("proxies", proxies)
            .put("mtu", s.mtu.coerceIn(1280, 9000))
            .put("udpTimeout", s.udpTimeoutSec)
            .put("proxyUdp", s.proxyUdp)
            .put("blockQuic", s.blockQuic)
            .put("blockStun", s.blockWebRtc)
            .put("blockIpv6", !s.ipv6 && s.blockIpv6Leaks)
            .put("blockLan", s.blockLan)
            .put("logLevel", if (s.noLogsMode) "silent" else "warning")
            .put("dns", dns)
            .toString()
    }

    private fun buildInterface(settings: Settings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(settings.mtu.coerceIn(1280, 9000))

        val v4 = settings.ipv4 || !settings.ipv6
        // With IPv6 off we still capture it (and the engine rejects it) so it cannot bypass the tunnel.
        val v6 = settings.ipv6 || settings.blockIpv6Leaks
        if (v4) {
            builder.addAddress(IPV4_ADDRESS, 32)
            builder.addRoute("0.0.0.0", 0)
        }
        if (v6) {
            builder.addAddress(IPV6_ADDRESS, 128)
            builder.addRoute("::", 0)
        }
        if (settings.remoteDns) {
            // Virtual resolver answered inside the engine; any other port-53 traffic is intercepted too.
            builder.addDnsServer(DNS_VIP4)
            if (settings.ipv6) builder.addDnsServer(DNS_VIP6)
        } else {
            if (v4 && settings.ipv4Dns.isNotBlank()) runCatching { builder.addDnsServer(settings.ipv4Dns.trim()) }
            if (settings.ipv6 && settings.ipv6Dns.isNotBlank()) runCatching { builder.addDnsServer(settings.ipv6Dns.trim()) }
        }

        if (settings.globalMode) {
            // Keep our own traffic (the connection to the proxy) outside the tunnel.
            runCatching { builder.addDisallowedApplication(packageName) }
            // Orbot's own Tor traffic must not loop back into the tunnel.
            if (settings.usesTor) runCatching { builder.addDisallowedApplication(ORBOT_PACKAGE) }
            settings.bypassPackages.filter { it != packageName }.forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
            }
        } else {
            var added = false
            settings.perAppPackages.filter { it != packageName }.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }.onSuccess { added = true }
            }
            // An empty allow-list would tunnel everything, which is not what the user asked for.
            if (!added) runCatching { builder.addAllowedApplication(packageName + ".none") }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        builder.setBlocking(false)
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        return builder.establish()
    }

    private fun startStatsPolling() {
        statsJob = scope.launch {
            var ticks = 0L
            while (isActive) {
                val raw = runCatching { t2s.T2s.stats() }.getOrNull()
                if (raw != null) {
                    runCatching {
                        val o = JSONObject(raw)
                        VpnController.setTraffic(
                            Traffic(
                                uploadBytes = o.optLong("uploadBytes"),
                                downloadBytes = o.optLong("downloadBytes"),
                                uploadPackets = o.optLong("uploadPackets"),
                                downloadPackets = o.optLong("downloadPackets"),
                                connections = o.optLong("connections"),
                                dnsQueries = o.optLong("dnsQueries"),
                                dnsBlocked = o.optLong("dnsBlocked"),
                                dnsCached = o.optLong("dnsCached"),
                                dnsFailed = o.optLong("dnsFailed"),
                                blocklistSize = o.optLong("blocklistSize"),
                                dnsCloaked = o.optLong("dnsCloaked"),
                                lanBlocked = o.optLong("lanBlocked"),
                            )
                        )
                    }
                }
                ++ticks
                drainTrackerHits()
                drainActivity()
                if (ticks % 60 == 0L) TrackerStore.persist(this@Tun2SocksVpnService, activeSettings.trackerLogDays, activeSettings.noLogsMode)
                delay(500)
            }
        }
    }

    /** Probes the active proxy; after two consecutive failures switches to the fastest healthy profile. */
    private fun startHealthChecks() {
        healthJob = scope.launch {
            var failures = 0
            delay(3000)
            while (isActive && engineRunning) {
                val s = SettingsRepository.get(this@Tun2SocksVpnService).settings.value.effective()
                val current = s.profiles.firstOrNull { it.id == activeProfileId }
                // Tor circuits are slow to build; never fail over away from Tor, just report it.
                val tor = current?.isTor == true
                val ms = current?.let { runCatching { t2s.T2s.latency(it.url, if (tor) 20 else 6) }.getOrNull() }
                if (ms != null) {
                    failures = 0
                    VpnController.setLatency(ms)
                } else if (tor) {
                    if (++failures >= 2) {
                        VpnController.setLatency(null)
                        VpnController.setEvent("Tor not responding. Open Orbot and make sure it is started (not in VPN mode).")
                        failures = 0
                    }
                } else if (++failures >= 2) {
                    VpnController.setLatency(null)
                    val best = s.profiles
                        .filter { it.isValid && it.id != activeProfileId }
                        .mapNotNull { p -> runCatching { p to t2s.T2s.latency(p.url, 5) }.getOrNull() }
                        .minByOrNull { it.second }
                    if (best != null && engineRunning) {
                        switchTo(best.first, best.second)
                        failures = 0
                    } else {
                        VpnController.setEvent("Proxy unreachable, no healthy backup found")
                    }
                }
                delay(s.healthCheckSec.coerceIn(10, 600) * 1000L)
            }
        }
    }

    private fun switchTo(profile: ProxyProfile, latency: Long) {
        runCatching { t2s.T2s.setProxies(profile.url) }.onSuccess {
            activeProfileId = profile.id
            VpnController.setRoute(profile.label)
            VpnController.setLatency(latency)
            VpnController.setEvent("Auto-failover → ${profile.label} (${latency} ms)")
            startForegroundNotification(connectedText(profile.label))
        }.onFailure { log("failover failed: ${it.message}") }
    }

    /** On Wi-Fi ↔ mobile switches, drop stale sockets and DNS cache so traffic recovers instantly. */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var last: Network? = null
            override fun onAvailable(network: Network) {
                val previous = last
                last = network
                if (previous != null && previous != network && engineRunning) {
                    scope.launch { runCatching { t2s.T2s.resetConnections() } }
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
    }

    private fun startForegroundNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, Tun2SocksVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setVisibility(if (activeSettings.stealthNotification) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_disconnect), stopIntent).build())
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** Engine start/stop can block on native teardown; keep it off the main thread and strictly ordered. */
        private val ENGINE = java.util.concurrent.Executors.newSingleThreadExecutor()
        /** Native start runs here so a wedged engine cannot block the ENGINE queue forever. */
        private val NATIVE = java.util.concurrent.Executors.newCachedThreadPool()
        private const val START_TIMEOUT_SEC = 25L
        const val ACTION_RESTART = "com.arafat.tun2socks.RESTART"
        private const val ALERT_CHANNEL_ID = "privacy_alerts"
        private const val ALERT_LEAK_ID = 2
        private const val ALERT_TZ_ID = 3
        const val ACTION_START = "com.arafat.tun2socks.START"
        const val ACTION_STOP = "com.arafat.tun2socks.STOP"
        private const val TAG = "Tun2SocksVpn"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1
        private const val IPV4_ADDRESS = "198.18.0.1"
        private const val IPV6_ADDRESS = "fc00::1"
        private const val DNS_VIP4 = "198.18.0.2"
        private const val DNS_VIP6 = "fc00::2"
    }
}
