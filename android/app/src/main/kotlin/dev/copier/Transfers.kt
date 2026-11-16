package dev.copier

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import dev.copier.proto.FileNames
import dev.copier.proto.Header
import dev.copier.proto.Message
import dev.copier.proto.ProtoException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Something to push to the Mac. URIs are opened by the activity that holds the grant (it's checked
 * at open time), and the descriptor stays valid after that activity finishes. [id] is the `put` id
 * and the timeline id on both devices.
 */
class Outgoing private constructor(
    val id: String,
    val name: String,
    val mime: String,
    val purpose: String,
    val sensitive: Boolean,
    val size: Long?,
    private val sourceUri: Uri?,
    private val fd: ParcelFileDescriptor?,
    private val bytes: ByteArray?,
) {
    private val kind: ActivityItem.Kind
        get() = when {
            mime.startsWith("image/") -> ActivityItem.Kind.IMAGE
            purpose == "clip" && mime.startsWith("text/") -> ActivityItem.Kind.TEXT
            else -> ActivityItem.Kind.FILE
        }

    fun toActivity() = ActivityItem(
        id = id, fromMac = false, kind = kind, title = if (purpose == "screenshot") "Screenshot" else name,
        size = size, mime = mime, uri = sourceUri?.toString(), clip = purpose == "clip", sensitive = sensitive,
        state = ActivityItem.State.WAITING,
    )

    /** The protocol declares the size up front; pipes don't know theirs, so they're spooled first. */
    fun open(cacheDir: File): Pair<InputStream, Long> {
        bytes?.let { return ByteArrayInputStream(it) to it.size.toLong() }
        val descriptor = requireNotNull(fd) { "Outgoing has neither bytes nor a descriptor" }
        if (descriptor.statSize >= 0) return ParcelFileDescriptor.AutoCloseInputStream(descriptor) to descriptor.statSize
        val spool = File.createTempFile("push", null, cacheDir)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> spool.outputStream().use { input.copyTo(it) } }
        val stream = object : FileInputStream(spool) {
            override fun close() {
                super.close()
                spool.delete()
            }
        }
        return stream to spool.length()
    }

    fun discard() {
        try {
            fd?.close()
        } catch (_: IOException) {
            // Nothing useful to do; the descriptor is being dropped either way.
        }
    }

    companion object {
        fun ofBytes(name: String, mime: String, purpose: String, sensitive: Boolean, bytes: ByteArray, id: String = UUID.randomUUID().toString()) =
            Outgoing(id, name, mime, purpose, sensitive, bytes.size.toLong(), null, null, bytes)

        /** Also starts the timeline thumbnail, while the grant still lets us read [uri]. */
        fun ofUri(context: Context, uri: Uri, purpose: String, sensitive: Boolean): Outgoing {
            val resolver = context.contentResolver
            var name: String? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0)
            }
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val fd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("cannot open shared item")
            val id = UUID.randomUUID().toString()
            if (mime.startsWith("image/")) Thumbnails.fromUriAsync(context, uri, ActivityStore.get(context).thumbFile(id))
            return Outgoing(id, name ?: uri.lastPathSegment ?: "file", mime, purpose, sensitive, fd.statSize.takeIf { it >= 0 }, uri, fd, null)
        }
    }
}

/** Where a pulled blob ended up, for the timeline. */
class Landed(val kind: ActivityItem.Kind, val uri: Uri?, val text: String?, val name: String, val clip: Boolean)

/** Blob transfers over dedicated `data` connections, so big files never delay clipboard messages. */
class Transfers(private val context: Context, private val store: Store, private val bridge: ClipboardBridge) {
    private val activity = ActivityStore.get(context)

    /** Mac → phone: fetch an offered blob, verify size + SHA-256, then deliver it by purpose. */
    fun pull(mac: PairedMac, host: String, port: Int, offer: Header, onProgress: (Long, Long) -> Unit): Landed {
        val id = offer.id ?: throw ProtoException("offer without id")
        val started = System.nanoTime()
        val (link, _) = SecureSocket.open(store.localDevice, mac, host, port, "data")
        link.use {
            link.setReadTimeout(DATA_IDLE_TIMEOUT_MS)
            link.send(Message(Header("get", id = id)))
            val put = link.receive().header
            if (put.t == "error") throw MacRefused(put.code ?: "unknown")
            val size = put.size ?: -1
            if (put.t != "put" || size < 0) throw ProtoException("expected put, got '${put.t}'")
            val mime = put.mime ?: "application/octet-stream"
            val sensitive = put.sensitive == true
            val isClip = put.purpose == "clip"
            val progress = { done: Long -> onProgress(done, size) }
            val landed = when {
                isClip && mime.startsWith("image/") -> receiveClipImage(link, id, size, mime, sensitive, progress)
                isClip && mime.startsWith("text/") && size <= ClipboardBridge.MAX_CLIPBOARD_TEXT_BYTES ->
                    receiveClipText(link, size, sensitive, progress)
                isClip && mime.startsWith("text/") -> receiveDownload(link, id, size, "Copied text.txt", "text/plain", mac.name, progress)
                else -> receiveDownload(link, id, size, FileNames.sanitize(put.name ?: "file"), mime, mac.name, progress)
            }
            Log.i(TAG, "pulled ${put.purpose} blob: $size bytes, $mime, ${(System.nanoTime() - started) / 1_000_000} ms")
            return landed
        }
    }

