package dev.copier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * One thing that crossed between this phone and the Mac — a row in the relay timeline
 * (docs/superpowers/specs/2026-11-16-relay-interface-design.md). Ids are the protocol ids.
 */
data class ActivityItem(
    val id: String,
    val fromMac: Boolean,
    val kind: Kind,
    val title: String,
    /** Preview for text items; never set for sensitive clips. */
    val text: String? = null,
    val size: Long? = null,
    val mime: String? = null,
    /** Received: where it landed. Sent: the source, which may stop being readable. */
    val uri: String? = null,
    /** Clipboard content rather than a shared/saved file. */
    val clip: Boolean = false,
    val sensitive: Boolean = false,
    val state: State,
    val progress: Float = 0f,
    val failure: String? = null,
    val time: Long = System.currentTimeMillis(),
) {
    enum class Kind { TEXT, IMAGE, FILE, LINK }
    enum class State { WAITING, TRANSFERRING, DELIVERED, RECEIVED, FAILED }

    val noun: String
        get() = when (kind) {
            Kind.TEXT -> "Text"
            Kind.IMAGE -> "Photo"
            Kind.FILE -> "File"
            Kind.LINK -> "Link"
        }

    /** The words under a bubble. */
    fun stateLine(): String =
        when (state) {
            State.WAITING -> when {
                kind == Kind.LINK -> "Sent to Mac"
                !fromMac && kind == Kind.TEXT -> "Sent"
                else -> "Waiting…"
            }
            State.TRANSFERRING -> "${if (fromMac) "Receiving" else "Sending"} ${(progress * 100).toInt()}%"
            State.DELIVERED -> "On Mac ✓"
            State.RECEIVED -> when {
                kind == Kind.LINK -> "Opened"
                clip -> "On clipboard"
                else -> "Saved to Downloads"
            }
            State.FAILED -> "Didn't arrive"
        }

    /** One line for the persistent notification. */
    fun summary(): String =
        when {
            kind == Kind.LINK -> if (fromMac) "Link from Mac opened" else "Link sent to Mac"
            !fromMac && state == State.DELIVERED -> "$noun on Mac ✓"
            !fromMac && state == State.FAILED -> "$noun didn't arrive"
            !fromMac -> "Sending ${noun.lowercase()}…"
            state == State.FAILED -> "$noun from Mac didn't arrive"
            state == State.RECEIVED && clip -> "$noun from Mac copied"
            state == State.RECEIVED -> "$title saved to Downloads"
            else -> "Receiving ${noun.lowercase()}…"
        }
}

/** JSON for the history file. Pure (org.json only) so it's JVM-testable. */
object ActivityCodec {
    const val CAP = 200
    const val MAX_TEXT = 2_000

    fun encode(items: List<ActivityItem>): String {
        val array = JSONArray()
        for (item in items.take(CAP)) {
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("fromMac", item.fromMac)
                    .put("kind", item.kind.name)
                    .put("title", item.title)
                    .put("clip", item.clip)
                    .put("sensitive", item.sensitive)
                    .put("state", item.state.name)
                    .put("time", item.time)
                    .apply {
                        if (!item.sensitive) item.text?.let { put("text", it.take(MAX_TEXT)) }
                        item.size?.let { put("size", it) }
                        item.mime?.let { put("mime", it) }
                        item.uri?.let { put("uri", it) }
                        item.failure?.let { put("failure", it) }
                    },
            )
        }
        return array.toString()
    }

    /** A transfer can't survive a restart, so anything still moving comes back as interrupted. */
    fun decode(json: String): List<ActivityItem> {
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        val items = ArrayList<ActivityItem>()
        for (i in 0 until minOf(array.length(), CAP)) {
            val o = array.optJSONObject(i) ?: continue
            try {
                val kind = ActivityItem.Kind.valueOf(o.getString("kind"))
                var state = ActivityItem.State.valueOf(o.getString("state"))
                var failure = o.strOrNull("failure")
                // Text and links "wait" for an ack that may simply never come; blobs were cut off.
                val stillMoving = state == ActivityItem.State.TRANSFERRING ||
                    (state == ActivityItem.State.WAITING && kind != ActivityItem.Kind.TEXT && kind != ActivityItem.Kind.LINK)
                if (stillMoving) {
                    state = ActivityItem.State.FAILED
                    failure = "Interrupted"
                }
                val sensitive = o.optBoolean("sensitive")
                items += ActivityItem(
                    id = o.getString("id"),
                    fromMac = o.getBoolean("fromMac"),
                    kind = kind,
                    title = o.getString("title"),
                    text = if (sensitive) null else o.strOrNull("text"),
                    size = if (o.has("size")) o.getLong("size") else null,
                    mime = o.strOrNull("mime"),
                    uri = o.strOrNull("uri"),
                    clip = o.optBoolean("clip"),
                    sensitive = sensitive,
                    state = state,
                    failure = failure,
                    time = o.getLong("time"),
                )
            } catch (e: JSONException) {
                continue // One bad entry shouldn't cost the whole history.
            } catch (e: IllegalArgumentException) {
                continue
            }
        }
        return items
    }

    private fun JSONObject.strOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}

