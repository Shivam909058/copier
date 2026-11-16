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
import java.util.concurrent.atomic.AtomicInteger

object Notifications {
    const val ID_STATUS = 1
    private const val ID_RING = 2
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

    fun cancelRinging(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_RING)

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
