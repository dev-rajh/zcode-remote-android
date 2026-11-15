package com.raj.zcoderemote.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.raj.zcoderemote.data.ConnectionStore
import com.raj.zcoderemote.data.DeviceStore

/** Restarts the monitor after reboot when the user opted in. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (ConnectionStore(context).startOnBoot && DeviceStore(context).devices().isNotEmpty()) {
            MonitorService.start(context)
        }
    }
}