    /** Phone → Mac: push one blob and wait for the Mac's verdict. */
    fun push(mac: PairedMac, host: String, port: Int, item: Outgoing, onProgress: (Long, Long) -> Unit) {
        val started = System.nanoTime()
        val (input, size) = item.open(context.cacheDir)
        input.use {
            val (link, _) = SecureSocket.open(store.localDevice, mac, host, port, "data")
            link.use {
                link.setReadTimeout(DATA_IDLE_TIMEOUT_MS)
                link.send(
                    Message(
                        Header(
                            "put", id = item.id, name = item.name, size = size,
                            mime = item.mime, purpose = item.purpose, sensitive = item.sensitive,
                        ),
                    ),
                )
                link.sendBlob(input, size) { sent -> onProgress(sent, size) }
                val reply = link.receive().header
                if (reply.t != "done") throw MacRefused(reply.code ?: reply.t)
            }
        }
        Log.i(TAG, "pushed ${item.purpose} blob: $size bytes, ${item.mime}, ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    private fun receiveClipImage(link: SecureSocket, id: String, size: Long, mime: String, sensitive: Boolean, progress: (Long) -> Unit): Landed {
        val file = ClipProvider.newFile(context, mime)
        receiveVerified(link, { file.outputStream() }, onFailure = { file.delete() }, size, progress)
        Thumbnails.fromFile(file, activity.thumbFile(id))
        val uri = ClipProvider.uriFor(file)
        bridge.applyUri(uri, sensitive)
        return Landed(ActivityItem.Kind.IMAGE, uri, null, "Image", clip = true)
    }

    private fun receiveClipText(link: SecureSocket, size: Long, sensitive: Boolean, progress: (Long) -> Unit): Landed {
        val buffer = ByteArrayOutputStream(size.toInt())
        receiveVerified(link, { buffer }, onFailure = {}, size, progress)
        val text = buffer.toString(Charsets.UTF_8.name())
        bridge.applyText(text, null, sensitive)
        return Landed(ActivityItem.Kind.TEXT, null, text, "Text", clip = true)
    }

    private fun receiveDownload(link: SecureSocket, id: String, size: Long, name: String, mime: String, from: String, progress: (Long) -> Unit): Landed {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Copier")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore refused the download")
        receiveVerified(
            link,
            open = { resolver.openOutputStream(uri) ?: throw IOException("cannot open the download for writing") },
            onFailure = { resolver.delete(uri, null, null) },
            size = size,
            progress = progress,
            finish = { resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) },
        )
        if (mime.startsWith("image/") || mime.startsWith("video/")) Thumbnails.fromUri(context, uri, activity.thumbFile(id))
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        Notifications.post(
            context, "Received from $from", name,
            PendingIntent.getActivity(context, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE),
        )
        val kind = if (mime.startsWith("image/")) ActivityItem.Kind.IMAGE else ActivityItem.Kind.FILE
        return Landed(kind, uri, null, name, clip = false)
    }

    /** Receives into [open]'s stream, then `done`; on any failure deletes the partial output and reports `error`. */
    private fun receiveVerified(
        link: SecureSocket,
        open: () -> OutputStream,
        onFailure: () -> Unit,
        size: Long,
        progress: (Long) -> Unit,
        finish: () -> Unit = {},
    ) {
        try {
            open().use { link.receiveBlob(it, size, progress) }
            finish()
        } catch (e: Exception) {
            onFailure()
            val code = if (e is ProtoException) "hash" else "io"
            try {
                link.send(Message(Header("error", code = code, msg = e.message)))
            } catch (_: IOException) {
                // The connection is already gone; the Mac will see the close.
            }
            throw e
        }
        link.send(Message(Header("done")))
    }

    /** Clipboard text from the Mac that is too big for Binder is delivered as a file instead. */
    fun saveTextAsDownload(text: ByteArray, from: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "Copied text.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Copier")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore refused the download")
        resolver.openOutputStream(uri)?.use { it.write(text) } ?: throw IOException("cannot open the download for writing")
        Notifications.post(context, "Large text from $from", "Too big for the clipboard — saved to Downloads/Copier")
        return uri
    }
}
