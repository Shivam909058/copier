package dev.copier

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import java.util.concurrent.atomic.AtomicInteger

object Notifications {
    const val ID_STATUS = 1
    private const val ID_RING = 2
    private const val ID_PAUSED = 3
    private const val CHANNEL_STATUS = "status"
    private const val CHANNEL_TRANSFERS = "transfers"
    private const val CHANNEL_RING = "ring"
    private val nextId = AtomicInteger(100)

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_TRANSFERS, "Transfers", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_RING, "Ring from Mac", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    /** The foreground-service notification: connection state, the latest event, and "Send clipboard". */
    fun status(context: Context, title: String, detail: String?): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val send = PendingIntent.getActivity(context, 1, FocusActivity.intent(context, manual = true), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .apply { if (detail != null) setContentText(detail) }
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat), "Send clipboard", send).build())
            .build()
    }

    fun updateStatus(context: Context, title: String, detail: String?) {
        if (!allowed(context)) return
        context.getSystemService(NotificationManager::class.java).notify(ID_STATUS, status(context, title, detail))
    }

    fun post(context: Context, title: String, text: String, tap: PendingIntent? = null) {
        if (!allowed(context)) return
        val notification = Notification.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .apply { if (tap != null) setContentIntent(tap) }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(nextId.incrementAndGet(), notification)
    }

    fun ringing(context: Context) {
        if (!allowed(context)) return
        val stop = PendingIntent.getService(
            context, 2, Intent(context, SyncService::class.java).setAction(SyncService.ACTION_STOP_RING), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Your Mac is ringing this phone")
            .setContentText("Tap to stop")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(stop)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat), "Stop", stop).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_RING, notification)
    }

    /** Automatic copy stepped aside for a payment app; tapping leads to where it's turned back on. */
    fun paused(context: Context, appName: String, comesBackByItself: Boolean) {
        if (!allowed(context)) return
        val settings = PendingIntent.getActivity(
            context, 3, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val after = if (comesBackByItself) "It turns back on by itself when you leave $appName." else "Tap here afterwards to turn automatic copy back on."
        val detail = "$appName won't open while Copier's accessibility is on, so Copier turned it off. " +
            "If $appName still complains, close it and open it again. $after"
        val notification = Notification.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Automatic copy paused for $appName")
            .setContentText(after)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(settings)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_PAUSED, notification)
    }

    fun cancelPaused(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_PAUSED)

    fun cancelRinging(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_RING)

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
