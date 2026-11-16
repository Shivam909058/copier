package dev.copier

import dev.copier.CopyDetector.Decision
import dev.copier.CopyDetector.Kind
import dev.copier.CopyDetector.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyDetectorTest {
    private val own = "dev.copier"
    private fun detector() = CopyDetector(own, copyWords = listOf("कॉपी करें"), copiedPhrases = listOf("Text copied"))
    private fun signal(kind: Kind, label: String = "", at: Long = 10_000, pkg: String = "com.whatsapp") = Signal(kind, pkg, label, at)

    @Test
    fun recognisesCopyControlsOnly() {
        val d = detector()
        for (label in listOf("Copy", " copy ", "Copy link", "Copy image", "Copy link address", "कॉपी करें")) assertTrue(label, d.isCopyLabel(label))
        for (label in listOf("Copyright", "Copy this whole paragraph to everyone", "Paste", "", "More options")) assertFalse(label, d.isCopyLabel(label))
    }

    @Test
    fun recognisesCopiedMessages() {
        val d = detector()
        for (text in listOf("Text copied", "Message copied", "Copied to clipboard", "Link copied", "संदेश कॉपी किया गया")) assertTrue(text, d.isCopiedText(text))
        for (text in listOf("Message sent", "", "x".repeat(40) + " copied " + "y".repeat(40))) assertFalse(text, d.isCopiedText(text))
    }

    @Test
    fun copyTapSchedulesADefiniteCheck() {
        val d = detector()
        assertEquals(Decision.Check(CopyDetector.CLICK_DELAY_MS), d.onSignal(signal(Kind.CLICK, "Copy")))
        val pending = d.takePending()
        assertTrue(pending!!.explicit) // takePending is non-null right after a Check
        assertEquals("click", pending.trigger)
        assertNull(d.takePending())
    }

    @Test
    fun systemAnnouncementAndToastsTriggerAChecks() {
        assertEquals(Decision.Check(CopyDetector.COPIED_DELAY_MS), detector().onSignal(signal(Kind.ANNOUNCEMENT, "Text copied", pkg = "com.android.systemui")))
        assertEquals(Decision.Check(CopyDetector.COPIED_DELAY_MS), detector().onSignal(signal(Kind.TOAST, "Message copied")))
        assertTrue(detector().onSignal(signal(Kind.TOAST, "Message sent")) is Decision.Ignore)
        assertTrue(detector().onSignal(signal(Kind.CLICK, "Reply")) is Decision.Ignore)
    }

    @Test
    fun menuClosingTriggersACheckEvenWithoutACopyTap() {
        val d = detector()
        assertEquals(Decision.Check(CopyDetector.WINDOW_DELAY_MS), d.onSignal(signal(Kind.WINDOW)))
        assertFalse(d.takePending()!!.explicit)
    }

    @Test
    fun ourOwnAppAndOurOwnFocusChangesAreIgnored() {
        val d = detector()
        assertTrue(d.onSignal(signal(Kind.CLICK, "Copy", pkg = own)) is Decision.Ignore)
        d.onSignal(signal(Kind.WINDOW, at = 10_000))
        d.takePending()
        d.onCheckFinished(at = 10_400, sent = false)
        assertTrue("echo of our own check", d.onSignal(signal(Kind.WINDOW, at = 10_500)) is Decision.Ignore)
        assertTrue("a real change later", d.onSignal(signal(Kind.WINDOW, at = 10_400 + CopyDetector.ECHO_MS)) is Decision.Check)
    }

    @Test
    fun aSecondSignalJoinsTheScheduledCheckAndUpgradesIt() {
        val d = detector()
        d.onSignal(signal(Kind.WINDOW, at = 10_000))
        assertTrue(d.onSignal(signal(Kind.TOAST, "Copied", at = 10_100)) is Decision.Ignore)
        assertTrue("the pending check is now a definite one", d.takePending()!!.explicit)
    }

    @Test
    fun oneCopyIsNotCheckedTwiceAfterItWasSent() {
        val d = detector()
        d.onSignal(signal(Kind.CLICK, "Copy", at = 10_000))
        d.takePending()
        d.onCheckFinished(at = 10_300, sent = true)
        assertTrue(d.onSignal(signal(Kind.ANNOUNCEMENT, "Text copied", at = 10_500, pkg = "com.android.systemui")) is Decision.Ignore)
        assertTrue(d.onSignal(signal(Kind.CLICK, "Copy", at = 10_300 + CopyDetector.ALREADY_SENT_MS)) is Decision.Check)
    }

    @Test
    fun windowChecksAreRateLimitedButCopySignalsNeverAre() {
        val d = detector()
        var at = 10_000L
        var checks = 0
        repeat(12) { // a runaway loop of window events, 400 ms apart
            if (d.onSignal(signal(Kind.WINDOW, at = at)) is Decision.Check) {
                checks++
                d.takePending()
                d.onCheckFinished(at + 50, sent = false)
            }
            at += 400
        }
        assertTrue("burst allowed, loop throttled (got $checks)", checks in CopyDetector.WINDOW_BURST..CopyDetector.WINDOW_BURST + 2)
        assertTrue(d.onSignal(signal(Kind.CLICK, "Copy", at = at)) is Decision.Check)
    }

    @Test
    fun windowChecksCanBeSwitchedOff() {
        val d = detector().apply { thorough = false }
        assertTrue(d.onSignal(signal(Kind.WINDOW)) is Decision.Ignore)
        assertTrue(d.onSignal(signal(Kind.CLICK, "Copy")) is Decision.Check)
    }
}
