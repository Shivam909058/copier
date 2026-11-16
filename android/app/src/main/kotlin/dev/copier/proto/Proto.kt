package dev.copier.proto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Wire constants from PROTOCOL.md. Changing any of these is a protocol change. */
object Proto {
    const val VERSION = 1
    const val PORT = 47474
    const val SERVICE_TYPE = "_copier._tcp"
    const val MAX_HANDSHAKE_FRAME = 65_536
    const val MAX_MESSAGE = 1_048_576
    const val MAX_FRAME = MAX_MESSAGE + 16
    const val MAX_HEADER = 65_536
    const val CHUNK_SIZE = 262_144
    const val REMOTE_CLIP_LABEL = "dev.copier.remote"
}

/** A protocol violation or crypto failure. Always fatal for the connection it happened on. */
class ProtoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** `u32 big-endian length ‖ payload`, shared by the plaintext and encrypted phases. */
object Frames {
    fun write(out: DataOutputStream, payload: ByteArray) {
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    /** Rejects oversized lengths before allocating, so a peer can't make us allocate 4 GiB. */
    fun read(input: DataInputStream, max: Int): ByteArray {
        val length = input.readInt().toLong() and 0xFFFF_FFFFL
        if (length > max) throw ProtoException("frame of $length bytes exceeds limit $max")
        return ByteArray(length.toInt()).also { input.readFully(it) }
    }
}

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        chars[2 * i] = HEX[v ushr 4]
        chars[2 * i + 1] = HEX[v and 0x0F]
    }
    return String(chars)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string has odd length $length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[2 * i], 16)
        val lo = Character.digit(this[2 * i + 1], 16)
        require(hi >= 0 && lo >= 0) { "invalid hex at offset ${2 * i}" }
        ((hi shl 4) or lo).toByte()
    }
}
