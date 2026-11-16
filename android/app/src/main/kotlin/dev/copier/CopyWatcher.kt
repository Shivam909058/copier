package dev.copier

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Automatic phone → Mac clipboard. Android 10+ hides the clipboard from background apps, so this
 * watches for taps on "Copy" controls (text-selection toolbar, WhatsApp's copy icon, "Copy image",
 * "Copy link"…), then reads the clipboard while briefly holding focus.
 *
 * `canRetrieveWindowContent` is off: the only thing it ever sees is the tapped control's own label.
 * Installing an APK with an Accessibility service from a browser needs Play Protect off in some
 * countries (Google's "enhanced fraud protection").
 */
class CopyWatcher : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastTrigger = 0L
    private var probe: View? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED || event.packageName == packageName) return
        val label = event.contentDescription?.takeIf { it.isNotBlank() }?.toString() ?: event.text.joinToString(" ")
        if (!isCopyLabel(label, getString(android.R.string.copy))) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < DEBOUNCE_MS) return
        lastTrigger = now
        // Apps write the clipboard inside the click handler, before this event; the delay covers
        // the few that copy asynchronously.
        handler.postDelayed(::readWithFocus, READ_DELAY_MS)
    }

    override fun onInterrupt() = Unit

    /**
     * Only the focused app may read the clipboard. A 1-px accessibility overlay takes focus without
     * an activity transition and without the background-pop-up blocks some OEM ROMs put on
     * activity starts. If focus never arrives, fall back to the invisible FocusActivity.
     */
    private fun readWithFocus() {
        if (probe != null) return
        val view = object : View(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus && probe === this) {
                    sendClipboard()
                    removeProbe()
                }
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE) but never touchable; ALT_FOCUSABLE_IM keeps the
            // keyboard attached to the app the user was typing in.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSPARENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            probe = view
        } catch (e: RuntimeException) {
            Log.w(TAG, "focus overlay unavailable; using FocusActivity", e)
            startActivity(FocusActivity.intent(this, manual = false))
            return
        }
        handler.postDelayed({
            if (probe === view) {
                Log.i(TAG, "focus overlay got no focus; using FocusActivity")
                removeProbe()
                startActivity(FocusActivity.intent(this, manual = false))
            }
        }, FOCUS_TIMEOUT_MS)
    }

    private fun sendClipboard() {
        val service = SyncService.instance ?: return
        if (!service.isConnected) return
        val clip = ClipboardBridge(this).read() ?: return
        service.sendLocalClip(this, clip)
        Log.i(TAG, "auto-sent a copy")
    }

    private fun removeProbe() {
        val view = probe ?: return
        probe = null
        try {
            getSystemService(WindowManager::class.java).removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "focus overlay was already gone", e)
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 700L
        const val READ_DELAY_MS = 250L
        const val FOCUS_TIMEOUT_MS = 500L
    }
}

/**
 * "Copy", "Copy image", "Copy link" or the system's localized Copy — but not "Copyright" or a long
 * sentence that merely starts with "Copy". A false positive only costs one redundant read.
 */
fun isCopyLabel(label: String, localizedCopy: String): Boolean {
    val normalized = label.trim().lowercase()
    if (normalized.isEmpty() || normalized.length > 24) return false
    return normalized == localizedCopy.trim().lowercase() || normalized == "copy" || normalized.startsWith("copy ")
}

fun copyWatcherEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val me = ComponentName(context, CopyWatcher::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}
