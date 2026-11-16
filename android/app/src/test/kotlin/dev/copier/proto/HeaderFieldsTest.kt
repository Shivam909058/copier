package dev.copier.proto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The fields added for receipts, battery, notifications, ring and links (PROTOCOL.md § Control messages). */
class HeaderFieldsTest {
    private fun roundTrip(header: Header) = Header.parse(header.toJson())

    @Test
    fun statusAndAckRoundTrip() {
        val status = roundTrip(Header("status", battery = 82, charging = true))
        assertEquals(82, status.battery)
        assertEquals(true, status.charging)
        assertEquals("abc", roundTrip(Header("ack", id = "abc")).id)
        assertEquals("abc", roundTrip(Header("clip", id = "abc", sensitive = false)).id)
    }

    @Test
    fun notificationRoundTrips() {
        val notif = roundTrip(
            Header(
                "notif", key = "0|com.whatsapp|1|null|10", pkg = "com.whatsapp", appName = "WhatsApp", title = "Asha",
                text = "On my way", time = 1_731_771_000_123L, actions = listOf("Reply", "Mark as read"), replyAction = 0, call = false,
            ),
        )
        assertEquals("0|com.whatsapp|1|null|10", notif.key)
        assertEquals("com.whatsapp", notif.pkg)
        assertEquals("WhatsApp", notif.appName)
        assertEquals("Asha", notif.title)
        assertEquals("On my way", notif.text)
        assertEquals(1_731_771_000_123L, notif.time)
        assertEquals(listOf("Reply", "Mark as read"), notif.actions)
        assertEquals(0, notif.replyAction)
        assertEquals(false, notif.call)
    }

    @Test
    fun actionDismissRingAndOpenRoundTrip() {
        val action = roundTrip(Header("notif-action", key = "k", index = 1, text = "Thanks"))
        assertEquals(1, action.index)
        assertEquals("Thanks", action.text)
        assertEquals("https://example.com/x?y=1", roundTrip(Header("open", url = "https://example.com/x?y=1")).url)
        val ring = roundTrip(Header("ring"))
        assertEquals("ring", ring.t)
        assertNull(ring.key)
    }

    @Test
    fun actionsAreAJsonArrayAndAbsentFieldsAreOmitted() {
        val json = JSONObject(String(Header("notif", key = "k", actions = listOf("A", "B")).toJson(), Charsets.UTF_8))
        assertEquals(2, json.getJSONArray("actions").length())
        assertEquals(setOf("t", "key", "actions"), json.keys().asSequence().toSet())
    }

    @Test
    fun parsesWhatTheMacSends() {
        // Swift's JSONEncoder output: sorted keys, numbers for Int64.
        val mac = Header.parse(
            """{"actions":["Reply"],"appName":"Messages","call":true,"key":"k","pkg":"p","replyAction":0,"t":"notif","text":"hi","time":5,"title":"T"}""".toByteArray(),
        )
        assertEquals(listOf("Reply"), mac.actions)
        assertEquals(true, mac.call)
        assertEquals(5L, mac.time)
    }
}
