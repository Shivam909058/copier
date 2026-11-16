package dev.copier

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import dev.copier.proto.Header
import dev.copier.proto.Message
import dev.copier.proto.Proto
import dev.copier.proto.ProtoException
import java.io.IOException
import java.security.GeneralSecurityException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Keeps one authenticated control connection to the paired Mac and routes everything through it.
 * A foreground service (type connectedDevice), so Android keeps the process and its socket alive.
 * Every clip and blob that crosses is recorded in [ActivityStore] for the relay timeline.
 */
class SyncService : Service() {
    private enum class State { NOT_PAIRED, SEARCHING, CONNECTED }

    private lateinit var store: Store
    private lateinit var activity: ActivityStore
    private lateinit var bridge: ClipboardBridge
    private lateinit var transfers: Transfers
    private lateinit var connectivity: ConnectivityManager
    private lateinit var discovery: Discovery
    private lateinit var ringer: Ringer
    private val main = Handler(Looper.getMainLooper())

    // ponytail: unbounded pool; fine for the handful of concurrent offers one Mac sends.
    private val workers = Executors.newCachedThreadPool()
    private val pushQueue = Executors.newSingleThreadExecutor()

    /** Small control messages that must keep their order (a notification posted, then removed). */
    private val outbox = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val wakeups = LinkedBlockingQueue<Unit>()

    @Volatile private var stopped = false
    @Volatile private var state = State.SEARCHING
    @Volatile private var control: SecureSocket? = null
    @Volatile private var address: Pair<String, Int>? = null
    @Volatile private var discovered: Pair<String, Int>? = null
    @Volatile private var lastReceivedAt = 0L
    @Volatile private var backoffMs = INITIAL_BACKOFF_MS
    @Volatile private var problem: String? = null

    // The Mac sends `bye` before sleeping. Dialing a sleeping Mac can wake it through a Bonjour Sleep
    // Proxy (Apple TV/HomePod), so after `bye` we only reconnect when its service *re*appears.
    @Volatile private var macAsleep = false
    @Volatile private var macVisibleWhileAsleep = false
    @Volatile private var macServiceName: String? = null
    @Volatile private var lastDisconnectAt = 0L

