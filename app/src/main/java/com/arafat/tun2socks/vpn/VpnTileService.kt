package com.arafat.tun2socks.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.arafat.tun2socks.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings tile to connect/disconnect without opening the app. */
class VpnTileService : TileService() {
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job = CoroutineScope(Dispatchers.Main).launch {
            VpnController.status.collect { render(it) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        when (VpnController.status.value) {
            VpnStatus.Connected, VpnStatus.Connecting -> VpnController.stop(this)
            else -> if (VpnService.prepare(this) == null) {
                VpnController.start(this)
            } else {
                val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
                } else {
                    @Suppress("DEPRECATION") startActivityAndCollapse(intent)
                }
            }
        }
    }

    private fun render(status: VpnStatus) {
        val tile = qsTile ?: return
        tile.state = when (status) {
            VpnStatus.Connected, VpnStatus.Connecting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (status) {
                VpnStatus.Connected -> "Protected"
                VpnStatus.Connecting -> "Connecting…"
                VpnStatus.Error -> "Error"
                VpnStatus.Disconnected -> "Off"
            }
        }
        tile.updateTile()
    }
}
