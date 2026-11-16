package dev.copier

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.copier.proto.Crypto
import dev.copier.proto.Pairing
import dev.copier.proto.Proto
import dev.copier.proto.QrPayload
import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Pairing (QR deep link first, 6-digit code as fallback), post-pairing onboarding made only of system
 * dialogs, then the relay screen: everything that crossed between this phone and the Mac.
 */
class MainActivity : Activity() {
    private enum class Onboarding { NOTIFICATIONS, SCREENSHOTS, BATTERY, BACKGROUND, DONE, COMPLETE }

    private lateinit var store: Store
    private lateinit var activityStore: ActivityStore
    private lateinit var relay: RelayViews
    private lateinit var root: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var renderPending = false
    private var setupExpanded: Boolean? = null
    private var shown = PAGE
    private var discovery: Discovery? = null
    private val found = LinkedHashMap<String, Discovery.MacService>()
    private var pairingStatus: String? = null
    private var resumed = false

    private val step: Onboarding
        get() = Onboarding.entries.getOrElse(store.onboardingStep) { Onboarding.COMPLETE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        activityStore = ActivityStore.get(this)
        relay = RelayViews(this)
        Notifications.createChannels(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        if (Build.VERSION.SDK_INT >= 30) {
            // Targeting API 36 forces edge-to-edge on Android 15+, so keep content clear of the system bars.
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        // A recreated activity gets the original intent again; its one-time token is already used.
        if (savedInstanceState == null) handlePairingLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingLink(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        SyncService.listener = { scheduleRender() }
        val mac = store.pairedMac
        if (mac != null) {
            startForegroundService(Intent(this, SyncService::class.java))
            publishShareShortcut(this, mac.name)
            SyncService.instance?.kick()
            SyncService.instance?.refreshClipboardWatcher()
            advanceOnboarding()
        } else if (pairingStatus == null) {
            startDiscovery()
        }
        render()
    }

    override fun onPause() {
        resumed = false
        SyncService.listener = null
        stopDiscovery()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (resumed) advanceOnboarding()
        render()
    }

    @Deprecated("Platform Activity results; there's no AndroidX here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null || (requestCode != REQUEST_PHOTOS && requestCode != REQUEST_FILES)) return
        val clips = data.clipData
        val uris = if (clips != null) List(clips.itemCount) { clips.getItemAt(it).uri } else listOfNotNull(data.data)
        sendPicked(uris)
    }

    /** Coalesces bursts of progress ticks into one rebuild every 150 ms. */
    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        ui.postDelayed({
            renderPending = false
            if (resumed) render()
        }, 150)
    }

    // region Pairing

    private fun handlePairingLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "copier" || uri.host != "pair") return
        val payload = try {
            QrPayload.parse(uri.encodedQuery.orEmpty())
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "invalid pairing link: ${e.message}")
            toast("That isn't a valid Copier QR code. Show a new one on the Mac.")
            return
        }
        pairWithQr(payload)
    }

    /**
     * PROTOCOL.md § QR pairing. Links can come from any web page, so the user confirms the Mac's real
     * name (from pair-ack) before anything is revealed: one tap, and no drive-by pairing.
     */
    private fun pairWithQr(payload: QrPayload) {
        if (pairingStatus != null) return
        stopDiscovery()
        showPairingStatus("Connecting to your Mac…")
        val decision = LinkedBlockingQueue<Boolean>()
        thread(name = "copier-qr-pair") {
            val result = try {
                openQrSession(payload).use { session ->
                    session.exchangeCommit()
                    runOnUiThread { confirmQrPairing(session.macName, decision) }
                    if (decision.poll(PAIR_TIMEOUT_MS, TimeUnit.MILLISECONDS) != true) {
                        bestEffort { session.sendVerdict(false) }
                        return@use "Pairing cancelled"
                    }
                    session.revealWithQr(payload.macPublicKey, payload.token)
                    if (session.awaitMacVerdict()) {
                        store.pairedMac = session.pairedMac()
                        store.onboardingStep = Onboarding.NOTIFICATIONS.ordinal
                        "Paired with ${session.macName}"
                    } else {
                        "The Mac cancelled pairing"
                    }
                }
            } catch (e: MacRefused) {
                refusalText(e)
            } catch (e: IOException) {
                Log.w(TAG, "QR pairing failed", e)
                "Couldn't pair (${e.message}). Show a new QR code on the Mac and scan it again."
            }
            runOnUiThread { finishPairing(result) }
        }
    }

    /** The QR lists the Mac's addresses; if none answers (address changed, other subnet), ask Bonjour. */
    private fun openQrSession(payload: QrPayload): PairingSession {
        var lastError: IOException? = null
        for (host in payload.hosts) {
            try {
                return PairingSession(store.localDevice, host, payload.port)
            } catch (e: IOException) {
                Log.i(TAG, "QR address $host unreachable: ${e.message}")
                lastError = e
            }
        }
        runOnUiThread { showPairingStatus("Looking for your Mac on this Wi-Fi…") }
        val mac = findMac(Crypto.deviceId(payload.macPublicKey))
            ?: throw lastError ?: IOException("the Mac isn't on this network")
        return PairingSession(store.localDevice, mac.host, mac.port)
    }

    private fun findMac(id: String): Discovery.MacService? {
        val result = LinkedBlockingQueue<Discovery.MacService>()
        val finder = Discovery(this, onFound = { if (it.id == id) result.offer(it) })
        finder.start()
        return try {
            result.poll(QR_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            finder.stop()
        }
    }

    private fun confirmQrPairing(macName: String, decision: BlockingQueue<Boolean>) {
        if (isFinishing || isDestroyed) {
            decision.offer(false)
            return
        }
        val current = store.pairedMac
        AlertDialog.Builder(this)
            .setTitle("Pair with $macName?")
            .setMessage(
                if (current != null) "This replaces the pairing with ${current.name}."
                else "Clipboard and files will sync between this phone and $macName.",
            )
            .setCancelable(false)
            .setPositiveButton("Pair") { _, _ -> decision.offer(true) }
            .setNegativeButton("Cancel") { _, _ -> decision.offer(false) }
            .show()
    }

    /** Fallback when there's no QR: both users compare a 6-digit code (PROTOCOL.md § Pairing). */
    private fun pairWithCode(host: String, port: Int) {
        if (pairingStatus != null) return
        stopDiscovery()
        showPairingStatus("Connecting to your Mac…")
        val events = LinkedBlockingQueue<String>()
        var dialog: AlertDialog? = null
        thread(name = "copier-pair") {
            val result = try {
                PairingSession(store.localDevice, host, port).use { session ->
                    session.exchangeCommit()
                    val code = session.revealWithCode()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            dialog = AlertDialog.Builder(this)
                                .setTitle("Pair with ${session.macName}?")
                                .setMessage("Make sure your Mac shows the same code:\n\n${Pairing.display(code)}")
                                .setCancelable(false)
                                .setPositiveButton("Pair") { _, _ -> events.offer(LOCAL_OK) }
                                .setNegativeButton("Cancel") { _, _ -> events.offer(LOCAL_CANCEL) }
                                .show()
                        }
                    }
                    thread(name = "copier-pair-read", isDaemon = true) {
                        events.offer(
                            try {
                                if (session.awaitMacVerdict()) REMOTE_OK else REMOTE_CANCEL
                            } catch (e: IOException) {
                                REMOTE_GONE
                            },
                        )
                    }
                    awaitBothConfirmations(session, events)
                }
            } catch (e: MacRefused) {
                refusalText(e)
            } catch (e: IOException) {
                "Couldn't reach the Mac: ${e.message}"
            }
            runOnUiThread {
                dialog?.dismiss()
                finishPairing(result)
            }
        }
    }

    /** Stores the Mac only after our user said yes AND the Mac sent pair-ok. */
    private fun awaitBothConfirmations(session: PairingSession, events: BlockingQueue<String>): String {
        var localOk = false
        var remoteOk = false
        val deadline = SystemClock.elapsedRealtime() + PAIR_TIMEOUT_MS
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            when (if (remaining > 0) events.poll(remaining, TimeUnit.MILLISECONDS) else null) {
                LOCAL_OK -> {
                    localOk = true
                    session.sendVerdict(true)
                }
                LOCAL_CANCEL -> {
                    session.sendVerdict(false)
                    return "Pairing cancelled"
                }
                REMOTE_OK -> remoteOk = true
                REMOTE_CANCEL -> return "Pairing was cancelled on the Mac"
                REMOTE_GONE -> return "The connection to the Mac was lost"
                null -> {
                    bestEffort { session.sendVerdict(false) }
                    return "Pairing timed out"
                }
            }
            if (localOk && remoteOk) {
                store.pairedMac = session.pairedMac()
                store.onboardingStep = Onboarding.NOTIFICATIONS.ordinal
                return "Paired with ${session.macName}"
            }
        }
    }

    private fun finishPairing(result: String) {
        pairingStatus = null
        toast(result)
        if (store.pairedMac != null) {
            startForegroundService(Intent(this, SyncService::class.java))
            SyncService.instance?.reconnect()
            if (resumed) advanceOnboarding()
        } else if (resumed) {
            startDiscovery()
        }
        render()
    }

    private fun refusalText(e: MacRefused) =
        if (e.code == "busy") "The Mac is pairing with another phone. Try again in a minute." else "The Mac refused (${e.code})"

    private fun showPairingStatus(text: String) {
        pairingStatus = text
        render()
    }

    // endregion

    // region Onboarding (system dialogs only)

    /** Each step is marked done before its dialog opens, so coming back resumes at the next one. */
    private fun advanceOnboarding() {
        when (step) {
            Onboarding.NOTIFICATIONS -> {
                store.onboardingStep = Onboarding.SCREENSHOTS.ordinal
                if (Build.VERSION.SDK_INT >= 33 && !Notifications.allowed(this)) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                } else {
                    advanceOnboarding()
                }
            }
            Onboarding.SCREENSHOTS -> {
                store.onboardingStep = Onboarding.BATTERY.ordinal
                if (!SyncService.canReadImages(this)) requestImageAccess() else advanceOnboarding()
            }
            Onboarding.BATTERY -> {
                store.onboardingStep = Onboarding.BACKGROUND.ordinal
                if (!ignoringBatteryOptimizations()) requestBatteryExemption()
            }
            Onboarding.BACKGROUND, Onboarding.DONE, Onboarding.COMPLETE -> Unit // Screens drawn by render().
        }
    }

    private fun ignoringBatteryOptimizations() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife") // Sideloaded utility; a persistent connection is the whole point.
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Screenshots → Mac needs every photo; Android 14+ also offers "selected photos", which isn't enough. */
    private fun requestImageAccess() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        requestPermissions(arrayOf(permission), REQUEST_IMAGES)
    }

    private fun sendHint(automatic: Boolean) =
        if (automatic) {
            "Just tap Copy in any app — it's on your Mac a moment later. Photos and files: Share → Send to Mac."
        } else {
            "Until automatic sending is on: select text → “Send to Mac”, or after copying tap the Copier tile or “Send clipboard” in Copier's notification. Photos and files: Share → Send to Mac."
        }

    /** Android 13+ can add our tile with one system prompt instead of the edit-tiles dance. */
    private fun requestTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, ClipTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_stat),
                mainExecutor,
            ) { result -> Log.i(TAG, "add-tile prompt result: $result") }
        }
    }

    // endregion

    // region Screens

    private fun render() {
        root.removeAllViews()
        val mac = store.pairedMac
        val status = pairingStatus
        when {
            status != null -> {
                title("Copier")
                text(status)
            }
            mac == null -> {
                title("Copier")
                renderUnpaired()
            }
            step == Onboarding.BACKGROUND -> {
                title("Copier")
                renderBackgroundHint()
            }
            step == Onboarding.DONE -> {
                title("Copier")
                renderDone(mac)
            }
            else -> renderRelay(mac)
        }
    }

    private fun renderUnpaired() {
        text("On your Mac, open the Copier menu and choose “Add Phone”, then scan the QR code with this phone's camera.")
        section("No QR code? Pick your Mac")
        text(if (found.isEmpty()) "Looking for Macs on this Wi-Fi…" else "You'll check that both screens show the same 6-digit code.")
        for (mac in found.values) button(mac.name) { pairWithCode(mac.host, mac.port) }
        button("Enter the Mac's IP address") { askForAddress() }
    }

    private fun renderBackgroundHint() {
        section("Keep Copier connected")
        text("Realme (ColorOS) and some other phones pause apps in the background. In App info → Battery usage, turn on “Allow background activity”, and “Auto launch” if you see it.")
        button("Open App info") {
            store.onboardingStep = Onboarding.DONE.ordinal
            openAppInfo()
        }
        button("Skip") {
            store.onboardingStep = Onboarding.DONE.ordinal
            render()
        }
    }

    private fun renderDone(mac: PairedMac) {
        section("You're all set")
        text("Copy anything on ${mac.name} and it's on this phone's clipboard a moment later.")
        section("Make phone → Mac automatic")
        text(AUTO_HINT)
        button("Turn on automatic sending") { openAccessibilitySettings() }
        section("See this phone's notifications on ${mac.name}")
        button("Turn on notifications on Mac") { openNotificationAccessSettings() }
        text(sendHint(CopyWatcher.running))
        if (Build.VERSION.SDK_INT >= 33) button("Add the Quick Settings tile") { requestTile() }
        button("Done") {
            store.onboardingStep = Onboarding.COMPLETE.ordinal
            render()
        }
    }

    /** The relay screen: header with options, send buttons, anything that needs fixing, the timeline. */
    private fun renderRelay(mac: PairedMac) {
        val service = SyncService.instance
        add(relay.header(mac.name, service?.statusText() ?: "Starting…", service?.isConnected == true) { showMenu(it, mac) })
        add(relay.actionRow(onPhotos = ::pickPhotos, onFiles = ::pickFiles))
        renderSetup(mac)
        val items = activityStore.snapshot()
        if (items.isEmpty()) {
            add(relay.empty(mac.name))
        } else {
            add(relay.timeline(items.take(shown), activityStore::thumbFile, ::canResend, relayActions))
            if (items.size > shown) {
                button("Show older") {
                    shown += PAGE
                    render()
                }
            }
        }
    }

    private fun showMenu(anchor: View, mac: PairedMac) {
        PopupMenu(this, anchor).apply {
            menu.add("Setup and permissions").setOnMenuItemClickListener {
                setupExpanded = true
                render()
                true
            }
            menu.add("Pause for payment apps…").setOnMenuItemClickListener {
                choosePauseApps()
                true
            }
            menu.add("Clear history…").setOnMenuItemClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Clear the timeline on this phone? Files stay where they were saved.")
                    .setPositiveButton("Clear") { _, _ -> activityStore.clear() }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            menu.add("Unpair ${mac.name}…").setOnMenuItemClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Unpair ${mac.name}? You'll need to pair again to sync.")
                    .setPositiveButton("Unpair") { _, _ ->
                        store.pairedMac = null
                        getSystemService(ShortcutManager::class.java).removeAllDynamicShortcuts()
                        SyncService.instance?.reconnect()
                        startDiscovery()
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }.show()
    }

    /** Which apps automatic copy steps aside for (they refuse to run next to an accessibility service). */
    private fun choosePauseApps() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val paused = store.pauseApps
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedWith(compareBy({ it.first !in paused }, { it.second.lowercase() }))
        val checked = BooleanArray(apps.size) { apps[it].first in paused }
        AlertDialog.Builder(this)
            .setTitle("Pause automatic copy in")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, index, isChecked -> checked[index] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val installed = apps.map { it.first }.toSet()
                val chosen = apps.filterIndexed { index, _ -> checked[index] }.map { it.first }
                store.pauseApps = (paused - installed) + chosen // keep known apps that aren't installed yet
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shown above the timeline while something needs attention, or when opened from the menu.
     * Only what sync depends on counts as "to fix"; optional features are listed but never nag.
     */
    private fun renderSetup(mac: PairedMac) {
        val watching = CopyWatcher.running
        val autoCopyStopped = store.autoCopyWanted && !watching
        val mirroring = NotifListener.enabled(this)
        val photos = SyncService.canReadImages(this)
        val notifications = Notifications.allowed(this)
        val battery = ignoringBatteryOptimizations()
        val toFix = listOf(!notifications, !battery, autoCopyStopped).count { it }
        if (toFix == 0 && setupExpanded != true) return
        val expanded = setupExpanded ?: true
        section(
            when (toFix) {
                0 -> "Setup ✓ (tap to hide)"
                1 -> "Setup: 1 thing to fix"
                else -> "Setup: $toFix things to fix"
            },
        ).setOnClickListener {
            setupExpanded = if (toFix == 0) null else !expanded
            render()
        }
        if (!expanded) return

        check("Automatic copy to ${mac.name}", watching, "Turn on") { openAccessibilitySettings() }
        when {
            watching -> switch("Also check after menus close (catches Copy inside menus)", store.thoroughCopy) {
                store.thoroughCopy = it
                CopyWatcher.setThorough(it)
            }
            CopyWatcher.enabledInSettings(this) ->
                text("Android stopped it even though Accessibility shows it as on. Turn “Copier — send copies to Mac” off and on again. To keep it running, lock Copier in the recent-apps screen.")
            store.pausedFor != null ->
                text("Paused so ${store.pausedFor} could open. Turn “Copier — send copies to Mac” back on in Accessibility.")
            else -> {
                text(AUTO_HINT)
                text("Payment and banking apps won't open while this is on. Copier turns it off when you open one (⋮ → Pause for payment apps) and reminds you to turn it back on.")
                button("Open App info (for restricted settings)") { openAppInfo() }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            check("Notifications from Copier", notifications, "Allow") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        }
        check("Battery optimization off", battery, "Fix") { requestBatteryExemption() }
        button("Background activity settings") { openAppInfo() }
        check("Notifications on ${mac.name}", mirroring, "Turn on") { openNotificationAccessSettings() }
        check("Screenshots to ${mac.name}", photos, "Allow") { requestImageAccess() }
        if (photos) {
            switch("Send new screenshots to ${mac.name}", store.screenshots) { store.screenshots = it }
        } else {
            text("When Android asks, choose “Allow all”: “Select photos” can't see new screenshots.")
        }
        if (Build.VERSION.SDK_INT >= 33) button("Add the Quick Settings tile") { requestTile() }
        text(sendHint(watching))
        // Auto mode only exists if READ_LOGS was granted out of band; never a setup step.
        if (checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
            switch("Send my clipboard to ${mac.name} automatically (logcat)", store.autoClipboard) {
                store.autoClipboard = it
                SyncService.instance?.refreshClipboardWatcher()
            }
        }
    }

    // endregion

    // region Relay actions

    private val relayActions by lazy {
        RelayViews.Actions(
            copy = { item ->
                item.text?.let {
                    ClipboardBridge(this).applyText(it, null, item.sensitive) // tagged, so it isn't echoed back
                    toast("Copied")
                }
            },
            open = ::openItem,
            share = ::shareItem,
            sendAgain = ::sendAgain,
            remove = ::confirmRemove,
        )
    }

    private fun canResend(item: ActivityItem) =
        !item.fromMac && item.uri != null && item.kind != ActivityItem.Kind.TEXT && item.kind != ActivityItem.Kind.LINK

    private fun openItem(item: ActivityItem) {
        val uri = (if (item.kind == ActivityItem.Kind.LINK) item.text else item.uri)?.let(Uri::parse) ?: return
        val view = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (item.kind == ActivityItem.Kind.LINK) view.data = uri else view.setDataAndType(uri, item.mime ?: "*/*")
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast("No app on this phone can open it")
        } catch (e: SecurityException) {
            toast("It isn't available any more")
        }
    }

    private fun shareItem(item: ActivityItem) {
        val uri = item.uri?.let(Uri::parse) ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mime ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, item.title))
    }

    private fun sendAgain(item: ActivityItem) {
        val uri = item.uri?.let(Uri::parse) ?: return
        val service = connectedService() ?: return
        val purpose = when {
            item.title == "Screenshot" -> "screenshot"
            item.clip -> "clip"
            else -> "file"
        }
        try {
            service.push(listOf(Outgoing.ofUri(this, uri, purpose, item.sensitive)))
            activityStore.remove(item.id)
        } catch (e: IOException) {
            toast("Copier can't read this any more. Share it again.")
        } catch (e: SecurityException) {
            toast("Copier can't read this any more. Share it again.")
        }
    }

    private fun confirmRemove(item: ActivityItem) {
        AlertDialog.Builder(this)
            .setMessage("Remove this from the timeline?")
            .setPositiveButton("Remove") { _, _ -> activityStore.remove(item.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickPhotos() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        launch(intent, REQUEST_PHOTOS)
    }

    private fun pickFiles() =
        launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            REQUEST_FILES,
        )

    @Suppress("DEPRECATION") // Platform Activity: there are no AndroidX result APIs here.
    private fun launch(intent: Intent, request: Int) {
        try {
            startActivityForResult(intent, request)
        } catch (e: ActivityNotFoundException) {
            toast("This phone has no picker for that")
        }
    }

    /** Opens every pick while this activity still holds the grant; thumbnails make it worth a thread. */
    private fun sendPicked(uris: List<Uri>) {
        val service = connectedService() ?: return
        thread(name = "copier-pick") {
            val items = uris.mapNotNull { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) // keeps "Send again" possible
                } catch (e: SecurityException) {
                    // Not persistable (e.g. some pickers); the one-shot grant is enough to send now.
                }
                try {
                    Outgoing.ofUri(this, uri, "file", sensitive = false)
                } catch (e: IOException) {
                    null
                } catch (e: SecurityException) {
                    null
                }
            }
            service.push(items)
            if (items.size < uris.size) runOnUiThread { toast("Some items couldn't be read") }
        }
    }

    private fun connectedService(): SyncService? {
        val service = SyncService.instance
        if (service == null || !service.isConnected) {
            toast("${store.pairedMac?.name ?: "Your Mac"} isn't connected")
            return null
        }
        return service
    }

    private fun askForAddress() {
        val input = EditText(this).apply {
            hint = "192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Mac's IP address")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val text = input.text.toString().trim()
                val host = text.substringBefore(':')
                val port = text.substringAfter(':', "").toIntOrNull() ?: Proto.PORT
                if (host.isNotEmpty()) pairWithCode(host, port)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // endregion

    // region Discovery for the unpaired screen

    private fun startDiscovery() {
        if (discovery != null) return
        discovery = Discovery(
            this,
            onFound = { mac -> runOnUiThread { found[mac.name] = mac; render() } },
            onLost = { name -> runOnUiThread { found.remove(name); render() } },
        ).also { it.start() }
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
    }

    // endregion

    // region Tiny view helpers

    private fun title(text: String) = add(TextView(this).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large)
        setPadding(0, 0, 0, dp(16))
    })

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
        setPadding(0, dp(24), 0, dp(8))
    }.also(::add)

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = add(Switch(this).apply {
        text = label
        isChecked = checked
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    })

    private fun text(text: String) = add(TextView(this).apply {
        this.text = text
        setPadding(0, dp(4), 0, dp(4))
    })

    private fun button(label: String, onClick: () -> Unit) = add(Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    })

    private fun check(label: String, ok: Boolean, action: String, onAction: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = "${if (ok) "✓" else "•"}  $label"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (!ok) row.addView(Button(this).apply {
            text = action
            setOnClickListener { onAction() }
        })
        add(row)
    }

    private fun add(view: View) = root.addView(view)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            // The connection is ending anyway.
        }
    }

    // endregion

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val REQUEST_IMAGES = 2
        const val REQUEST_PHOTOS = 3
        const val REQUEST_FILES = 4
        const val PAGE = 60
        const val QR_DISCOVERY_TIMEOUT_MS = 10_000L
        const val AUTO_HINT = "In Accessibility, open “Copier — send copies to Mac” and turn it on. If it's greyed out, open App info → ⋮ (top right) → Allow restricted settings, then try again."
        const val LOCAL_OK = "local-ok"
        const val LOCAL_CANCEL = "local-cancel"
        const val REMOTE_OK = "remote-ok"
        const val REMOTE_CANCEL = "remote-cancel"
        const val REMOTE_GONE = "remote-gone"
    }
}
