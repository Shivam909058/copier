package dev.copier.proto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.PrivateKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Per-direction keys from the session handshake (PROTOCOL.md § Session handshake). */
class SessionKeys(val initiatorToResponder: ByteArray, val responderToInitiator: ByteArray) {
    companion object {
        /**
         * `hello*` are the exact JSON payloads exchanged on the wire; hashing them binds the keys to
         * this transcript, so a tampered hello yields keys the peer cannot match.
         */
        fun derive(
            staticKey: PrivateKey,
            peerStaticKey: ByteArray,
            ephemeralKey: PrivateKey,
            peerEphemeralKey: ByteArray,
            helloInitiator: ByteArray,
            helloResponder: ByteArray,
        ): SessionKeys {
            val ee = Crypto.ecdh(ephemeralKey, peerEphemeralKey)
            val ss = Crypto.ecdh(staticKey, peerStaticKey)
            val okm = Crypto.hkdf(
                ikm = ee + ss,
                salt = Crypto.sha256(helloInitiator, helloResponder),
                info = "copier/v1/session".toByteArray(Charsets.UTF_8),
                length = 64,
            )
            return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
        }
    }
}

/**
 * One direction of an encrypted connection: AES-256-GCM with a counter nonce. Because the counter is
 * implicit, a replayed, dropped or reordered frame fails authentication. Not thread-safe; callers
 * serialize seal+write (and there is a single reader).
 */
class CipherState(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    var counter: Long = 0
        private set

    fun seal(plaintext: ByteArray): ByteArray {
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce()))
        val out = cipher.doFinal(plaintext)
        counter++
        return out
    }

    fun open(frame: ByteArray): ByteArray {
        if (frame.size < 16) throw ProtoException("frame shorter than the GCM tag")
        val out = try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce()))
            cipher.doFinal(frame)
        } catch (e: GeneralSecurityException) {
            throw ProtoException("frame failed authentication (counter $counter)", e)
        }
        counter++
        return out
    }

    private fun nonce(): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
}
