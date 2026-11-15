package com.raj.zcoderemote.data

import android.content.Context
import android.content.SharedPreferences

/**
 * User settings for the monitor service and diagnostics. Paired devices
 * live in [DeviceStore]; this store only holds non-secret preferences.
 */
class ConnectionStore(context: Context) {

    private val settingsPrefs: SharedPreferences =
        context.getSharedPreferences("zcode_settings", Context.MODE_PRIVATE)

    /** Master switch for the approval/notification monitor service. */
    var monitorEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_MONITOR_ENABLED, true)
        set(value) = settingsPrefs.edit().putBoolean(KEY_MONITOR_ENABLED, value).apply()

    /** Also raise a notification when a task finishes. */
    var notifyOnCompleted: Boolean
        get() = settingsPrefs.getBoolean(KEY_NOTIFY_COMPLETED, true)
        set(value) = settingsPrefs.edit().putBoolean(KEY_NOTIFY_COMPLETED, value).apply()

    /** Restart the monitor after reboot. */
    var startOnBoot: Boolean
        get() = settingsPrefs.getBoolean(KEY_START_ON_BOOT, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    /** Last event received from the WebView bridge (for the diagnostics row). */
    var lastEvent: String?
        get() = settingsPrefs.getString(KEY_LAST_EVENT, null)
        set(value) = settingsPrefs.edit().putString(KEY_LAST_EVENT, value).apply()

    companion object {
        private const val KEY_MONITOR_ENABLED = "monitorEnabled"
        private const val KEY_NOTIFY_COMPLETED = "notifyOnCompleted"
        private const val KEY_START_ON_BOOT = "startOnBoot"
        private const val KEY_LAST_EVENT = "lastEvent"
    }
}
