package dev.copier

import dev.copier.proto.Header
import dev.copier.proto.Identity
import dev.copier.proto.Message
import dev.copier.proto.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Drives the phone's real networking code (Link.kt) against the real Swift server over loopback.
 * Skipped unless the Mac suite launches it: `cd mac && COPIER_INTEROP=1 swift test --filter Interop`.
 */
class MacInteropTest {
    @Test
    fun qrPairThenClipPingPushAndPull() {
        val port = System.getProperty("copier.interop.port")?.toIntOrNull()
        assumeTrue("launched by the Mac interop test", port != null)
        val host = "127.0.0.1"
        val macKey = Base64.getUrlDecoder().decode(System.getProperty("copier.interop.mackey"))
        val token = Base64.getUrlDecoder().decode(System.getProperty("copier.interop.token"))
        val me = LocalDevice(Identity.generate(), "Kotlin Phone", "interop")

        val mac = PairingSession(me, host, port!!).use { session ->
            session.exchangeCommit()
            session.revealWithQr(macKey, token)
            assertTrue("Mac accepted the QR proof", session.awaitMacVerdict())
            session.pairedMac()
        }

        val (control, ok) = SecureSocket.open(me, mac, host, port, "control")
        control.use {
            assertEquals("auth-ok", ok.t)
            val html = "<b>hi</b>".toByteArray()
            control.send(Message(Header("clip", sensitive = false, htmlBytes = html.size), html + "hello from kotlin".toByteArray()))
            control.send(Message(Header("ping")))
            assertEquals("pong", control.receive().header.t)
        }

        val upload = ByteArray(600_000) { (it % 251).toByte() }
        SecureSocket.open(me, mac, host, port, "data").first.use { data ->
            data.send(
                Message(
                    Header(
                        "put", id = UUID.randomUUID().toString(), name = "from-kotlin.bin", size = upload.size.toLong(),
                        mime = "application/octet-stream", purpose = "file", sensitive = false,
                    ),
                ),
            )
            data.sendBlob(upload.inputStream(), upload.size.toLong())
            assertEquals("done", data.receive().header.t)
        }

        SecureSocket.open(me, mac, host, port, "data").first.use { data ->
            data.send(Message(Header("get", id = System.getProperty("copier.interop.offer"))))
            val put = data.receive().header
            assertEquals("put", put.t)
            val pulled = ByteArrayOutputStream()
            data.receiveBlob(pulled, put.size ?: -1)
            data.send(Message(Header("done")))
            val sha = MessageDigest.getInstance("SHA-256").digest(pulled.toByteArray()).toHex()
            assertEquals(System.getProperty("copier.interop.offersha"), sha)
        }
    }
}
