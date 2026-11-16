package dev.copier.proto

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Locale

/**
 * Commit–reveal pairing (PROTOCOL.md § Pairing), plus the QR variant where a one-time token from the
 * Mac's QR code replaces the human code comparison (PROTOCOL.md § QR pairing).
 */
object Pairing {
    fun commitment(publicKey: ByteArray, nonce: ByteArray): ByteArray = Crypto.sha256(publicKey, nonce)

    /**
     * Six decimal digits both users compare. The phone commits to its key before seeing the Mac's,
     * so an attacker cannot search for keys that make two different codes collide.
     */
    fun code(phonePublicKey: ByteArray, macPublicKey: ByteArray, phoneNonce: ByteArray, macNonce: ByteArray): String {
        val hash = Crypto.sha256("copier/v1/sas".toByteArray(Charsets.UTF_8), phonePublicKey, macPublicKey, phoneNonce, macNonce)
        val value = (ByteBuffer.wrap(hash).int.toLong() and 0xFFFF_FFFFL) % 1_000_000
        // Locale.ROOT: some locales format digits with non-ASCII numerals.
        return String.format(Locale.ROOT, "%06d", value)
    }

    fun display(code: String): String = "${code.take(3)} ${code.takeLast(3)}"

    /**
     * Proves we saw the QR (only it carries [token]). Binding both keys and nonces means a proof
     * captured from one exchange is useless in another.
     */
    fun qrProof(token: ByteArray, phonePublicKey: ByteArray, macPublicKey: ByteArray, phoneNonce: ByteArray, macNonce: ByteArray): ByteArray =
        Crypto.hmacSha256(token, "copier/v1/qr".toByteArray(Charsets.UTF_8), phonePublicKey, macPublicKey, phoneNonce, macNonce)
}

/** What the Mac's QR code tells the phone, delivered as `copier://pair?h=…&p=…&k=…&t=…`. */
class QrPayload(val hosts: List<String>, val port: Int, val macPublicKey: ByteArray, val token: ByteArray) {
    companion object {
        /** Parses `h=<ip>[,<ip>…]&p=<port>&k=<base64url>&t=<base64url>`; throws IllegalArgumentException when malformed. */
        fun parse(query: String): QrPayload {
            val params = query.split('&').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            }.toMap()
            val hosts = params["h"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val port = params["p"]?.toIntOrNull() ?: Proto.PORT
            require(port in 1..65_535) { "port $port out of range" }
            // The URL decoder accepts unpadded input, which is what the QR carries.
            val key = Base64.getUrlDecoder().decode(requireNotNull(params["k"]) { "QR has no Mac key" })
            val token = Base64.getUrlDecoder().decode(requireNotNull(params["t"]) { "QR has no token" })
            require(token.size == 16) { "QR token must be 16 bytes, got ${token.size}" }
            require(Crypto.isValidPublicKey(key)) { "QR Mac key is not a P-256 point" }
            return QrPayload(hosts, port, key, token)
        }
    }
}
