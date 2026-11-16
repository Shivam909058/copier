package dev.copier

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * Lets Copier switch its own accessibility service back on after a payment app closes, and when
 * Copier starts again after the phone stopped it (which silently disables the service).
 *
 * Android has no on-phone way to allow this. It needs WRITE_SECURE_SETTINGS and usage access,
 * granted once from the Mac over adb (Copier → Advanced → Self-Managing Automatic Copy, or
 * `copier usb-setup`). Without them nothing here runs and the user turns the service on by hand.
 */
class SelfManage(private val context: Context, private val store: Store) {
    private val watcher = ComponentName(context, CopyWatcher::class.java)
    private var foreground: String? = null
    private var eventsReadUntil = 0L
    private var polls = 0

    /** Both grants from the Mac's one-time USB setup are in place. */
    val available: Boolean
        get() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(AppOpsManager::class.java)
                .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    /** Automatic copy just stepped aside for [pkg]; watch for the user leaving it. */
    fun onPaused(pkg: String) {
        foreground = pkg
        eventsReadUntil = System.currentTimeMillis()
        polls = 0
    }

    /** Called every couple of seconds from SyncService's scheduler thread. */
    fun tick() {
        if (!available || !store.autoCopyWanted || CopyWatcher.running) {
            polls = 0
            return
        }
        if (store.pausedFor != null) {
            updateForeground()
            if (!ResumeRule.mayResume(foreground, store.pauseApps)) {
                polls = 0
                return
            }
        }
        polls++
        val listed = enabledServices().any(::isWatcher)
        when {
            // Off Android's list (we stepped aside, or the app was force-stopped): put it back once
            // the user has been out of the payment app for a moment, not mid app-switch.
            !listed && polls >= CALM_POLLS -> {
                setEnabled(true)
                polls = 0
                Log.i(TAG, "turned automatic copy back on")
            }
            // Listed but still not running: Android dropped the binding. Taking it off the list
            // makes the branch above bind it afresh on the next tick.
            listed && polls >= STUCK_POLLS -> {
                setEnabled(false)
                polls = CALM_POLLS - 1
            }
        }
    }

    /** The switch in the app: on right away. */
    fun enable() = setEnabled(true)

    private fun enabledServices(): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }

    private fun isWatcher(entry: String) = ComponentName.unflattenFromString(entry) == watcher

    private fun setEnabled(on: Boolean) {
        val others = enabledServices().filterNot(::isWatcher)
        val services = if (on) others + watcher.flattenToString() else others
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
        if (on) Settings.Secure.putString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
    }

    private fun updateForeground() {
        val now = System.currentTimeMillis()
        // After a process restart we don't know where the user is: look back a while.
        val from = if (eventsReadUntil == 0L) now - LOOK_BACK_MS else eventsReadUntil
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foreground = event.packageName
        }
        eventsReadUntil = now
    }

    private companion object {
        const val CALM_POLLS = 2 // × SyncService's 2 s tick
        const val STUCK_POLLS = 5
        const val LOOK_BACK_MS = 10 * 60_000L
    }
}

/** When automatic copy may come back after stepping aside for a payment app. */
object ResumeRule {
    /** [foreground] is the app in front as far as we know; null (unknown) counts as "left". */
    fun mayResume(foreground: String?, pauseApps: Set<String>): Boolean = foreground == null || foreground !in pauseApps
}
