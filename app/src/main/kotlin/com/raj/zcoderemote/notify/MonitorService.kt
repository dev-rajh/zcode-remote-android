package com.raj.zcoderemote.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.raj.zcoderemote.data.ConnectionStore
import com.raj.zcoderemote.data.DeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Foreground service that keeps the process alive in the background so the
 * hosted ZCode web app keeps its WebSocket connection (and keeps calling the
 * notification shim). Converts bridged web notifications into Android
 * notifications, deduplicating repeats within a short window (the web app can
 * replay notifications after reconnects).
 */
class MonitorService : Service() {

    private val json = Json { ignoreUnknownKeys = true }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recentNotifications = mutableMapOf<Int, Long>()
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val store = ConnectionStore(this)
        if (!store.monitorEnabled || DeviceStore(this).devices().isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(Notifier.FOREGROUND_NOTIFICATION_ID, Notifier.statusNotification(this))
        if (collectJob == null) {
            collectJob = serviceScope.launch {
                ApprovalMonitor.events.collect { handle(it) }
            }
        }
        return START_STICKY
    }

    private suspend fun handle(raw: String) {
        val payload = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val title = payload["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val body = payload["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (title.isBlank() && body.isBlank()) return

        ConnectionStore(this).lastEvent = raw

        val kind = Notifier.classify(title, body)
        if (kind == Notifier.Kind.COMPLETED && !ConnectionStore(this).notifyOnCompleted) return

        val key = (kind.ordinal * 31 + (title + body).hashCode()) and 0x7fffffff
        val now = System.currentTimeMillis()
        synchronized(recentNotifications) {
            recentNotifications.entries.removeAll { it.value < now - DEDUPE_WINDOW_MS }
            val last = recentNotifications[key]
            if (last != null && now - last < DEDUPE_WINDOW_MS) return
            recentNotifications[key] = now
        }
        Notifier.show(this, title, body, kind)
    }

    override fun onDestroy() {
        collectJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val DEDUPE_WINDOW_MS = 60_000L
        const val ACTION_STOP = "com.raj.zcoderemote.action.STOP_MONITOR"

        fun start(context: Context) {
            if (!ConnectionStore(context).monitorEnabled || DeviceStore(context).devices().isEmpty()) return
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
