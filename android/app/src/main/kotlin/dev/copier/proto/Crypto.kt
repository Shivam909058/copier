package dev.copier.proto

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A device's long-term P-256 key pair. Its public key is the device's identity. */
class Identity(val privateKey: PrivateKey, val publicKey: ByteArray) {
    val deviceId: String = Crypto.deviceId(publicKey)

    companion object {
        fun generate(): Identity {
            val pair = Crypto.generateKeyPair()
            return Identity(pair.private, Crypto.encodePublicKey(pair.public))
        }
    }
}

object Crypto {
    private val random = SecureRandom()

    /** secp256r1 parameters taken from a generated key, so no curve constants live in this file. */
    private val p256: ECParameterSpec by lazy { (generateKeyPair().public as ECPublicKey).params }

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
            .generateKeyPair()

    /** X9.63 uncompressed: `04 ‖ X ‖ Y`, coordinates left-padded to 32 bytes (matches CryptoKit). */
    fun encodePublicKey(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        return byteArrayOf(4) + point.affineX.toFixed32() + point.affineY.toFixed32()
    }

    /** Peer keys are untrusted: reject anything that is not a point on P-256 (invalid-curve attacks). */
    fun decodePublicKey(bytes: ByteArray): PublicKey {
        if (bytes.size != 65 || bytes[0] != 4.toByte()) throw ProtoException("expected a 65-byte uncompressed P-256 point")
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val p = (p256.curve.field as ECFieldFp).p
        val onCurve = x < p && y < p &&
            y.multiply(y).subtract(x.pow(3).add(p256.curve.a.multiply(x)).add(p256.curve.b)).mod(p).signum() == 0
        if (!onCurve) throw ProtoException("public key is not on P-256")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256))
    }

    fun isValidPublicKey(bytes: ByteArray): Boolean =
        try {
            decodePublicKey(bytes)
            true
        } catch (e: ProtoException) {
            false
        }

    fun privateKeyFromScalar(scalar: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), p256))

    fun privateKeyFromPkcs8(encoded: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    /** Raw ECDH output: the 32-byte x-coordinate, same as CryptoKit's shared secret bytes. */
    fun ecdh(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(decodePublicKey(peerPublicKey), true)
            generateSecret()
        }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }

    fun deviceId(publicKey: ByteArray): String = sha256(publicKey).copyOf(8).toHex()

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }

    /** RFC 5869. JCA has no HKDF, and it's ten lines over HMAC. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "HKDF-SHA256 length $length out of range" }
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmacSha256(prk, block, info, byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    private fun BigInteger.toFixed32(): ByteArray {
        val raw = toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> throw IllegalArgumentException("coordinate does not fit in 32 bytes")
        }
    }
}
