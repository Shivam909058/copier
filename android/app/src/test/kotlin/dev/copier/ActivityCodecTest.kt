package dev.copier

import dev.copier.ActivityItem.Kind
import dev.copier.ActivityItem.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityCodecTest {
    private fun item(id: String, kind: Kind = Kind.TEXT, state: State = State.RECEIVED, sensitive: Boolean = false, text: String? = "hello") =
        ActivityItem(
            id = id, fromMac = id.hashCode() % 2 == 0, kind = kind, title = "Title $id", text = text, size = 42,
            mime = "text/plain", uri = "content://x/$id", clip = true, sensitive = sensitive, state = state,
            failure = null, time = 1_700_000_000_000L + id.hashCode(),
        )

    @Test
    fun roundTripsEveryField() {
        val items = listOf(
            item("a"),
            item("b", Kind.IMAGE, State.DELIVERED, text = null),
            item("c", Kind.LINK, State.WAITING, text = "https://example.com"),
            item("d", Kind.FILE, State.FAILED, text = null).copy(failure = "Mac refused (hash)", uri = null, size = null, mime = null),
        )
        assertEquals(items, ActivityCodec.decode(ActivityCodec.encode(items)))
    }

    @Test
    fun keepsOnlyTheNewestTwoHundred() {
        val items = (0 until 250).map { item("i$it") }
        val decoded = ActivityCodec.decode(ActivityCodec.encode(items))
        assertEquals(ActivityCodec.CAP, decoded.size)
        assertEquals("i0", decoded.first().id)
        assertEquals("i199", decoded.last().id)
    }

    @Test
    fun neverPersistsSensitiveText() {
        val json = ActivityCodec.encode(listOf(item("p", sensitive = true, text = "hunter2")))
        assertFalse(json.contains("hunter2"))
        assertNull(ActivityCodec.decode(json).single().text)
    }

    @Test
    fun capsPreviewLength() {
        val decoded = ActivityCodec.decode(ActivityCodec.encode(listOf(item("long", text = "x".repeat(5_000))))).single()
        assertEquals(ActivityCodec.MAX_TEXT, decoded.text?.length)
    }

    @Test
    fun transfersInFlightComeBackInterrupted() {
        val decoded = ActivityCodec.decode(
            ActivityCodec.encode(
                listOf(
                    item("moving", Kind.FILE, State.TRANSFERRING, text = null),
                    item("queued", Kind.IMAGE, State.WAITING, text = null),
                    item("clip", Kind.TEXT, State.WAITING),
                    item("link", Kind.LINK, State.WAITING),
                ),
            ),
        ).associateBy { it.id }
        assertEquals(State.FAILED, decoded.getValue("moving").state)
        assertEquals("Interrupted", decoded.getValue("moving").failure)
        assertEquals(State.FAILED, decoded.getValue("queued").state)
        assertEquals(State.WAITING, decoded.getValue("clip").state) // an ack may simply never come
        assertEquals(State.WAITING, decoded.getValue("link").state)
    }

    @Test
    fun malformedHistoryIsSkippedNotFatal() {
        assertTrue(ActivityCodec.decode("not json").isEmpty())
        val decoded = ActivityCodec.decode("""[{"id":"x"}, ${ActivityCodec.encode(listOf(item("ok"))).removeSurrounding("[", "]")}]""")
        assertEquals(listOf("ok"), decoded.map { it.id })
    }

    @Test
    fun progressGateThrottlesToTwoPercentSteps() {
        val emitted = mutableListOf<Float>()
        val gate = ProgressGate { emitted += it }
        for (done in 0L..1000L) gate.update(done, 1000L)
        assertTrue("emitted ${emitted.size} updates", emitted.size in 40..60)
        assertEquals(1f, emitted.last())
    }

    @Test
    fun stateWordsMatchTheSpec() {
        assertEquals("On Mac ✓", item("s", state = State.DELIVERED).copy(fromMac = false).stateLine())
        assertEquals("Sending 62%", item("s", Kind.FILE, State.TRANSFERRING).copy(fromMac = false, progress = 0.62f).stateLine())
        assertEquals("Saved to Downloads", item("s", Kind.FILE).copy(fromMac = true, clip = false).stateLine())
        assertEquals("Didn't arrive", item("s", state = State.FAILED).stateLine())
    }
}
