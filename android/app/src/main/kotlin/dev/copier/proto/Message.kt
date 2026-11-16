package dev.copier.proto

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Every JSON header on the wire. One flat class instead of a type per message: the wire format is
 * flat, unknown fields are ignored, and each handler checks the fields its type requires.
 */
class Header(
    val t: String,
    val v: Int? = null,
    val kind: String? = null,
    val id: String? = null,
    val name: String? = null,
    val app: String? = null,
    val eph: ByteArray? = null,
    val nonce: ByteArray? = null,
    val pub: ByteArray? = null,
    val commit: ByteArray? = null,
    val proof: ByteArray? = null,
    val code: String? = null,
    val msg: String? = null,
    val sensitive: Boolean? = null,
    val htmlBytes: Int? = null,
    val size: Long? = null,
    val mime: String? = null,
    val purpose: String? = null,
    val sha256: String? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    // Notifications, ring and links (PROTOCOL.md § Control messages).
    val key: String? = null,
    val pkg: String? = null,
    val appName: String? = null,
    val title: String? = null,
    val text: String? = null,
    val time: Long? = null,
    val actions: List<String>? = null,
    val replyAction: Int? = null,
    val call: Boolean? = null,
    val index: Int? = null,
    val url: String? = null,
    /** `status`: whether the phone's automatic-copy service is running. */
    val watcher: Boolean? = null,
    /** `status`: the phone can turn automatic copy back on by itself. */
    val managed: Boolean? = null,
) {
    fun toJson(): ByteArray {
        val o = JSONObject().put("t", t)
        v?.let { o.put("v", it) }
        kind?.let { o.put("kind", it) }
        id?.let { o.put("id", it) }
        name?.let { o.put("name", it) }
        app?.let { o.put("app", it) }
        eph?.let { o.put("eph", b64(it)) }
        nonce?.let { o.put("nonce", b64(it)) }
        pub?.let { o.put("pub", b64(it)) }
        commit?.let { o.put("commit", b64(it)) }
        proof?.let { o.put("proof", b64(it)) }
        code?.let { o.put("code", it) }
        msg?.let { o.put("msg", it) }
        sensitive?.let { o.put("sensitive", it) }
        htmlBytes?.let { o.put("htmlBytes", it) }
        size?.let { o.put("size", it) }
        mime?.let { o.put("mime", it) }
        purpose?.let { o.put("purpose", it) }
        sha256?.let { o.put("sha256", it) }
        battery?.let { o.put("battery", it) }
        charging?.let { o.put("charging", it) }
        key?.let { o.put("key", it) }
        pkg?.let { o.put("pkg", it) }
        appName?.let { o.put("appName", it) }
        title?.let { o.put("title", it) }
        text?.let { o.put("text", it) }
        time?.let { o.put("time", it) }
        actions?.let { o.put("actions", JSONArray(it)) }
        replyAction?.let { o.put("replyAction", it) }
        call?.let { o.put("call", it) }
        index?.let { o.put("index", it) }
        url?.let { o.put("url", it) }
        watcher?.let { o.put("watcher", it) }
        managed?.let { o.put("managed", it) }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun parse(bytes: ByteArray): Header =
            try {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                Header(
                    t = o.getString("t"),
                    v = o.opt("v")?.let { o.getInt("v") },
                    kind = o.str("kind"),
                    id = o.str("id"),
                    name = o.str("name"),
                    app = o.str("app"),
                    eph = o.bytes("eph"),
                    nonce = o.bytes("nonce"),
                    pub = o.bytes("pub"),
                    commit = o.bytes("commit"),
                    proof = o.bytes("proof"),
                    code = o.str("code"),
                    msg = o.str("msg"),
                    sensitive = o.opt("sensitive")?.let { o.getBoolean("sensitive") },
                    htmlBytes = o.opt("htmlBytes")?.let { o.getInt("htmlBytes") },
                    size = o.opt("size")?.let { o.getLong("size") },
                    mime = o.str("mime"),
                    purpose = o.str("purpose"),
                    sha256 = o.str("sha256"),
                    battery = o.opt("battery")?.let { o.getInt("battery") },
                    charging = o.opt("charging")?.let { o.getBoolean("charging") },
                    key = o.str("key"),
                    pkg = o.str("pkg"),
                    appName = o.str("appName"),
                    title = o.str("title"),
                    text = o.str("text"),
                    time = o.opt("time")?.let { o.getLong("time") },
                    actions = o.optJSONArray("actions")?.let { array -> List(array.length()) { array.getString(it) } },
                    replyAction = o.opt("replyAction")?.let { o.getInt("replyAction") },
                    call = o.opt("call")?.let { o.getBoolean("call") },
                    index = o.opt("index")?.let { o.getInt("index") },
                    url = o.str("url"),
                    watcher = o.opt("watcher")?.let { o.getBoolean("watcher") },
                    managed = o.opt("managed")?.let { o.getBoolean("managed") },
                )
            } catch (e: JSONException) {
                throw ProtoException("malformed header JSON", e)
            } catch (e: IllegalArgumentException) {
                throw ProtoException("malformed base64 in header", e)
            }

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)

        private fun JSONObject.bytes(key: String): ByteArray? = str(key)?.let { Base64.getDecoder().decode(it) }
    }
}

/** The plaintext inside an encrypted frame: `u32 headerLength ‖ header JSON ‖ body`. */
class Message(val header: Header, val body: ByteArray = EMPTY) {
    fun encode(): ByteArray {
        val json = header.toJson()
        return ByteBuffer.allocate(4 + json.size + body.size).putInt(json.size).put(json).put(body).array()
    }

    companion object {
        private val EMPTY = ByteArray(0)

        fun decode(data: ByteArray): Message {
            if (data.size < 4) throw ProtoException("message shorter than its length prefix")
            val length = ByteBuffer.wrap(data).int.toLong() and 0xFFFF_FFFFL
            if (length > Proto.MAX_HEADER || length > data.size - 4) throw ProtoException("header length $length out of range")
            val end = 4 + length.toInt()
            return Message(Header.parse(data.copyOfRange(4, end)), data.copyOfRange(end, data.size))
        }
    }
}
