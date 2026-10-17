package com.arafat.tun2socks.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.arafat.tun2socks.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VpnStatus { Disconnected, Connecting, Connected, Error }

data class Traffic(
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val uploadPackets: Long = 0,
    val downloadPackets: Long = 0,
    val connections: Long = 0,
    val dnsQueries: Long = 0,
    val dnsBlocked: Long = 0,
    val dnsCached: Long = 0,
    val dnsFailed: Long = 0,
    val blocklistSize: Long = 0,
    val dnsCloaked: Long = 0,
    val lanBlocked: Long = 0,
)

/** Process-wide VPN state shared between the service, tile and UI. */
object VpnController {
    private val _status = MutableStateFlow(VpnStatus.Disconnected)
    val status: StateFlow<VpnStatus> = _status

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** Human-readable active route (profile or chain), updated on failover. */
    private val _route = MutableStateFlow("")
    val route: StateFlow<String> = _route

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs

    private val _event = MutableStateFlow<String?>(null)
    val event: StateFlow<String?> = _event

    /** Tunnel-relevant settings the running session was started with (null when not running). */
    private val _applied = MutableStateFlow<Settings?>(null)
    val applied: StateFlow<Settings?> = _applied

    internal fun setApplied(settings: Settings?) { _applied.value = settings }
    internal fun setStatus(status: VpnStatus) { _status.value = status }
    internal fun setTraffic(traffic: Traffic) { _traffic.value = traffic }
    internal fun setError(message: String?) { _lastError.value = message }
    internal fun setRoute(route: String) { _route.value = route }
    internal fun setLatency(ms: Long?) { _latencyMs.value = ms }
    internal fun setEvent(message: String?) { _event.value = message }

    fun start(context: Context) {
        val intent = Intent(context, Tun2SocksVpnService::class.java)
            .setAction(Tun2SocksVpnService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    /** Stops and starts the tunnel again so changed settings take effect. */
    fun restart(context: Context) {
        val intent = Intent(context, Tun2SocksVpnService::class.java)
            .setAction(Tun2SocksVpnService.ACTION_RESTART)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        if (_status.value == VpnStatus.Disconnected) return
        val intent = Intent(context, Tun2SocksVpnService::class.java)
            .setAction(Tun2SocksVpnService.ACTION_STOP)
        context.startService(intent)
    }
}
