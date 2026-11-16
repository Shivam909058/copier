package dev.copier

import dev.copier.proto.CipherState
import dev.copier.proto.Crypto
import dev.copier.proto.Frames
import dev.copier.proto.Header
import dev.copier.proto.Identity
import dev.copier.proto.Message
import dev.copier.proto.Pairing
import dev.copier.proto.Proto
import dev.copier.proto.ProtoException
import dev.copier.proto.SessionKeys
import dev.copier.proto.toHex
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

private const val CONNECT_TIMEOUT_MS = 5_000
private const val HANDSHAKE_TIMEOUT_MS = 10_000
const val DATA_IDLE_TIMEOUT_MS = 30_000
const val PAIR_TIMEOUT_MS = 60_000L

/** The Mac answered with a protocol `error` (e.g. `unpaired`, `version`, `busy`, `gone`). */
class MacRefused(val code: String) : IOException("Mac refused: $code")

/**
 * Who this phone is on the wire. Kept separate from [Store] so this file needs no Android APIs and
 * the JVM interop test can drive it against the real Mac server.
 */
class LocalDevice(val identity: Identity, val name: String, val appVersion: String)

/** One TCP connection to the Mac: plaintext frames during the handshake, AES-GCM frames after. */
class SecureSocket private constructor(private val socket: Socket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val writeLock = Any()
    private var sender: CipherState? = null
    private var receiver: CipherState? = null

    val remoteHost: String get() = socket.inetAddress?.hostAddress ?: ""

    fun writePlain(json: ByteArray) = synchronized(writeLock) { Frames.write(output, json) }

    fun readPlain(): ByteArray = Frames.read(input, Proto.MAX_HANDSHAKE_FRAME)

    /** Seal and write under one lock: the GCM counter must match the order frames hit the wire. */
    fun send(message: Message) = synchronized(writeLock) {
        val cipher = sender ?: throw IllegalStateException("send before the handshake finished")
        Frames.write(output, cipher.seal(message.encode()))
    }

    /** One reader thread per connection by design, so no lock here. */
    fun receive(): Message {
        val cipher = receiver ?: throw IllegalStateException("receive before the handshake finished")
        return Message.decode(cipher.open(Frames.read(input, Proto.MAX_FRAME)))
    }

    fun setReadTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed or broken; nothing left to release.
        }
    }

    companion object {
        fun connect(host: String, port: Int): SecureSocket {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                return SecureSocket(socket)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
        }

        /** Opens an authenticated `control` or `data` session; returns the socket and the Mac's `auth-ok`. */
        fun open(me: LocalDevice, mac: PairedMac, host: String, port: Int, kind: String): Pair<SecureSocket, Header> {
            val link = connect(host, port)
            try {
                val identity = me.identity
                val eph = Crypto.generateKeyPair()
                val hello = Header(
                    "hello", v = Proto.VERSION, kind = kind, id = identity.deviceId,
                    eph = Crypto.encodePublicKey(eph.public), nonce = Crypto.randomBytes(16),
                ).toJson()
                link.writePlain(hello)
                val replyBytes = link.readPlain()
                val reply = Header.parse(replyBytes)
                if (reply.t == "error") throw MacRefused(reply.code ?: "unknown")
                if (reply.t != "hello" || reply.v != Proto.VERSION || reply.id != mac.id) {
                    throw ProtoException("unexpected handshake reply '${reply.t}' from ${reply.id}")
                }
                val keys = SessionKeys.derive(
                    identity.privateKey, mac.publicKey, eph.private,
                    reply.eph ?: throw ProtoException("hello without eph"), hello, replyBytes,
                )
                link.sender = CipherState(keys.initiatorToResponder)
                link.receiver = CipherState(keys.responderToInitiator)
                link.send(Message(Header("auth", name = me.name, app = me.appVersion)))
                val ok = link.receive().header
                if (ok.t != "auth-ok") throw ProtoException("expected auth-ok, got '${ok.t}'")
                return link to ok
            } catch (e: Exception) {
                link.close()
                throw e
            }
        }
    }
}

/** Streams [size] bytes as `chunk`s, then `end` with the blob's SHA-256 (PROTOCOL.md § Data connections). */
fun SecureSocket.sendBlob(input: InputStream, size: Long, onProgress: (Long) -> Unit = {}) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(Proto.CHUNK_SIZE)
    var sent = 0L
    while (sent < size) {
        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
        if (n < 0) throw IOException("source ended after $sent of $size bytes")
        digest.update(buffer, 0, n)
        send(Message(Header("chunk"), buffer.copyOf(n)))
        sent += n
        onProgress(sent)
    }
    send(Message(Header("end", sha256 = digest.digest().toHex())))
}

