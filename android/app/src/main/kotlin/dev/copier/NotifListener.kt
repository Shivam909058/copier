package dev.copier

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.copier.proto.Header

/**
 * Mirrors this phone's notifications to the Mac (PROTOCOL.md § Control messages, `notif…`) while
 * connected. Contents only travel over the encrypted link and are never logged. Android 15+ redacts
 * OTPs and other sensitive text for third-party listeners; we mirror whatever we're given.
 */
class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val service = SyncService.instance?.takeIf { it.isConnected } ?: return
        toHeader(sbn)?.let(service::sendToMac)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val service = SyncService.instance?.takeIf { it.isConnected } ?: return
        if (mirrorable(sbn)) service.sendToMac(Header("notif-removed", key = sbn.key))
    }

    /** `notif-action`: fires the action, filling its RemoteInput with [text] for a reply. */
    fun perform(key: String, index: Int, text: String?) {
        val sbn = getActiveNotifications(arrayOf(key))?.firstOrNull() ?: return
        val action = sbn.notification.actions?.getOrNull(index) ?: return
        val inputs = action.remoteInputs
        val fill = if (text != null && !inputs.isNullOrEmpty()) {
            Intent().also { intent ->
                RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
            }
        } else {
            null
        }
        try {
            action.actionIntent.send(this, 0, fill, null, null, null, backgroundStartOptions())
        } catch (e: PendingIntent.CanceledException) {
            Log.i(TAG, "notification action is no longer available")
        }
    }

    fun dismiss(key: String) = cancelNotification(key)

    /** Some actions open an activity; we're allowed to start one from the background, so say so (34+). */
    private fun backgroundStartOptions(): Bundle? =
        if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }

    private fun mirrorable(sbn: StatusBarNotification): Boolean =
        !sbn.isOngoing && sbn.packageName != packageName &&
            (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0

    private fun toHeader(sbn: StatusBarNotification): Header? {
        if (!mirrorable(sbn)) return null
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return null
        val actions = notification.actions.orEmpty()
        val reply = actions.indexOfFirst { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true }
        return Header(
            "notif",
            key = sbn.key,
            pkg = sbn.packageName,
            appName = appLabel(sbn.packageName),
            title = title,
            text = text,
            time = sbn.postTime,
            actions = actions.map { it.title?.toString().orEmpty() },
            replyAction = reply.takeIf { it >= 0 },
            call = notification.category == Notification.CATEGORY_CALL,
        )
    }

    private fun appLabel(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }

    companion object {
        @Volatile
        var instance: NotifListener? = null
            private set

        fun enabled(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, NotifListener::class.java))
    }
}
