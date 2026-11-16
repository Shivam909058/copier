package dev.copier

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import java.io.IOException

/**
 * Invisible one-frame activity. Android 10+ only lets the focused app read the clipboard, so every
 * phone → Mac clipboard path (tile, notification action, logcat watcher) briefly shows this window.
 */
class FocusActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || isFinishing) return
        val read = ClipboardBridge(this).readDetailed()
        if (intent.getBooleanExtra(EXTRA_MANUAL, false)) {
            sendManually(read)
        } else if (CopyWatcher.instance?.onActivityRead(read) != true) {
            AutoCopy.offer(this, read) // started by the logcat watcher
        }
        finish()
    }

    /** Tile or notification button: the user asked, so send even a clip that was sent before. */
    private fun sendManually(read: ClipboardBridge.Read) {
        val service = SyncService.instance
        when {
            service == null || !service.isConnected -> toast("Mac isn't connected")
            read !is ClipboardBridge.Read.Clip -> toast("Nothing new on the clipboard")
            else -> {
                AutoCopy.remember(read)
                service.sendLocalClip(this, read.clip)
                toast("Clipboard sent to ${service.macName}")
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_MANUAL = "manual"

        fun intent(context: Context, manual: Boolean): Intent =
            Intent(context, FocusActivity::class.java)
                .putExtra(EXTRA_MANUAL, manual)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}

/** "Send to Mac" from the share sheet (text, one file, many files) or the text-selection menu. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = SyncService.instance
        if (service == null || !service.isConnected) {
            toast("Mac isn't connected — open Copier")
            finish()
            return
        }
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            // PROCESS_TEXT is the text-selection menu; SEND is the share sheet.
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            text?.toString()?.let { shared ->
                // "Open on Mac" (an alias of this activity) opens links; plain text still becomes a clip.
                val url = if (intent.component?.className == OPEN_ON_MAC) firstWebUrl(shared) else null
                if (url != null) {
                    service.openOnMac(url)
                    toast("Opening on ${service.macName}")
                } else {
                    service.sendText(shared, null, sensitive = false)
                    toast("Sent to ${service.macName}")
                }
            }
        } else {
            // Open every descriptor now: the URI grant belongs to this activity and is checked at open time.
            val items = uris.mapNotNull { uri ->
                try {
                    Outgoing.ofUri(this, uri, "file", sensitive = false)
                } catch (e: IOException) {
                    Log.w(TAG, "could not open a shared item", e)
                    null
                } catch (e: SecurityException) {
                    Log.w(TAG, "no permission for a shared item", e)
                    null
                }
            }
            service.push(items)
            toast(if (items.size == 1) "Sending to ${service.macName}…" else "Sending ${items.size} files to ${service.macName}…")
        }
        finish()
    }

    private fun sharedUris(intent: Intent): List<Uri> =
        when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                },
            )
            Intent.ACTION_SEND_MULTIPLE ->
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
            else -> emptyList()
        }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val OPEN_ON_MAC = "dev.copier.OpenOnMacActivity"
    }
}

/** The first http(s) URL in shared text ("Great read https://…" from many apps), minus trailing punctuation. */
fun firstWebUrl(text: String): String? =
    Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')

/**
 * Puts "Send to <Mac>" in the share sheet's top (Direct Share) row, so sending a file is two taps:
 * Share, then the Mac. Matches the `share-target` in res/xml/shortcuts.xml.
 */
fun publishShareShortcut(context: Context, macName: String) {
    val builder = ShortcutInfo.Builder(context, "send-to-mac")
        .setShortLabel(macName)
        .setLongLabel("Send to $macName")
        .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND))
        .setCategories(setOf("dev.copier.category.SEND_TO_MAC"))
    if (Build.VERSION.SDK_INT >= 30) builder.setLongLived(true)
    try {
        context.getSystemService(ShortcutManager::class.java).addDynamicShortcuts(listOf(builder.build()))
    } catch (e: IllegalStateException) {
        Log.w(TAG, "share shortcut not published (user locked)", e)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "share shortcut not published", e)
    }
}

/** Quick Settings tile: one tap sends the current clipboard, no setup or special permission needed. */
class ClipTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = FocusActivity.intent(this, manual = true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") // The Intent overload is the only one before API 34.
            startActivityAndCollapse(intent)
        }
    }
}

/** Restarts the sync service after a reboot or an app update, when a Mac is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (relevant && Store.get(context).pairedMac != null) {
            context.startForegroundService(Intent(context, SyncService::class.java))
        }
    }
}