    @Volatile private var watcher: Thread? = null
    @Volatile private var logcat: Process? = null
    @Volatile private var lastFocusLaunchAt = 0L
    private var clipListenerAdded = false
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // Only delivered while we're focused. Registered because the system logs
        // "Denying clipboard access to <pkg>" each time it can't deliver it — that's our change signal.
    }

    @Volatile private var battery: Pair<Int, Boolean>? = null
    @Volatile private var statusSent: String? = null
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            battery = (level * 100 / scale) to charging
            sendStatus()
        }
    }

    // ponytail: grows by one id per screenshot for the life of the process; trivially small.
    private val pushedScreenshots = Collections.synchronizedSet(HashSet<Long>())
    private val screenshotObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            workers.execute(::sendNewScreenshots)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = kick()
    }

    val isConnected: Boolean get() = control != null
    val macName: String get() = store.pairedMac?.name ?: "Mac"

    override fun onCreate() {
        super.onCreate()
        store = Store.get(this)
        activity = ActivityStore.get(this)
        bridge = ClipboardBridge(this)
        transfers = Transfers(this, store, bridge)
        ringer = Ringer(this)
        Notifications.createChannels(this)
        startForeground(Notifications.ID_STATUS, Notifications.status(this, statusText(), latestEvent()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        instance = this
        activity.onChange = ::onActivityChanged
        discovery = Discovery(this, ::onServiceFound, ::onServiceLost)
        connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(networkCallback)
        val batteryChanged = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, batteryChanged, RECEIVER_NOT_EXPORTED) else registerReceiver(batteryReceiver, batteryChanged)
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotObserver)
        thread(name = "copier-connect") { connectLoop() }
        scheduler.scheduleWithFixedDelay(::ping, PING_INTERVAL_S, PING_INTERVAL_S, TimeUnit.SECONDS)
        refreshClipboardWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RING) ringer.stop()
        wake()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopped = true
        instance = null
        activity.onChange = null
        connectivity.unregisterNetworkCallback(networkCallback)
        unregisterReceiver(batteryReceiver)
        contentResolver.unregisterContentObserver(screenshotObserver)
        if (clipListenerAdded) getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener)
        discovery.stop()
        control?.close()
        logcat?.destroy()
        ringer.stop()
        wake()
        workers.shutdownNow()
        pushQueue.shutdownNow()
        outbox.shutdownNow()
        scheduler.shutdownNow()
        super.onDestroy()
    }

    // region Public API for this process's activities, tile and listeners

    /** Something the user did (opened the app, network changed): retry now, even if the Mac was asleep. */
    fun kick() {
        macAsleep = false
        backoffMs = INITIAL_BACKOFF_MS
        wake()
    }

    /** The pairing changed: drop the current connection and start over with whatever is stored now. */
    fun reconnect() {
        control?.close()
        kick()
    }

    fun statusText(): String {
        val base = when (state) {
            State.NOT_PAIRED -> "Not paired — open Copier to pair"
            State.SEARCHING -> if (macAsleep) "${macName} is asleep" else "Looking for $macName…"
            State.CONNECTED -> "Connected to $macName"
        }
        return problem?.let { "$base · $it" } ?: base
    }

    /** Called while one of our windows has focus, which is the only time the clipboard is readable. */
    fun sendLocalClip(context: Context, clip: ClipboardBridge.LocalClip) {
        when (clip) {
            is ClipboardBridge.LocalClip.Text -> sendText(clip.text, clip.html, clip.sensitive)
            is ClipboardBridge.LocalClip.Content -> {
                val mime = context.contentResolver.getType(clip.uri) ?: "application/octet-stream"
                val purpose = if (mime.startsWith("image/")) "clip" else "file"
                try {
                    push(listOf(Outgoing.ofUri(context, clip.uri, purpose, clip.sensitive)))
                } catch (e: IOException) {
                    Log.w(TAG, "could not open the copied item", e)
                } catch (e: SecurityException) {
                    Log.w(TAG, "no permission to read the copied item", e)
                }
            }
        }
    }

    fun sendText(text: String, html: String?, sensitive: Boolean) {
        val id = UUID.randomUUID().toString()
        activity.add(
            ActivityItem(
                id = id, fromMac = false, kind = ActivityItem.Kind.TEXT, title = "Text",
                text = if (sensitive) null else text.take(ActivityCodec.MAX_TEXT), size = text.length.toLong(),
                clip = true, sensitive = sensitive, state = ActivityItem.State.WAITING,
            ),
        )
        workers.execute {
            val link = control
            if (link == null) {
                fail(id, "$macName isn't connected")
                return@execute
            }
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val htmlBytes = html?.toByteArray(Charsets.UTF_8)
            try {
                val withHtml = htmlBytes?.let { Message(Header("clip", id = id, sensitive = sensitive, htmlBytes = it.size), it + textBytes) }
                val plain = Message(Header("clip", id = id, sensitive = sensitive), textBytes)
                when {
                    withHtml != null && fits(withHtml) -> link.send(withHtml)
                    fits(plain) -> link.send(plain)
                    else -> push(listOf(Outgoing.ofBytes("Copied text.txt", "text/plain", "clip", sensitive, textBytes, id)))
                }
                Log.i(TAG, "clip sent: ${textBytes.size} bytes, html=${htmlBytes != null}")
            } catch (e: IOException) {
                Log.w(TAG, "sending clip failed; reconnecting", e)
                fail(id, "Connection lost")
                link.close()
            }
        }
    }

    fun push(items: List<Outgoing>) {
        for (item in items) {
            if (!activity.contains(item.id)) activity.add(item.toActivity())
            pushQueue.execute {
                val mac = store.pairedMac
                val target = address
                if (mac == null || target == null || control == null) {
                    item.discard()
                    fail(item.id, "$macName isn't connected")
                    return@execute
                }
                try {
                    val gate = ProgressGate { fraction ->
                        activity.update(item.id) { it.copy(state = ActivityItem.State.TRANSFERRING, progress = fraction) }
                    }
                    transfers.push(mac, target.first, target.second, item) { sent, total -> gate.update(sent, total) }
                    activity.update(item.id) { it.copy(state = ActivityItem.State.DELIVERED, progress = 1f) }
                } catch (e: Exception) {
                    item.discard()
                    Log.w(TAG, "push failed", e)
                    fail(item.id, e.message ?: e.javaClass.simpleName)
                    Notifications.post(this, "Couldn't send ${item.name}", e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** "Open on Mac": the Mac opens the link in its default browser. */
    fun openOnMac(url: String) {
        activity.add(
            ActivityItem(id = UUID.randomUUID().toString(), fromMac = false, kind = ActivityItem.Kind.LINK, title = "Link", text = url, state = ActivityItem.State.WAITING),
        )
        sendToMac(Header("open", url = url))
    }

    /** Fire-and-forget control message, kept in order (notifications, status, links). */
    fun sendToMac(header: Header) {
        val link = control ?: return
        outbox.execute {
            try {
                link.send(Message(header))
            } catch (e: IOException) {
                link.close()
            }
        }
    }

    /** Starts or stops automatic phone → Mac clipboard sync to match the setting and permissions. */
    fun refreshClipboardWatcher() {
        val wanted = store.autoClipboard && checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        if (!wanted) {
            logcat?.destroy()
            return
        }
        if (!clipListenerAdded) {
            getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener)
            clipListenerAdded = true
        }
        if (watcher?.isAlive == true) return
        problem = null
        watcher = thread(name = "copier-logcat", isDaemon = true) { watchLogcat() }
    }

    /** The latest timeline event, for the status notification ("Photo on Mac ✓ · 10:40"). */
    fun latestEvent(): String? {
        // Someone who turned automatic copy on should hear about it the moment Android stops it.
        if (store.autoCopyWanted && !CopyWatcher.running && store.pairedMac != null) {
            return store.pausedFor?.let { "Automatic copy paused for $it. Tap to turn it back on" }
                ?: "Automatic copy is off. Tap to turn it on"
        }
        return activity.latest()?.let { "${it.summary()} · ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.time))}" }
    }

    // endregion

    // region Connection

    private fun connectLoop() {
        while (!stopped) {
            val mac = store.pairedMac
            if (mac == null) {
                setState(State.NOT_PAIRED)
                stopSelf()
                return
            }
            val target = discovered ?: if (macAsleep) null else mac.host?.let { it to mac.port }
            discovered = null
            if (target != null) {
                try {
                    val (link, ok) = SecureSocket.open(store.localDevice, mac, target.first, target.second, "control")
                    onConnected(link, ok, target)
                    readLoop(link)
                } catch (e: MacRefused) {
                    Log.w(TAG, "Mac refused the connection: ${e.code}")
                    problem = if (e.code == "unpaired") "Mac doesn't know this phone — unpair and pair again" else "Mac refused (${e.code})"
                } catch (e: IOException) {
                    Log.i(TAG, "control connection to ${target.first} ended: ${e.message}")
                } catch (e: GeneralSecurityException) {
                    Log.w(TAG, "handshake crypto failed", e)
                } finally {
                    onDisconnected()
                }
            }
            if (stopped) return
            discovery.start()
            wakeups.poll(if (macAsleep) TimeUnit.HOURS.toMillis(1) else backoffMs, TimeUnit.MILLISECONDS)
            wakeups.clear()
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun onConnected(link: SecureSocket, ok: Header, target: Pair<String, Int>) {
        discovery.stop()
        control = link
        address = target
        backoffMs = INITIAL_BACKOFF_MS
        macAsleep = false
        problem = problem?.takeIf { it.startsWith("auto clipboard") }
        lastReceivedAt = SystemClock.elapsedRealtime()
        store.rememberMac(ok.name ?: macName, target.first, target.second)
        Log.i(TAG, "connected to Mac at ${target.first}:${target.second} (app ${ok.app})")
        statusSent = null
        sendStatus()
        setState(State.CONNECTED)
    }

    private fun onDisconnected() {
        val wasConnected = control != null
        control?.close()
        control = null
        address = null
        if (wasConnected) {
            Log.i(TAG, "disconnected from Mac")
            lastDisconnectAt = SystemClock.elapsedRealtime()
            setState(State.SEARCHING)
        }
    }

    private fun readLoop(link: SecureSocket) {
        link.setReadTimeout(0) // Liveness comes from ping/pong, not socket timeouts.
        while (!stopped) {
            val message = link.receive()
            lastReceivedAt = SystemClock.elapsedRealtime()
            val header = message.header
            when (header.t) {
                "ping" -> link.send(Message(Header("pong")))
                "pong" -> Unit
                "clip" -> onRemoteClip(link, message)
                "ack" -> header.id?.let { id -> activity.update(id) { it.copy(state = ActivityItem.State.DELIVERED) } }
                "offer" -> onOffer(header)
                "notif-action" -> {
                    val key = header.key
                    val index = header.index
                    if (key != null && index != null) main.post { NotifListener.instance?.perform(key, index, header.text) }
                }
                "notif-dismiss" -> header.key?.let { key -> main.post { NotifListener.instance?.dismiss(key) } }
                "ring" -> ringer.start()
                "ring-stop" -> ringer.stop()
                "open" -> header.url?.let(::openFromMac)
                "diagnose" -> onDiagnose(header.time ?: 120)
                "bye" -> {
                    Log.i(TAG, "Mac is going to sleep")
                    macAsleep = true
                    macVisibleWhileAsleep = false
                    return
                }
                else -> Log.d(TAG, "ignoring message type '${header.t}'")
            }
        }
    }

    private fun onRemoteClip(link: SecureSocket, message: Message) {
        val htmlLength = message.header.htmlBytes ?: 0
        if (htmlLength < 0 || htmlLength > message.body.size) throw ProtoException("htmlBytes $htmlLength out of range")
        val textLength = message.body.size - htmlLength
        val sensitive = message.header.sensitive == true
        val id = message.header.id ?: UUID.randomUUID().toString()
        Log.i(TAG, "clip received: $textLength text bytes, html=${htmlLength > 0}")
        if (textLength > ClipboardBridge.MAX_CLIPBOARD_TEXT_BYTES) {
            val text = message.body.copyOfRange(htmlLength, message.body.size)
            workers.execute {
                try {
                    val uri = transfers.saveTextAsDownload(text, macName)
                    activity.add(
                        ActivityItem(
                            id = id, fromMac = true, kind = ActivityItem.Kind.FILE, title = "Copied text.txt",
                            size = text.size.toLong(), mime = "text/plain", uri = uri.toString(), state = ActivityItem.State.RECEIVED,
                        ),
                    )
                } catch (e: IOException) {
                    Log.w(TAG, "could not save large text", e)
                }
            }
        } else {
            val text = String(message.body, htmlLength, textLength, Charsets.UTF_8)
            val html = if (htmlLength > 0) String(message.body, 0, htmlLength, Charsets.UTF_8) else null
            bridge.applyText(text, html, sensitive)
            activity.add(
                ActivityItem(
                    id = id, fromMac = true, kind = ActivityItem.Kind.TEXT, title = "Text",
                    text = if (sensitive) null else text.take(ActivityCodec.MAX_TEXT), size = textLength.toLong(),
                    clip = true, sensitive = sensitive, state = ActivityItem.State.RECEIVED,
                ),
            )
        }
        message.header.id?.let { link.send(Message(Header("ack", id = it))) }
    }

    private fun onOffer(offer: Header) {
        val mac = store.pairedMac ?: return
        val target = address ?: return
        val id = offer.id ?: return
        val mime = offer.mime ?: "application/octet-stream"
        val kind = when {
            mime.startsWith("image/") -> ActivityItem.Kind.IMAGE
            offer.purpose == "clip" && mime.startsWith("text/") -> ActivityItem.Kind.TEXT
            else -> ActivityItem.Kind.FILE
        }
        activity.add(
            ActivityItem(
                id = id, fromMac = true, kind = kind, title = offer.name ?: "file", size = offer.size, mime = mime,
                clip = offer.purpose == "clip", sensitive = offer.sensitive == true, state = ActivityItem.State.WAITING,
            ),
        )
        workers.execute {
            try {
                val gate = ProgressGate { fraction ->
                    activity.update(id) { it.copy(state = ActivityItem.State.TRANSFERRING, progress = fraction) }
                }
                val landed = transfers.pull(mac, target.first, target.second, offer) { done, total -> gate.update(done, total) }
                activity.update(id) { item ->
                    item.copy(
                        state = ActivityItem.State.RECEIVED, progress = 1f, kind = landed.kind, clip = landed.clip,
                        title = if (landed.clip) item.title else landed.name, uri = landed.uri?.toString(),
                        text = if (item.sensitive) null else landed.text?.take(ActivityCodec.MAX_TEXT),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "pull failed", e)
                fail(id, e.message ?: e.javaClass.simpleName)
                if (offer.purpose == "file") Notifications.post(this, "Couldn't receive ${offer.name ?: "file"}", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** `open` from the Mac. Allowed to start activities from the background while the accessibility
     * service is bound; otherwise fall back to a notification the user can tap. */
    private fun openFromMac(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        activity.add(
            ActivityItem(id = UUID.randomUUID().toString(), fromMac = true, kind = ActivityItem.Kind.LINK, title = "Link", text = url, state = ActivityItem.State.RECEIVED),
        )
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post {
            try {
                startActivity(view)
            } catch (e: ActivityNotFoundException) {
                Notifications.post(this, "Tap to open ${uri.host}", url, PendingIntent.getActivity(this, url.hashCode(), view, PendingIntent.FLAG_IMMUTABLE))
            } catch (e: SecurityException) {
                Notifications.post(this, "Tap to open ${uri.host}", url, PendingIntent.getActivity(this, url.hashCode(), view, PendingIntent.FLAG_IMMUTABLE))
            }
        }
    }

    /** Battery, and whether automatic copy is really running — the Mac shows both. */
    private fun sendStatus() {
        if (control == null) return
        val watching = CopyWatcher.running
        val reason = if (watching) null else store.pausedFor?.let { "paused for $it" }
        val current = "${battery?.first}|${battery?.second}|$watching|$reason"
        if (current == statusSent) return
        statusSent = current
        sendToMac(Header("status", battery = battery?.first, charging = battery?.second, watcher = watching, msg = reason))
    }

    /** The copy watcher started or stopped: tell the Mac and refresh what the user sees here. */
    fun watcherChanged() {
        statusSent = null
        sendStatus()
        main.post {
            Notifications.updateStatus(this, statusText(), latestEvent())
            listener?.invoke(statusText())
        }
    }

    /** A diagnostic line for the Mac's log and `copier diagnose`. Never clipboard contents. */
    fun diag(text: String) = sendToMac(Header("diag", text = text))

    private fun onDiagnose(seconds: Long) {
        CopyWatcher.diagnose(seconds)
        val automatic = when {
            CopyWatcher.running -> "running"
            CopyWatcher.enabledInSettings(this) -> "ON in Settings but NOT running (Android stopped it: turn it off and on again)"
            store.pausedFor != null -> "paused for ${store.pausedFor}"
            else -> "off"
        }
        diag("phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), Copier ${store.appVersion}")
        diag("automatic copy: $automatic; menu checks ${if (store.thoroughCopy) "on" else "off"}; overlay reads ${if (store.overlayBlocked) "blocked on this phone" else "ok so far"}")
        diag(if (CopyWatcher.running) "ready: copy something on the phone now" else "automatic copy isn't running, so nothing will be detected")
    }

    /** New screenshots (in a "Screenshots" folder, added in the last 30 s) go to the Mac. */
    private fun sendNewScreenshots() {
        if (!store.screenshots || control == null || !canReadImages(this)) return
        val since = System.currentTimeMillis() / 1000 - 30
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH)
        val fresh = try {
            contentResolver.query(images, columns, "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(since.toString()), null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1).orEmpty().contains("screenshot", ignoreCase = true)) add(cursor.getLong(0))
                    }
                }
            }.orEmpty()
        } catch (e: SecurityException) {
            return
        }
        for (id in fresh) {
            if (!pushedScreenshots.add(id)) continue
            try {
                push(listOf(Outgoing.ofUri(this, ContentUris.withAppendedId(images, id), "screenshot", sensitive = false)))
            } catch (e: IOException) {
                pushedScreenshots.remove(id)
                Log.w(TAG, "could not open a new screenshot", e)
            } catch (e: SecurityException) {
                pushedScreenshots.remove(id)
            }
        }
    }

    private fun fail(id: String, reason: String) =
        activity.update(id) { it.copy(state = ActivityItem.State.FAILED, failure = reason) }

    private fun onActivityChanged(stateChanged: Boolean) {
        main.post {
            if (stateChanged) Notifications.updateStatus(this, statusText(), latestEvent())
            listener?.invoke(statusText())
        }
    }

    private fun ping() {
        val link = control ?: return
        val sentAt = SystemClock.elapsedRealtime()
        try {
            link.send(Message(Header("ping")))
        } catch (e: IOException) {
            link.close()
            return
        }
        scheduler.schedule({
            if (control === link && lastReceivedAt < sentAt) {
                Log.i(TAG, "no pong within ${PONG_TIMEOUT_S}s; reconnecting")
                link.close()
            }
        }, PONG_TIMEOUT_S, TimeUnit.SECONDS)
    }

    private fun onServiceFound(service: Discovery.MacService) {
        val mac = store.pairedMac ?: return
        if (service.id != mac.id) return
        macServiceName = service.name
        if (macAsleep) {
            if (macVisibleWhileAsleep) return
            // NSD replays known services right after discovery restarts; a sighting that soon after
            // `bye` is the pre-sleep or sleep-proxied record, not the Mac waking up.
            if (SystemClock.elapsedRealtime() - lastDisconnectAt < STALE_SIGHTING_MS) {
                macVisibleWhileAsleep = true
                return
            }
        }
        macAsleep = false
        discovered = service.host to service.port
        wake()
    }

    private fun onServiceLost(name: String) {
        if (name == macServiceName) macVisibleWhileAsleep = false
    }

    private fun wake() {
        wakeups.offer(Unit)
    }

    private fun setState(next: State) {
        state = next
        val text = statusText()
        Notifications.updateStatus(this, text, latestEvent())
        main.post { listener?.invoke(text) }
    }

    // endregion

    // region Automatic clipboard (logcat watcher)

    private fun watchLogcat() {
        var failures = 0
        while (!stopped && store.autoClipboard) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val process = ProcessBuilder("logcat", "-T", since, "ClipboardService:E", "*:S").redirectErrorStream(true).start()
                logcat = process
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (stopped) break
                        if (line.contains(packageName)) onForeignClipboardChange()
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "logcat watcher failed", e)
            }
            if (stopped || !store.autoClipboard) return
            failures = if (SystemClock.elapsedRealtime() - startedAt > 60_000) 0 else failures + 1
            if (failures >= 5) {
                problem = "auto clipboard unavailable — open Copier to retry"
                setState(state)
                return
            }
            SystemClock.sleep(1_000L shl failures)
        }
    }

    private fun onForeignClipboardChange() {
        val now = SystemClock.elapsedRealtime()
        // Our own writes produce the same log line, and one copy can log several lines.
        if (now - bridge.lastOwnWriteAt < OWN_WRITE_GRACE_MS || now - lastFocusLaunchAt < 500) return
        if (control == null) return
        lastFocusLaunchAt = now
        startActivity(FocusActivity.intent(this, manual = false))
    }

    // endregion

    private fun fits(message: Message) = message.encode().size <= Proto.MAX_MESSAGE

    companion object {
        const val ACTION_STOP_RING = "dev.copier.STOP_RING"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val PING_INTERVAL_S = 60L
        private const val PONG_TIMEOUT_S = 20L
        private const val OWN_WRITE_GRACE_MS = 1_500L
        private const val STALE_SIGHTING_MS = 5_000L

        @SuppressLint("StaticFieldLeak") // Cleared in onDestroy; lets this process's activities reach the live service.
        @Volatile
        var instance: SyncService? = null
            private set

        /** MainActivity's refresh hook; set in onResume, cleared in onPause. */
        @Volatile
        var listener: ((String) -> Unit)? = null

        fun canReadImages(context: Context): Boolean =
            context.checkSelfPermission(
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
    }
}
