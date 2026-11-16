package dev.copier

/**
 * Decides when to look at the clipboard. Android never tells a background app that something was
 * copied, so this reads the traces a copy leaves in accessibility events:
 *
 *  - a tap on a control labelled "Copy…". Android sends that event *after* the click handler and
 *    drops it if the control is gone by then, so it only arrives for toolbars that stay on screen;
 *  - Android 13+'s own "Text copied" announcement and apps' "Copied" toasts;
 *  - a window change. Menus and dialogs close inside their click handler, so their "Copy" tap never
 *    arrives, but the window change that follows does.
 *
 * Pure logic (no Android types) so it is unit-tested on the JVM. CopyWatcher feeds it events and
 * runs the checks it asks for; a check that finds nothing new sends nothing.
 */
class CopyDetector(
    private val ownPackage: String,
    copyWords: Collection<String> = emptyList(),
    copiedPhrases: Collection<String> = emptyList(),
) {
    enum class Kind { CLICK, ANNOUNCEMENT, TOAST, WINDOW }

    class Signal(val kind: Kind, val pkg: String, val label: String, val at: Long)

    /** A check waiting to run: what caused it, and whether it was a definite copy signal. */
    class Pending(val trigger: String, val explicit: Boolean)

    sealed interface Decision {
        data class Ignore(val why: String) : Decision
        data class Check(val delayMs: Long) : Decision
    }

    /** Also check after window changes (menus closing). The kill switch for the broadest signal. */
    @Volatile var thorough = true

    private val copyWords = (copyWords.map { it.trim().lowercase() } + "copy").filter { it.isNotEmpty() }.toSet()
    private val copiedPhrases = (copiedPhrases.map { it.trim().lowercase() } + GENERIC_COPIED).filter { it.isNotEmpty() }.toSet()

    private var pending: Pending? = null
    private var lastCheckEndedAt = Long.MIN_VALUE / 2
    private var lastSentAt = Long.MIN_VALUE / 2
    private var tokens = WINDOW_BURST.toDouble()
    private var tokensAt = 0L

    fun onSignal(signal: Signal): Decision {
        if (signal.pkg == ownPackage) return Decision.Ignore("our own app")
        val explicit = when (signal.kind) {
            Kind.CLICK -> isCopyLabel(signal.label)
            Kind.ANNOUNCEMENT, Kind.TOAST -> isCopiedText(signal.label)
            Kind.WINDOW -> false
        }
        if (!explicit && signal.kind != Kind.WINDOW) return Decision.Ignore("not a copy")

        val waiting = pending
        if (waiting != null) {
            // The check already on its way will see this copy; make sure it's treated as definite.
            if (explicit && !waiting.explicit) pending = Pending(signal.kind.name.lowercase(), explicit = true)
            return Decision.Ignore("a check is already scheduled")
        }
        if (explicit) {
            if (signal.at - lastSentAt < ALREADY_SENT_MS) return Decision.Ignore("this copy was already sent")
            pending = Pending(signal.kind.name.lowercase(), explicit = true)
            return Decision.Check(if (signal.kind == Kind.CLICK) CLICK_DELAY_MS else COPIED_DELAY_MS)
        }
        if (!thorough) return Decision.Ignore("window checks are off")
        // Our own check moves focus away and back, which produces a window event of its own.
        if (signal.at - lastCheckEndedAt < ECHO_MS) return Decision.Ignore("caused by our own check")
        if (!takeToken(signal.at)) return Decision.Ignore("too many window checks")
        pending = Pending("window", explicit = false)
        return Decision.Check(WINDOW_DELAY_MS)
    }

    /** The scheduled check is starting; returns what it is for (null if it was cancelled). */
    fun takePending(): Pending? = pending.also { pending = null }

    fun onCheckFinished(at: Long, sent: Boolean) {
        lastCheckEndedAt = at
        if (sent) lastSentAt = at
    }

    /** "Copy", "Copy link", "Copy image" or a localized Copy — not "Copyright" or a long sentence. */
    fun isCopyLabel(label: String): Boolean {
        val text = label.trim().lowercase()
        if (text.isEmpty() || text.length > MAX_LABEL) return false
        return copyWords.any { text == it || text.startsWith("$it ") }
    }

    /** "Text copied", "Message copied", "Copied to clipboard"… Short texts only. */
    fun isCopiedText(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty() || normalized.length > MAX_COPIED_TEXT) return false
        return copiedPhrases.any { normalized.contains(it) }
    }

    /** Token bucket: a burst of window checks is fine, a runaway loop is not. */
    private fun takeToken(at: Long): Boolean {
        tokens = (tokens + (at - tokensAt) / WINDOW_REFILL_MS.toDouble()).coerceAtMost(WINDOW_BURST.toDouble())
        tokensAt = at
        if (tokens < 1) return false
        tokens -= 1
        return true
    }

    companion object {
        const val MAX_LABEL = 24
        const val MAX_COPIED_TEXT = 60
        const val CLICK_DELAY_MS = 250L // a few apps copy asynchronously after the tap
        const val COPIED_DELAY_MS = 120L
        const val WINDOW_DELAY_MS = 350L
        const val ECHO_MS = 300L
        const val ALREADY_SENT_MS = 800L
        const val WINDOW_BURST = 4
        const val WINDOW_REFILL_MS = 2_500L

        // ponytail: a small fixed word list for apps' own "copied" toasts; the system's exact
        // localized phrases are added at runtime. Extend when a language turns up missing.
        private val GENERIC_COPIED = listOf(
            "copied", "कॉपी", "copié", "copiado", "copiato", "kopiert", "скопир", "已复制", "已複製", "コピー", "복사",
        )
    }
}
