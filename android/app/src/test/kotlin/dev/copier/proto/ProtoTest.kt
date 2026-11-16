package dev.copier.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class ProtoTest {
    @Test
    fun publicKeyRoundTripsThroughX963() {
        val identity = Identity.generate()
        assertEquals(65, identity.publicKey.size)
        assertArrayEquals(identity.publicKey, Crypto.encodePublicKey(Crypto.decodePublicKey(identity.publicKey)))
    }

    @Test
    fun rejectsPointsOffTheCurve() {
        val bad = Identity.generate().publicKey.copyOf()
        bad[64] = (bad[64].toInt() xor 1).toByte()
        assertFalse(Crypto.isValidPublicKey(bad))
        assertFalse(Crypto.isValidPublicKey(ByteArray(65)))
    }

    @Test
    fun handshakeBetweenFreshIdentitiesAgrees() {
        val phone = Identity.generate()
        val mac = Identity.generate()
        val phoneEph = Crypto.generateKeyPair()
        val macEph = Crypto.generateKeyPair()
        val hI = "i".toByteArray()
        val hR = "r".toByteArray()
        val p = SessionKeys.derive(phone.privateKey, mac.publicKey, phoneEph.private, Crypto.encodePublicKey(macEph.public), hI, hR)
        val m = SessionKeys.derive(mac.privateKey, phone.publicKey, macEph.private, Crypto.encodePublicKey(phoneEph.public), hI, hR)
        assertArrayEquals(p.initiatorToResponder, m.initiatorToResponder)
        assertArrayEquals(p.responderToInitiator, m.responderToInitiator)

        val impostor = SessionKeys.derive(Identity.generate().privateKey, mac.publicKey, phoneEph.private, Crypto.encodePublicKey(macEph.public), hI, hR)
        assertFalse(impostor.initiatorToResponder.contentEquals(m.initiatorToResponder))
    }

    @Test
    fun tamperedReplayedAndShortFramesAreRejected() {
        val key = Crypto.randomBytes(32)
        val sender = CipherState(key)
        val receiver = CipherState(key)

        val tampered = sender.seal("payload".toByteArray())
        tampered[0] = (tampered[0].toInt() xor 1).toByte()
        assertThrows(ProtoException::class.java) { receiver.open(tampered) }

        val fresh = CipherState(key)
        val frame = CipherState(key).seal("once".toByteArray())
        assertArrayEquals("once".toByteArray(), fresh.open(frame))
        assertThrows(ProtoException::class.java) { fresh.open(frame) }

        assertThrows(ProtoException::class.java) { CipherState(key).open(ByteArray(15)) }
    }

    @Test
    fun messageRoundTripsHeaderAndBody() {
        val message = Message(
            Header("put", id = "abc", name = "IMG.jpg", size = 5_000_000_000L, mime = "image/jpeg", purpose = "file", sensitive = true, pub = byteArrayOf(-1, 0)),
            body = "body".toByteArray(),
        )
        val decoded = Message.decode(message.encode())
        assertEquals("put", decoded.header.t)
        assertEquals("abc", decoded.header.id)
        assertEquals(5_000_000_000L, decoded.header.size)
        assertEquals(true, decoded.header.sensitive)
        assertArrayEquals(byteArrayOf(-1, 0), decoded.header.pub)
        assertEquals(null, decoded.header.htmlBytes)
        assertArrayEquals("body".toByteArray(), decoded.body)
        assertTrue(String(message.header.toJson()).contains("\"pub\":\"/wA=\""))
    }

    @Test
    fun headerIgnoresUnknownFieldsAndOmitsNulls() {
        assertEquals("clip", Header.parse("""{"t":"clip","fromTheFuture":42}""".toByteArray()).t)
        assertEquals("""{"t":"ping"}""", String(Header("ping").toJson()))
    }

    @Test
    fun malformedMessagesAreRejected() {
        assertThrows(ProtoException::class.java) { Message.decode(byteArrayOf(0, 0, 0, 100, '{'.code.toByte(), '}'.code.toByte())) }
        assertThrows(ProtoException::class.java) { Message.decode(byteArrayOf(0, 0)) }
        assertThrows(ProtoException::class.java) { Header.parse("not json".toByteArray()) }
    }

    @Test
    fun framesRoundTripAndRejectOversizedLengthsBeforeAllocating() {
        val buffer = ByteArrayOutputStream()
        Frames.write(DataOutputStream(buffer), byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 1, 2, 3), buffer.toByteArray())
        assertArrayEquals(byteArrayOf(1, 2, 3), Frames.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray())), 3))

        val huge = DataInputStream(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1)))
        assertThrows(ProtoException::class.java) { Frames.read(huge, Proto.MAX_FRAME) }
    }

    @Test
    fun pairingCodeIsSixAsciiDigits() {
        repeat(200) {
            val code = Pairing.code(Identity.generate().publicKey, Identity.generate().publicKey, Crypto.randomBytes(16), Crypto.randomBytes(16))
            assertTrue(code, code.length == 6 && code.all { it in '0'..'9' })
        }
        assertEquals("012 345", Pairing.display("012345"))
        assertNotEquals(
            Pairing.commitment(ByteArray(65) { 1 }, ByteArray(16) { 3 }).toHex(),
            Pairing.commitment(ByteArray(65) { 1 }, ByteArray(16) { 4 }).toHex(),
        )
    }

    @Test
    fun malformedQrPayloadsAreRejected() {
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Identity.generate().publicKey)
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        assertEquals(emptyList<String>(), QrPayload.parse("p=1&k=$key&t=$token").hosts)
        assertThrows(IllegalArgumentException::class.java) { QrPayload.parse("h=1.2.3.4&k=$key") }
        assertThrows(IllegalArgumentException::class.java) { QrPayload.parse("h=1.2.3.4&k=$key&t=AAAA") }
        assertThrows(IllegalArgumentException::class.java) { QrPayload.parse("h=1.2.3.4&k=AAAA&t=$token") }
        assertThrows(IllegalArgumentException::class.java) { QrPayload.parse("h=1.2.3.4&p=99999&k=$key&t=$token") }
    }

    @Test
    fun proofFieldRoundTrips() {
        assertArrayEquals(byteArrayOf(1, 2, 3), Header.parse(Header("pair-reveal", proof = byteArrayOf(1, 2, 3)).toJson()).proof)
    }

    @Test
    fun fileNamesMatchTheSwiftTable() {
        val table = listOf(
            "photo.jpg" to "photo.jpg",
            "../../etc/passwd" to "_.._etc_passwd",
            ".hidden" to "hidden",
            "a\u0000b\nc.txt" to "a_b_c.txt",
            "C:\\Windows\\evil.dll" to "C__Windows_evil.dll",
            "   " to "file",
            "..." to "file",
        )
        for ((input, expected) in table) assertEquals(input, expected, FileNames.sanitize(input))

        val long = FileNames.sanitize("é".repeat(300) + ".pdf")
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(long.endsWith(".pdf"))
    }
}
