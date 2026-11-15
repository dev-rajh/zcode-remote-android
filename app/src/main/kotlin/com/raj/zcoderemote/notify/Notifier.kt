package com.raj.zcoderemote.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.raj.zcoderemote.web.ChatActivity

/** Builds channels and posts the approval/input/completion notifications. */
object Notifier {
    const val CHANNEL_NEEDS_ATTENTION = "needs_attention_v2"
    const val CHANNEL_UPDATES = "updates_v2"
    const val CHANNEL_CONNECTION = "connection_v2"
    private val LEGACY_CHANNEL_IDS = listOf("needs_attention", "updates", "connection")
    const val FOREGROUND_NOTIFICATION_ID = 1

    /** What kind of attention the ZCode web app is asking for. */
    enum class Kind { APPROVAL, INPUT, COMPLETED, ERROR }

    private val APPROVAL_WORDS =
        Regex("permission|approv|allow|consent|trust|run command|execute", RegexOption.IGNORE_CASE)
    private val INPUT_WORDS =
        Regex("input|question|waiting|elicitation|choice|answer|respond|plan", RegexOption.IGNORE_CASE)
    private val COMPLETED_WORDS =
        Regex("complete|finished|is done|success", RegexOption.IGNORE_CASE)

    /** Classify a web notification by its text; approval beats input beats error. */
    fun classify(title: String, body: String): Kind {
        val text = "$title $body"
        return when {
            APPROVAL_WORDS.containsMatchIn(text) -> Kind.APPROVAL
            INPUT_WORDS.containsMatchIn(text) -> Kind.INPUT
            COMPLETED_WORDS.containsMatchIn(text) -> Kind.COMPLETED
            else -> Kind.ERROR
        }
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Channel settings can be user-locked by OEM skins (importance downgraded,
        // sound muted) and some skins persist those locks across delete/recreate
        // of the same ID. Version-bumped IDs guarantee clean channels; the old
        // ones are deleted so they don't clutter system settings.
        for (oldId in LEGACY_CHANNEL_IDS) {
            manager.deleteNotificationChannel(oldId)
        }
        val needsAttention = NotificationChannel(
            CHANNEL_NEEDS_ATTENTION,
            "Needs your attention",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Approval and input requests from ZCode sessions"
            enableVibration(true)
            enableLights(true)
        }
        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            "Task updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Completed task notifications"
            enableVibration(true)
        }
        val connection = NotificationChannel(
            CHANNEL_CONNECTION,
            "Connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing status while ZCode Remote is monitoring"
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannels(listOf(needsAttention, updates, connection))
    }

    fun statusNotification(context: Context): Notification {
        val contentIntent = openAppIntent(context)
        return Notification.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("ZCode Remote")
            .setContentText("Watching for approval and input requests")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun show(context: Context, title: String, body: String, kind: Kind) {
        val channel = when (kind) {
            Kind.COMPLETED -> CHANNEL_UPDATES
            else -> CHANNEL_NEEDS_ATTENTION
        }
        val id = notificationId(kind, title, body)
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title.ifBlank { "ZCode needs your attention" })
            .setContentText(body.ifBlank { "Open ZCode Remote to respond." })
            .setStyle(Notification.BigTextStyle().bigText(body.ifBlank { "Open ZCode Remote to respond." }))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    fun cancelEventNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (kind in Kind.entries) {
            manager.cancel(notificationIdBase(kind))
        }
    }

    private fun notificationId(kind: Kind, title: String, body: String): Int {
        val hash = (title + body).hashCode() and 0x7fffffff
        return notificationIdBase(kind) + (hash % 1_000_000)
    }

    private fun notificationIdBase(kind: Kind) = 1000 + kind.ordinal * 1_000_000

    private const val KEY_CHANNEL_VERSION = "channelVersion"
    private const val CHANNEL_VERSION = 2

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
