package dev.copier

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
 * accessibility service (1) notices that something was probably copied — see [CopyDetector] — and
 * (2) reads the clipboard while briefly holding focus with an invisible 1-px overlay, falling back
 * to the invisible [FocusActivity] where an OEM refuses the overlay.
 *
 * `canRetrieveWindowContent` is off: it sees event labels (a tapped button's name, a toast's text),
 * never the screen. It also steps aside for payment apps, which refuse to run while any sideloaded
 * accessibility service is on: see [pauseFor].
 */
class CopyWatcher : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var bridge: ClipboardBridge
    private lateinit var detector: CopyDetector
    private var probe: View? = null
    private var awaitingActivity: CopyDetector.Pending? = null
    private var overlayMisses = 0
    private var lastProblemReportAt = 0L

    override fun onServiceConnected() {
        store = Store.get(this)
        bridge = ClipboardBridge(this)
        detector = CopyDetector(packageName, listOf(getString(android.R.string.copy)), systemCopiedPhrases())
        detector.thorough = store.thoroughCopy
        instance = this
        store.autoCopyWanted = true
        store.pausedFor = null
        Notifications.cancelPaused(this)
        applyEventTypes()
        if (store.pairedMac != null && SyncService.instance == null) {
            try {
                startForegroundService(Intent(this, SyncService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "could not start the sync service from the copy watcher", e)
            }
        }
        SyncService.instance?.watcherChanged()
        Log.i(TAG, "copy watcher connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopped()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopped()
        super.onDestroy()
    }

    private fun stopped() {
        if (instance !== this) return
        instance = null
        removeProbe()
        handler.removeCallbacksAndMessages(null)
        SyncService.instance?.watcherChanged()
        Log.i(TAG, "copy watcher stopped")
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> CopyDetector.Kind.CLICK
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> CopyDetector.Kind.ANNOUNCEMENT
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                // Toasts and real notifications share this type; only toasts say "Copied".
                if (event.className?.contains("Toast") == true) CopyDetector.Kind.TOAST else null
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> CopyDetector.Kind.WINDOW
            else -> null // other types are only subscribed to while diagnosing
        }
        if (kind == CopyDetector.Kind.WINDOW && pkg != packageName && pkg in store.pauseApps) {
            pauseFor(pkg)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val label = if (kind == null || kind == CopyDetector.Kind.WINDOW) "" else labelOf(event)
        val decision = if (kind == null) {
            CopyDetector.Decision.Ignore("not a copy signal")
        } else {
            detector.onSignal(CopyDetector.Signal(kind, pkg, label, now))
        }
        if (diagnosing()) {
            val shown = if (label.isEmpty()) "" else if (label.length <= CopyDetector.MAX_LABEL) " “$label”" else " (${label.length} chars)"
            report("saw ${AccessibilityEvent.eventTypeToString(event.eventType)} in $pkg$shown → ${describe(decision)}")
        }
        if (decision is CopyDetector.Decision.Check) handler.postDelayed(::startCheck, decision.delayMs)
    }

    private fun labelOf(event: AccessibilityEvent): String =
        event.contentDescription?.takeIf { it.isNotBlank() }?.toString() ?: event.text.joinToString(" ")

    private fun describe(decision: CopyDetector.Decision) = when (decision) {
        is CopyDetector.Decision.Check -> "check the clipboard in ${decision.delayMs} ms"
        is CopyDetector.Decision.Ignore -> "ignored (${decision.why})"
    }

    // region Reading the clipboard

    private fun startCheck() {
        val pending = detector.takePending() ?: return
        when {
            // A read already in flight will see whatever was just copied.
            probe != null || awaitingActivity != null -> finish(pending, "joined the check in progress", sent = false)
            getSystemService(KeyguardManager::class.java).isKeyguardLocked -> finish(pending, "skipped: phone is locked", sent = false)
            store.overlayBlocked -> if (pending.explicit) readViaActivity(pending) else finish(pending, "skipped: overlay reads don't work on this phone", sent = false)
            else -> readViaOverlay(pending)
        }
    }

    /**
     * Only the focused app may read the clipboard. A 1-px accessibility overlay takes focus without
     * an activity transition; ALT_FOCUSABLE_IM leaves the keyboard attached to the app underneath.
     */
    private fun readViaOverlay(pending: CopyDetector.Pending) {
        val view = object : View(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus || probe !== this) return
                val read = bridge.readDetailed()
                removeProbe()
                if (read is ClipboardBridge.Read.Unreadable && pending.explicit) {
                    // A definite copy but nothing readable: this OEM may refuse overlay focus reads.
                    readViaActivity(pending)
                } else {
                    deliver(pending, "overlay", read)
                }
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSPARENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            probe = view
        } catch (e: RuntimeException) {
            Log.w(TAG, "focus overlay unavailable", e)
            if (pending.explicit) readViaActivity(pending) else finish(pending, "overlay unavailable", sent = false)
            return
        }
        handler.postDelayed({
            if (probe === view) {
                removeProbe()
                if (pending.explicit) readViaActivity(pending) else finish(pending, "overlay got no focus", sent = false)
            }
        }, FOCUS_TIMEOUT_MS)
    }

    private fun readViaActivity(pending: CopyDetector.Pending) {
        awaitingActivity = pending
        try {
            startActivity(FocusActivity.intent(this, manual = false))
        } catch (e: RuntimeException) {
            awaitingActivity = null
            finish(pending, "could not open the reader window (${e.javaClass.simpleName})", sent = false, problem = true)
            return
        }
        handler.postDelayed({
            if (awaitingActivity === pending) {
                awaitingActivity = null
                finish(pending, "the reader window never got focus", sent = false, problem = true)
            }
        }, ACTIVITY_TIMEOUT_MS)
    }

    /** FocusActivity hands its read back here when we started it. Returns false if we didn't. */
    fun onActivityRead(read: ClipboardBridge.Read): Boolean {
        val pending = awaitingActivity ?: return false
        awaitingActivity = null
        if (read !is ClipboardBridge.Read.Unreadable && !store.overlayBlocked && ++overlayMisses >= 2) {
            // Twice the overlay saw nothing where the activity could read: stop trying the overlay.
            store.overlayBlocked = true
            report("this phone refuses clipboard reads from the overlay; using the reader window from now on")
        }
        deliver(pending, "reader window", read)
        return true
    }

    private fun deliver(pending: CopyDetector.Pending, via: String, read: ClipboardBridge.Read) {
        val outcome = AutoCopy.offer(this, read)
        finish(
            pending, "$via: ${outcome.text}", sent = outcome == AutoCopy.Outcome.SENT,
            problem = pending.explicit && outcome in setOf(AutoCopy.Outcome.UNREADABLE, AutoCopy.Outcome.NO_SERVICE, AutoCopy.Outcome.NOT_CONNECTED),
        )
    }

    private fun finish(pending: CopyDetector.Pending, result: String, sent: Boolean, problem: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        detector.onCheckFinished(now, sent)
        val line = "copy check (${pending.trigger}) → $result"
        Log.i(TAG, line)
        if (diagnosing()) {
            report(line)
        } else if (problem && now - lastProblemReportAt > PROBLEM_REPORT_GAP_MS) {
            lastProblemReportAt = now
            report(line)
        }
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

    // endregion

    /**
     * Payment and banking apps refuse to run while a sideloaded accessibility service is on (Play
     * Integrity reports it as an app that could control the screen). Nothing can hide that, so we
     * turn ourselves off the moment one opens. Android won't let an app turn its own accessibility
     * service back on, so a notification offers the way back.
     */
    private fun pauseFor(pkg: String) {
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
        Log.i(TAG, "pausing automatic copy for a payment app")
        store.pausedFor = label
        report("automatic copy paused for $label")
        Notifications.paused(this, label)
        disableSelf() // → onUnbind → stopped() tells the Mac
    }

    private fun report(text: String) {
        SyncService.instance?.diag(text)
    }

    /** Normal operation needs four event types; diagnosing listens more widely to find new signals. */
    private fun applyEventTypes() {
        val info = serviceInfo ?: return
        info.eventTypes = if (diagnosing()) DIAGNOSE_EVENT_TYPES else NORMAL_EVENT_TYPES
        serviceInfo = info
    }

    /** The exact phrases the system UI announces after a copy, in the phone's language. */
    private fun systemCopiedPhrases(): List<String> =
        try {
            val systemUi = packageManager.getResourcesForApplication(SYSTEM_UI)
            listOf("clipboard_text_copied", "clipboard_image_copied", "clipboard_content_copied").mapNotNull { name ->
                val id = systemUi.getIdentifier(name, "string", SYSTEM_UI)
                if (id != 0) systemUi.getString(id) else null
            }
        } catch (e: PackageManager.NameNotFoundException) {
            emptyList()
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not read the system's copied phrases", e)
            emptyList()
        }

    companion object {
        private const val FOCUS_TIMEOUT_MS = 500L
        private const val ACTIVITY_TIMEOUT_MS = 1_500L
        private const val PROBLEM_REPORT_GAP_MS = 10_000L
        private const val SYSTEM_UI = "com.android.systemui"
        private const val NORMAL_EVENT_TYPES = AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_ANNOUNCEMENT or
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        private const val DIAGNOSE_EVENT_TYPES = NORMAL_EVENT_TYPES or
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            AccessibilityEvent.TYPE_VIEW_SELECTED or
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED

        @Volatile
        var instance: CopyWatcher? = null
            private set

        @Volatile private var diagnoseUntil = 0L

        /** Whether the service is actually bound — Settings can say "On" for a service Android has stopped. */
        val running: Boolean get() = instance != null

        fun diagnosing(): Boolean = SystemClock.elapsedRealtime() < diagnoseUntil

        /** The Mac's `copier diagnose` asks for a play-by-play for a while. */
        fun diagnose(seconds: Long) {
            diagnoseUntil = SystemClock.elapsedRealtime() + seconds.coerceIn(10, 900) * 1000
            val watcher = instance ?: return
            watcher.handler.post {
                watcher.applyEventTypes()
                watcher.handler.postDelayed({ watcher.applyEventTypes() }, seconds.coerceIn(10, 900) * 1000 + 500)
            }
        }

        fun setThorough(enabled: Boolean) {
            instance?.detector?.thorough = enabled
        }

        /** What Settings says, which can be "on" while the service isn't running. */
        fun enabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(context, CopyWatcher::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/**
 * The automatic path's memory: which clip was last handled, so several signals for one copy send it
 * once, and an old clipboard is never sent just because the app restarted.
 */
object AutoCopy {
    enum class Outcome(val text: String) {
        SENT("sent"),
        ALREADY_SENT("already sent"),
        OLD("an older clip, not sent"),
        FROM_MAC("it came from the Mac"),
        UNREADABLE("nothing readable on the clipboard"),
        NO_SERVICE("Copier's connection service isn't running"),
        NOT_CONNECTED("the Mac isn't connected"),
    }

    private const val FRESH_MS = 60_000L
    @Volatile private var lastCopiedAt = 0L
    @Volatile private var lastHash = 0

    fun offer(context: Context, read: ClipboardBridge.Read): Outcome = when (read) {
        ClipboardBridge.Read.Unreadable -> Outcome.UNREADABLE
        ClipboardBridge.Read.FromMac -> Outcome.FROM_MAC
        is ClipboardBridge.Read.Clip -> {
            val hash = read.clip.fingerprint()
            val same = if (read.copiedAt != 0L) read.copiedAt == lastCopiedAt else hash == lastHash
            val service = SyncService.instance
            when {
                same -> Outcome.ALREADY_SENT
                read.copiedAt != 0L && System.currentTimeMillis() - read.copiedAt > FRESH_MS -> {
                    remember(read.copiedAt, hash)
                    Outcome.OLD
                }
                service == null -> Outcome.NO_SERVICE
                else -> {
                    remember(read.copiedAt, hash)
                    val connected = service.isConnected
                    service.sendLocalClip(context, read.clip) // records a failed item itself if the Mac is away
                    if (connected) Outcome.SENT else Outcome.NOT_CONNECTED
                }
            }
        }
    }

    /** A manual send (tile, notification) counts as handled too. */
    fun remember(read: ClipboardBridge.Read.Clip) = remember(read.copiedAt, read.clip.fingerprint())

    private fun remember(copiedAt: Long, hash: Int) {
        lastCopiedAt = copiedAt
        lastHash = hash
    }

    private fun ClipboardBridge.LocalClip.fingerprint(): Int = when (this) {
        is ClipboardBridge.LocalClip.Text -> text.hashCode()
        is ClipboardBridge.LocalClip.Content -> uri.hashCode()
    }
}
