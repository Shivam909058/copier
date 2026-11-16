package dev.copier

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import dev.copier.proto.Proto

/** Reads and writes the system clipboard, tagging our own writes so they're never echoed back. */
class ClipboardBridge(context: Context) {
    private val app = context.applicationContext
    private val clipboard = app.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** When we last wrote the clipboard; the logcat watcher ignores the burst our own write causes. */
    @Volatile
    var lastOwnWriteAt = 0L
        private set

    sealed interface LocalClip {
        class Text(val text: String, val html: String?, val sensitive: Boolean) : LocalClip
        class Content(val uri: Uri, val sensitive: Boolean) : LocalClip
    }

    fun applyText(text: String, html: String?, sensitive: Boolean) {
        main.post { write(if (html != null) ClipData.newHtmlText(LABEL, text, html) else ClipData.newPlainText(LABEL, text), sensitive) }
    }

    fun applyUri(uri: Uri, sensitive: Boolean) {
        main.post { write(ClipData.newUri(app.contentResolver, LABEL, uri), sensitive) }
    }

    private fun write(clip: ClipData, sensitive: Boolean) {
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        lastOwnWriteAt = SystemClock.elapsedRealtime()
        try {
            clipboard.setPrimaryClip(clip)
        } catch (e: RuntimeException) {
            // Binder caps a transaction at ~1 MB; callers route big text to Downloads, this is the backstop.
            Log.w(TAG, "clipboard write rejected", e)
        }
    }

    /** Only works while one of our windows has input focus: Android 10+ denies background reads. */
    fun read(): LocalClip? {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0 || clip.description.label?.toString() == LABEL) return null
        val sensitive = clip.description.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true
        val item = clip.getItemAt(0)
        item.uri?.let { return LocalClip.Content(it, sensitive) }
        val text = item.text?.toString()
        if (text.isNullOrEmpty()) return null
        return LocalClip.Text(text, item.htmlText, sensitive)
    }

    companion object {
        const val LABEL = Proto.REMOTE_CLIP_LABEL

        // ClipDescription.EXTRA_IS_SENSITIVE is API 33, but password managers set the same key on older versions.
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        /** Larger text can't cross Binder into the clipboard, so it's delivered as a file instead. */
        const val MAX_CLIPBOARD_TEXT_BYTES = 512 * 1024
    }
}
