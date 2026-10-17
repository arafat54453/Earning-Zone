package com.arafat.tun2socks.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.arafat.tun2socks.data.SettingsRepository

/** Reconnects after reboot when enabled and VPN consent is still granted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = SettingsRepository.get(context).settings.value
        if (settings.autoConnectOnBoot && VpnService.prepare(context) == null) {
            runCatching { VpnController.start(context) }
        }
    }
}