/** Throttles progress callbacks to ≥ 2 % steps or 150 ms, whichever comes first. */
class ProgressGate(private val emit: (Float) -> Unit) {
    private var last = -1f
    private var lastAt = 0L

    fun update(done: Long, total: Long) {
        val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 1f
        val now = System.nanoTime() / 1_000_000
        // Completion always gets through: float steps of 0.02 can land just short of 1.0.
        val done100 = fraction >= 1f && last < 1f
        if (done100 || fraction - last >= 0.02f || (fraction > last && now - lastAt >= 150)) {
            last = fraction
            lastAt = now
            emit(fraction)
        }
    }
}

/** The timeline, newest first, persisted to `filesDir/activity.json`; thumbnails in `cacheDir/thumbs`. */
class ActivityStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "activity.json"))
    private val thumbs = File(context.cacheDir, "thumbs")
    private val items = ArrayList<ActivityItem>() // guarded by `this`
    private val io = Executors.newSingleThreadExecutor()

    /** Called on the changing thread; `stateChanged` is false for pure progress ticks. */
    @Volatile
    var onChange: ((stateChanged: Boolean) -> Unit)? = null

    init {
        if (file.baseFile.exists()) {
            try {
                items += ActivityCodec.decode(String(file.readFully(), Charsets.UTF_8))
            } catch (e: IOException) {
                Log.w(TAG, "activity history unreadable; starting fresh", e)
            }
        }
    }

    fun snapshot(): List<ActivityItem> = synchronized(this) { items.toList() }

    fun latest(): ActivityItem? = synchronized(this) { items.firstOrNull() }

    fun contains(id: String): Boolean = synchronized(this) { items.any { it.id == id } }

    fun add(item: ActivityItem) {
        synchronized(this) {
            items.removeAll { it.id == item.id }
            items.add(0, item)
            while (items.size > ActivityCodec.CAP) thumbFile(items.removeAt(items.lastIndex).id).delete()
        }
        changed(stateChanged = true)
    }

    fun update(id: String, transform: (ActivityItem) -> ActivityItem) {
        val stateChanged = synchronized(this) {
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return
            val before = items[index]
            items[index] = transform(before)
            before.state != items[index].state
        }
        changed(stateChanged)
    }

    fun remove(id: String) {
        synchronized(this) { items.removeAll { it.id == id } }
        thumbFile(id).delete()
        changed(stateChanged = true)
    }

    fun clear() {
        synchronized(this) { items.clear() }
        thumbs.listFiles()?.forEach { it.delete() }
        changed(stateChanged = true)
    }

    fun thumbFile(id: String) = File(thumbs, "$id.jpg")

    private fun changed(stateChanged: Boolean) {
        if (stateChanged) persist() // progress ticks aren't worth a disk write
        onChange?.invoke(stateChanged)
    }

    private fun persist() {
        val json = synchronized(this) { ActivityCodec.encode(items) }
        io.execute {
            val out = file.startWrite()
            try {
                out.write(json.toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: IOException) {
                file.failWrite(out)
                Log.w(TAG, "could not save activity history", e)
            }
        }
    }

    companion object {
        @Volatile private var instance: ActivityStore? = null

        fun get(context: Context): ActivityStore =
            instance ?: synchronized(this) {
                instance ?: ActivityStore(context.applicationContext).also { instance = it }
            }
    }
}

/** ≤ 360 px JPEG previews. Best effort: a missing thumbnail just shows the placeholder. */
object Thumbnails {
    private const val MAX = 360
    private val worker = Executors.newSingleThreadExecutor()

    /**
     * For URIs whose read grant is about to end (share sheet, picker, clipboard): opens a separate
     * descriptor now, while the grant holds, and decodes on a worker thread. Its own descriptor means
     * its own file offset, so it can't disturb the transfer reading the original one.
     */
    fun fromUriAsync(context: Context, uri: Uri, out: File) {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return
        worker.execute {
            descriptor.use { pfd -> decodeSampled(pfd)?.let { write(it, out) } }
        }
    }

    /** Worker threads only (MediaStore thumbnail I/O). */
    fun fromUri(context: Context, uri: Uri, out: File) {
        try {
            write(context.contentResolver.loadThumbnail(uri, Size(MAX, MAX), null), out)
        } catch (e: IOException) {
            Log.i(TAG, "no thumbnail: ${e.message}")
        } catch (e: RuntimeException) {
            Log.i(TAG, "no thumbnail: ${e.message}")
        }
    }

    /** Worker threads only. */
    fun fromFile(source: File, out: File) {
        try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> decodeSampled(pfd)?.let { write(it, out) } }
        } catch (e: IOException) {
            Log.i(TAG, "no thumbnail: ${e.message}")
        }
    }

    /** decodeFileDescriptor leaves the descriptor's position unchanged, so it can be read twice. */
    private fun decodeSampled(pfd: ParcelFileDescriptor): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX && bounds.outHeight / (sample * 2) >= MAX) sample *= 2
        return BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun write(bitmap: Bitmap, out: File) {
        val scale = MAX.toFloat() / maxOf(bitmap.width, bitmap.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        try {
            out.parentFile?.mkdirs()
            out.outputStream().use { sized.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (e: IOException) {
            Log.i(TAG, "could not cache a thumbnail: ${e.message}")
        }
    }
}