/** Receives `chunk`s into [out] until `end`, enforcing the declared size and verifying the hash. */
fun SecureSocket.receiveBlob(out: OutputStream, size: Long, onProgress: (Long) -> Unit = {}) {
    val digest = MessageDigest.getInstance("SHA-256")
    var received = 0L
    while (true) {
        val message = receive()
        when (message.header.t) {
            "chunk" -> {
                received += message.body.size
                if (received > size) throw ProtoException("peer sent more than the declared $size bytes")
                digest.update(message.body)
                out.write(message.body)
                onProgress(received)
            }
            "end" -> {
                if (received != size) throw ProtoException("blob ended at $received of $size bytes")
                if (message.header.sha256 != digest.digest().toHex()) throw ProtoException("blob SHA-256 mismatch")
                return
            }
            "error" -> throw MacRefused(message.header.code ?: "unknown")
            else -> throw ProtoException("unexpected '${message.header.t}' during a transfer")
        }
    }
}

/**
 * Phone side of PROTOCOL.md § Pairing and § QR pairing. Every call blocks, so drive it from a worker
 * thread: [exchangeCommit], then either [revealWithCode] or [revealWithQr], then the verdicts.
 */
class PairingSession(private val me: LocalDevice, host: String, private val port: Int) : Closeable {
    private val link = SecureSocket.connect(host, port)
    private val phoneNonce = Crypto.randomBytes(16)
    private var macNonce = ByteArray(0)
    private var macId = ""
    var macName = "Mac"
        private set
    var macPublicKey = ByteArray(0)
        private set

    /** pair (commitment) → pair-ack. Learns the Mac's name and key; nothing is revealed yet. */
    fun exchangeCommit() {
        link.setReadTimeout(PAIR_TIMEOUT_MS.toInt())
        val identity = me.identity
        link.writePlain(
            Header(
                "pair", v = Proto.VERSION, id = identity.deviceId, name = me.name,
                commit = Pairing.commitment(identity.publicKey, phoneNonce),
            ).toJson(),
        )
        val ack = Header.parse(link.readPlain())
        if (ack.t == "error") throw MacRefused(ack.code ?: "unknown")
        val pub = ack.pub
        val nonce = ack.nonce
        val id = ack.id
        if (ack.t != "pair-ack" || ack.v != Proto.VERSION || pub == null || nonce?.size != 16 || id == null) {
            throw ProtoException("malformed pair-ack")
        }
        if (!Crypto.isValidPublicKey(pub) || Crypto.deviceId(pub) != id) throw ProtoException("Mac's id doesn't match its key")
        macId = id
        macName = ack.name ?: "Mac"
        macPublicKey = pub
        macNonce = nonce
    }

    /** Code path: reveal, then return the 6-digit code both screens must show. */
    fun revealWithCode(): String {
        val identity = me.identity
        link.writePlain(Header("pair-reveal", pub = identity.publicKey, nonce = phoneNonce).toJson())
        return Pairing.code(identity.publicKey, macPublicKey, phoneNonce, macNonce)
    }

    /**
     * QR path: the QR told us the Mac's key, so a different key means a different (or fake) Mac; the
     * token's HMAC proves to the Mac that we saw its QR. No code to compare, so pair-ok goes straight out.
     */
    fun revealWithQr(expectedMacKey: ByteArray, token: ByteArray) {
        if (!macPublicKey.contentEquals(expectedMacKey)) throw ProtoException("this Mac isn't the one in the QR code")
        val identity = me.identity
        val proof = Pairing.qrProof(token, identity.publicKey, macPublicKey, phoneNonce, macNonce)
        link.writePlain(Header("pair-reveal", pub = identity.publicKey, nonce = phoneNonce, proof = proof).toJson())
        sendVerdict(true)
    }

    fun sendVerdict(accepted: Boolean) = link.writePlain(Header(if (accepted) "pair-ok" else "pair-cancel").toJson())

    /** Blocks for the Mac user's answer. */
    fun awaitMacVerdict(): Boolean =
        when (val t = Header.parse(link.readPlain()).t) {
            "pair-ok" -> true
            "pair-cancel" -> false
            else -> throw ProtoException("unexpected '$t' while waiting for the Mac")
        }

    /** The Mac to remember once both verdicts are in; [host] is the address that actually worked. */
    fun pairedMac(): PairedMac = PairedMac(macId, macName, macPublicKey, link.remoteHost, port)

    override fun close() = link.close()
}
