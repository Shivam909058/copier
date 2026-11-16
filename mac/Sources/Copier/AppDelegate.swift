import AppKit
import CopierCore
import ServiceManagement
import SystemConfiguration
import UniformTypeIdentifiers

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var controller: AppController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            let controller = AppController(identity: try Store.loadIdentity())
            controller.start()
            self.controller = controller
        } catch {
            let alert = NSAlert()
            alert.messageText = "Copier couldn't start"
            alert.informativeText = String(describing: error)
            alert.runModal()
            NSApp.terminate(nil)
        }
    }

    /// Opening Copier again (Spotlight, Launchpad, Finder) while it runs shows its window.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        controller?.showMainWindow()
        return true
    }
}

/// Owns every component and routes events between them. Every send and receive lands in the
/// activity timeline under its protocol id, so both devices can show its real state.
@MainActor
final class AppController: NSObject {
    static let autoFileLimit: Int64 = 50_000_000
    private static let inlineClipLimit = Proto.maxMessage - Proto.maxHeader

    let store = Store()
    let live = LiveState()
    let activity: ActivityStore
    let phoneNotifications = PhoneNotifications()
    private let identity: Identity
    private let server: Server
    private let transfers = Transfers()
    private let pasteboard = PasteboardSync()
    private let notifier = Notifier()
    private let pairingPrompt = PairingPrompt()
    private let installServer = InstallServer()
    private let addPhoneWindow = AddPhoneWindow()
    private let mainWindow = MainWindow()
    private let arrival = ArrivalPanel()
    private var menu: StatusMenu?
    private var warnedAboutClipboardAccess = false
    private var ringReset: Task<Void, Never>?
    private var lastCopiedCode: String?

    /// `activityDirectory` lets tests keep their sample history away from the real one.
    init(identity: Identity, activityDirectory: URL? = nil) {
        self.identity = identity
        activity = activityDirectory.map { ActivityStore(directory: $0) } ?? ActivityStore()
        server = Server(identity: identity, store: store, deviceName: Self.computerName, appVersion: Self.appVersion)
        super.init()
    }

    func start() {
        menu = StatusMenu(app: self)
        notifier.onReply = { [weak self] key, text in self?.replyToNotification(key: key, text: text) }
        notifier.onOpenApp = { [weak self] in self?.showMainWindow() }
        notifier.start()

        server.onSessionsChanged = { [weak self] in self?.sessionsChanged() }
        server.onMessage = { [weak self] message, session in self?.handle(message, from: session) }
        server.onDataConnection = { [weak self] link, phone in
            guard let self else { return link.close() }
            await self.transfers.serve(link, phone: phone)
        }
        server.confirmPairing = { [weak self] name, code in
            (await self?.pairingPrompt.ask(phoneName: name, code: code)) ?? false
        }
        server.dismissPairingPrompt = { [weak self] in self?.pairingPrompt.dismiss() }
        server.onPaired = { [weak self] phone in self?.paired(phone) }
        addPhoneWindow.onClose = { [weak self] in
            self?.server.endInvite()
            self?.installServer.stop()
            self?.clearInvite()
        }

        transfers.onIncoming = { [weak self] header, _ in self?.incomingStarted(header) }
        transfers.onProgress = { [weak self] id, fraction in
            self?.activity.update(id) { item in
                switch item.state {
                case .received, .delivered, .failed: break
                default: item.state = .transferring(fraction)
                }
            }
        }
        transfers.onReceived = { [weak self] received, header, phone in self?.handle(received, header: header, from: phone) }
        transfers.onDelivered = { [weak self] id in self?.activity.update(id) { $0.state = .delivered } }
        transfers.onFailed = { [weak self] id, reason in self?.activity.update(id) { $0.state = .failed(reason) } }

        pasteboard.onLocalChange = { [weak self] clip in
            guard let self, self.store.syncClipboard else { return }
            self.send(clip, explicit: false)
        }
        pasteboard.onAccessBlocked = { [weak self] in self?.warnAboutClipboardAccess() }

        server.start()
        pasteboard.start()
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
        let workspace = NSWorkspace.shared.notificationCenter
        workspace.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.server.pause() }
        }
        workspace.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.server.resume() }
        }
        // The `copier` command-line tool talks to us with distributed notifications.
        DistributedNotificationCenter.default().addObserver(
            forName: Notification.Name("dev.copier.command"), object: nil, queue: .main
        ) { [weak self] note in
            let info = note.userInfo ?? [:]
            let command = info["cmd"] as? String
            let paths = info["paths"] as? [String] ?? []
            let text = info["text"] as? String
            let url = info["url"] as? String
            MainActor.assumeIsolated { self?.runCommand(command, paths: paths, text: text, url: url) }
        }
        sessionsChanged()
        clearInvite()  // a stale link from a previous run would be useless anyway
        Task {
            await refreshNotificationPermission()
            try? await Task.sleep(for: .seconds(1))  // the listener's port is known once it's ready
            writeStatus()
        }
        Log.app.info("Copier \(Self.appVersion, privacy: .public) started, device \(self.identity.deviceID, privacy: .public)")
        if store.phones.isEmpty { showAddPhone() }  // first run: straight to the QR code
    }

    // MARK: State

    var connectedPhones: [PhoneSession] { Array(server.sessions.values) }
    var pasteboardAccess: PasteboardSync.Access { pasteboard.access }
    var launchAtLogin: Bool { SMAppService.mainApp.status == .enabled }

    private func sessionsChanged() {
        let session = server.sessions.values.first
        live.connected = session != nil
        live.phoneName = session?.name ?? store.phones.last?.name
        live.hasPairedPhone = !store.phones.isEmpty
        if session == nil {
            live.battery = nil
            live.ringing = false
            live.autoCopy = nil
            live.autoCopyNote = nil
            live.autoCopyManaged = false
        }
        menu?.refreshIcon()
        writeStatus()
    }

    private static let supportDirectory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Copier", isDirectory: true)
    private static let inviteFile = supportDirectory.appendingPathComponent("invite.url")
    private static let diagnosticsFile = supportDirectory.appendingPathComponent("diagnostics.log")

    /// A line from the phone's copy detector: logged, and kept for `copier diagnose` / `doctor`.
    private func recordPhoneDiagnostic(_ text: String) {
        Log.clip.info("phone: \(text, privacy: .public)")
        let line = "\(Date.now.formatted(date: .omitted, time: .standard))  \(text)\n"
        do {
            try FileManager.default.createDirectory(at: Self.supportDirectory, withIntermediateDirectories: true)
            if let handle = try? FileHandle(forWritingTo: Self.diagnosticsFile) {
                defer { try? handle.close() }
                // ponytail: the file only grows during a diagnose run and is reset at the next one.
                try handle.seekToEnd()
                try handle.write(contentsOf: Data(line.utf8))
            } else {
                try Data(line.utf8).write(to: Self.diagnosticsFile)
            }
        } catch {
            Log.app.error("writing diagnostics failed: \(String(describing: error), privacy: .public)")
        }
    }

    /// `copier status`, `copier doctor` and `copier start` read this; tiny and rewritten on every change.
    private func writeStatus() {
        var status: [String: Any] = [
            "connected": live.connected,
            "paired": store.phones.count,
            "version": Self.appVersion,
            "clipboardAccess": String(describing: pasteboard.access),
            "notifications": live.notificationsAllowed,
            "updated": Date.now.timeIntervalSince1970,
        ]
        if let name = live.phoneName { status["phone"] = name }
        if let port = server.boundPort { status["port"] = Int(port) }
        if let address = LANAddress.candidates().first { status["address"] = address }
        if let battery = live.battery {
            status["battery"] = battery
            status["charging"] = live.charging
        }
        if let autoCopy = live.autoCopy { status["autoCopy"] = autoCopy }
        if let note = live.autoCopyNote { status["autoCopyNote"] = note }
        if live.autoCopyManaged { status["autoCopyManaged"] = true }
        do {
            try FileManager.default.createDirectory(at: Self.supportDirectory, withIntermediateDirectories: true)
            try JSONSerialization.data(withJSONObject: status, options: [.sortedKeys])
                .write(to: Self.supportDirectory.appendingPathComponent("status.json"), options: .atomic)
        } catch {
            Log.app.error("writing status failed: \(String(describing: error), privacy: .public)")
        }
    }

    private func runCommand(_ command: String?, paths: [String], text: String?, url: String?) {
        Log.app.info("command-line: \(command ?? "?", privacy: .public)")
        switch command {
        case "send": send(files: paths.map { URL(fileURLWithPath: $0) })
        case "copy": if let text { send(.text(text, html: nil, sensitive: false), explicit: true) }
        case "open": if let url, let link = Links.webURL(from: url) { openOnPhone(link) }
        case "ring": ringPhone()
        case "restart-network":
            // After a Wi-Fi change: drop stale sockets and re-announce, which is what phones wait for.
            server.pause()
            server.resume()
            Task {
                try? await Task.sleep(for: .seconds(1))
                writeStatus()
            }
        case "add-phone": showAddPhone()
        case "usb-setup": setUpPhone(alert: false)
        case "diagnose":
            // The phone reports what its copy detector sees for a while (labels of tapped buttons
            // and "copied" messages, never clipboard contents); `copier diagnose` prints it.
            var request = Header("diagnose")
            request.time = Int64(text.flatMap(Int.init) ?? 120)
            try? FileManager.default.removeItem(at: Self.diagnosticsFile)
            recordPhoneDiagnostic(connectedPhones.isEmpty ? "the phone isn't connected" : "asked the phone to report for \(request.time ?? 120) s")
            broadcast(Message(request))
        case "selftest-otp":
            // `copier selftest`: feeds a sample phone notification through the real path (detector,
            // clipboard, arrival panel, Notifications tab) so people can see the OTP flow safely.
            var sample = Header("notif")
            sample.key = "copier-selftest-\(UUID().uuidString)"
            sample.pkg = "dev.copier.selftest"
            sample.appName = "Copier test"
            sample.title = "Copier test"
            sample.text = "\(text ?? "482913") is your Copier test code. Don't share it."
            sample.time = Int64(Date.now.timeIntervalSince1970 * 1000)
            receiveNotification(sample)
        default: break
        }
    }

    /// The terminal QR (`copier qr`) needs the same one-time link; owner-only, deleted when the
    /// invite ends.
    private func writeInvite(_ url: String) {
        let created = FileManager.default.createFile(
            atPath: Self.inviteFile.path, contents: Data(url.utf8), attributes: [.posixPermissions: 0o600]
        )
        if !created { Log.app.error("couldn't write the invite file") }
    }

    private func clearInvite() {
        try? FileManager.default.removeItem(at: Self.inviteFile)
    }

    /// Symlinks the bundled tool as `copier` into the first writable bin folder on PATH. Only ever
    /// replaces an existing `copier` that already points into a Copier.app.
    func installCommandLineTool() {
        let tool = Bundle.main.bundleURL.appendingPathComponent("Contents/MacOS/copier-cli")
        guard FileManager.default.isExecutableFile(atPath: tool.path) else {
            return showAlert("The command-line tool isn't in this build", "Rebuild Copier with mac/scripts/build-app.sh.")
        }
        for directory in ["/opt/homebrew/bin", "/usr/local/bin", NSHomeDirectory() + "/.local/bin"] where FileManager.default.isWritableFile(atPath: directory) {
            let link = URL(fileURLWithPath: directory).appendingPathComponent("copier")
            if let existing = try? FileManager.default.destinationOfSymbolicLink(atPath: link.path) {
                guard existing.contains("Copier.app") else { continue }
                try? FileManager.default.removeItem(at: link)
            } else if FileManager.default.fileExists(atPath: link.path) {
                continue  // someone else's `copier`; leave it alone
            }
            do {
                try FileManager.default.createSymbolicLink(at: link, withDestinationURL: tool)
                return showAlert("Installed “copier”", "In a new terminal, try:\n\ncopier status\ncopier send report.pdf\ncopier open localhost:3000")
            } catch {
                continue
            }
        }
        showAlert("Couldn't install “copier”", "No writable bin folder found. Link it yourself:\n\nln -s \(tool.path) /usr/local/bin/copier")
    }

    /// Menu "Recent" entries: text is copied, files and photos open.
    func activate(_ item: ActivityItem) {
        if item.kind != .text, let url = item.fileURL, FileManager.default.fileExists(atPath: url.path) {
            NSWorkspace.shared.open(url)
        } else {
            copy(item)
        }
    }

    private func paired(_ phone: PairedPhone) {
        server.endInvite()
        installServer.stop()
        clearInvite()
        if addPhoneWindow.isOpen {
            addPhoneWindow.showPaired(phone.name)
        } else {
            notifier.post(title: "Paired with \(phone.name)", body: "Copy on one device, paste on the other.")
        }
        sessionsChanged()
    }

    private func refreshNotificationPermission() async {
        live.notificationsAllowed = await notifier.isAllowed()
        writeStatus()
    }

    // MARK: Phone → Mac

    private func handle(_ message: Message, from session: PhoneSession) {
        let header = message.header
        switch header.t {
        case "clip":
            applyClip(message, from: session)
        case "ack":
            if let id = header.id { activity.update(id) { $0.state = .delivered } }
        case "status":
            live.battery = header.battery.map { max(0, min(100, $0)) }
            live.charging = header.charging ?? false
            live.autoCopy = header.watcher
            live.autoCopyNote = header.msg
            live.autoCopyManaged = header.managed ?? false
            writeStatus()
        case "diag":
            if let text = header.text { recordPhoneDiagnostic(text) }
        case "notif":
            receiveNotification(header)
        case "notif-removed":
            if let key = header.key {
                phoneNotifications.remove(key: key)
                notifier.removePhone(key: key)
            }
        case "open":
            guard let text = header.url, let url = Links.webURL(from: text) else { return }
            openOnMac(url)
            activity.add(ActivityItem(id: UUID().uuidString, direction: .fromPhone, kind: .text, title: "Link", text: url.absoluteString, state: .received))
        default:
            Log.net.debug("ignoring \(header.t, privacy: .public)")
        }
    }

    private func applyClip(_ message: Message, from session: PhoneSession) {
        let htmlBytes = message.header.htmlBytes ?? 0
        guard (0...message.body.count).contains(htmlBytes) else {
            Log.clip.error("clip from \(session.id, privacy: .public) has an invalid htmlBytes")
            return
        }
        let html = htmlBytes > 0 ? Data(message.body.prefix(htmlBytes)) : nil
        let text = String(decoding: message.body.dropFirst(htmlBytes), as: UTF8.self)
        let sensitive = message.header.sensitive ?? false
        pasteboard.apply(text: text, html: html, sensitive: sensitive)
        if let id = message.header.id {
            var ack = Header("ack")
            ack.id = id
            session.send(Message(ack))
        }
        activity.add(ActivityItem(
            id: message.header.id ?? UUID().uuidString, direction: .fromPhone, kind: .text, title: "Text",
            text: sensitive ? nil : String(text.prefix(20_000)), size: Int64(message.body.count),
            sensitive: sensitive, state: .received
        ))
        menu?.flash()
        Log.clip.info("applied text clip (\(message.body.count) bytes)")
    }

    private func incomingStarted(_ header: Header) {
        guard let id = header.id else { return }
        let mime = header.mime ?? ""
        let kind: ActivityItem.Kind = mime.hasPrefix("image/") ? .image : (header.purpose == "clip" && mime.hasPrefix("text/") ? .text : .file)
        activity.add(ActivityItem(
            id: id, direction: .fromPhone, kind: kind, title: header.name ?? "File", size: header.size,
            sensitive: header.sensitive ?? false, state: .transferring(0)
        ))
    }

    private func handle(_ received: Received, header: Header, from phone: PairedPhone) {
        let id = header.id ?? UUID().uuidString
        switch received {
        case let .file(url):
            let isImage = UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true
            let hasThumbnail = isImage && activity.storeThumbnail(for: id, fileURL: url)
            if activity.item(id) == nil { incomingStarted(header) }
            activity.update(id) {
                $0.state = .received
                $0.fileURL = url
                $0.title = url.lastPathComponent
                $0.hasThumbnail = hasThumbnail
            }
            let isScreenshot = header.purpose == "screenshot"
            if isScreenshot, let data = try? Data(contentsOf: url) {
                pasteboard.apply(image: data, mime: header.mime ?? "image/png", sensitive: false)
            }
            let what = isScreenshot ? "Screenshot" : isImage ? "Photo" : "File"
            arrival.show(
                title: "\(what) from \(phone.name)",
                detail: isScreenshot ? "Copied — ⌘V to paste" : url.lastPathComponent,
                image: hasThumbnail ? activity.thumbnail(for: id) : NSWorkspace.shared.icon(forFile: url.path),
                actions: [
                    .init(title: "Open") { NSWorkspace.shared.open(url) },
                    .init(title: "Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([url]) },
                ],
                onClick: { [weak self] in self?.showMainWindow() }
            )
            if live.notificationsAllowed {
                notifier.post(title: "\(what) from \(phone.name)", body: url.lastPathComponent, revealing: url)
            }
        case let .clip(data, mime, sensitive):
            if mime.hasPrefix("text/") {
                pasteboard.apply(text: String(decoding: data, as: UTF8.self), html: nil, sensitive: sensitive)
                activity.update(id) {
                    $0.state = .received
                    $0.text = sensitive ? nil : String(decoding: data.prefix(20_000), as: UTF8.self)
                }
                menu?.flash()
            } else {
                pasteboard.apply(image: data, mime: mime, sensitive: sensitive)
                let copy = keepClipImage(data, id: id, mime: mime)
                let hasThumbnail = activity.storeThumbnail(for: id, imageData: data)
                activity.update(id) {
                    $0.state = .received
                    $0.fileURL = copy
                    $0.hasThumbnail = hasThumbnail
                }
                arrival.show(
                    title: "Image copied from \(phone.name)",
                    detail: "⌘V to paste",
                    image: hasThumbnail ? activity.thumbnail(for: id) : nil,
                    actions: [],
                    onClick: { [weak self] in self?.showMainWindow() }
                )
            }
        }
    }

    /// Received clipboard images are kept (in Application Support, deleted with their item) so
    /// "Copy" and "Open" work later from the timeline.
    private func keepClipImage(_ data: Data, id: String, mime: String) -> URL? {
        let ext = UTType(mimeType: mime)?.preferredFilenameExtension ?? "png"
        let url = activity.clipsDirectory.appendingPathComponent("\(id).\(ext)")
        do {
            try FileManager.default.createDirectory(at: activity.clipsDirectory, withIntermediateDirectories: true)
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            Log.clip.error("keeping clipboard image failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    private func receiveNotification(_ header: Header) {
        guard let key = header.key else { return }
        let notification = PhoneNotification(
            key: key,
            pkg: header.pkg ?? "",
            appName: header.appName ?? header.pkg ?? "Phone",
            title: header.title ?? "",
            text: header.text ?? "",
            date: header.time.map { Date(timeIntervalSince1970: Double($0) / 1000) } ?? .now,
            actions: header.actions ?? [],
            replyAction: header.replyAction,
            isCall: header.call ?? false
        )
        phoneNotifications.upsert(notification)
        notifier.postPhone(notification)
        // Best effort: Android 15+ hides one-time codes from third-party listeners, so this only
        // fires for codes the phone actually let through.
        if store.copyCodes, let code = OneTimeCode.find(in: notification.title + " " + notification.text), code != lastCopiedCode {
            lastCopiedCode = code
            copyCode(code, from: notification.appName)
        }
    }

    // MARK: Mac → phone

    func sendClipboardNow() {
        guard let clip = pasteboard.read() else {
            notifier.post(title: "Nothing to send", body: "The clipboard is empty, or it holds something Copier can't send yet.")
            return
        }
        send(clip, explicit: true)
    }

    private func send(_ clip: LocalClip, explicit: Bool) {
        let sessions = connectedPhones
        guard !sessions.isEmpty else {
            if explicit { notifyNotConnected() }
            return
        }
        switch clip {
        case let .text(text, html, sensitive):
            let textData = Data(text.utf8)
            var header = Header("clip")
            header.sensitive = sensitive
            var body = textData
            if let html, html.count + textData.count <= Self.inlineClipLimit {
                header.htmlBytes = html.count
                body = html + textData
            }
            let id: String
            if body.count <= Self.inlineClipLimit {
                id = UUID().uuidString
                header.id = id
                for session in sessions { session.send(Message(header, body: body)) }
            } else {
                let offer = transfers.offerData(textData, name: "Clipboard.txt", mime: "text/plain", purpose: "clip", sensitive: sensitive)
                id = offer.id ?? UUID().uuidString
                for session in sessions { session.send(Message(offer)) }
            }
            activity.add(ActivityItem(
                id: id, direction: .toPhone, kind: .text, title: "Text",
                text: sensitive ? nil : String(text.prefix(20_000)), size: Int64(textData.count),
                sensitive: sensitive, state: .waiting
            ))
            Log.clip.info("sent text clip (\(body.count) bytes, sensitive: \(sensitive))")
        case let .image(data, mime, sensitive):
            let name = mime == "image/png" ? "Clipboard.png" : "Clipboard.jpg"
            let offer = transfers.offerData(data, name: name, mime: mime, purpose: "clip", sensitive: sensitive)
            let id = offer.id ?? UUID().uuidString
            let hasThumbnail = activity.storeThumbnail(for: id, imageData: data)
            activity.add(ActivityItem(
                id: id, direction: .toPhone, kind: .image, title: "Image", size: Int64(data.count),
                hasThumbnail: hasThumbnail, sensitive: sensitive, state: .waiting
            ))
            for session in sessions { session.send(Message(offer)) }
            Log.clip.info("offered image clip (\(data.count) bytes)")
        case let .files(urls):
            if !explicit {
                guard store.syncCopiedFiles else { return }
                let total = urls.reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
                guard total <= Self.autoFileLimit else {
                    notifier.post(title: "Copied files weren't synced", body: "They're over 50 MB. Drop them on the Copier window to send them.")
                    return
                }
            }
            send(files: urls)
        }
    }

    func send(files urls: [URL]) {
        let sessions = connectedPhones
        guard !sessions.isEmpty else { return notifyNotConnected() }
        var skipped: [String] = []
        for url in urls {
            do {
                let offer = try transfers.offerFile(url, purpose: "file")
                let id = offer.id ?? UUID().uuidString
                let isImage = UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true
                activity.add(ActivityItem(
                    id: id, direction: .toPhone, kind: isImage ? .image : .file, title: url.lastPathComponent,
                    size: offer.size, fileURL: url,
                    hasThumbnail: isImage && activity.storeThumbnail(for: id, fileURL: url), state: .waiting
                ))
                for session in sessions { session.send(Message(offer)) }
            } catch {
                skipped.append(url.lastPathComponent)
            }
        }
        if !skipped.isEmpty {
            notifier.post(title: "Some items weren't sent", body: "Folders aren't supported yet: \(skipped.joined(separator: ", "))")
        }
    }

    func chooseAndSendFiles() {
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                let panel = NSOpenPanel()
                panel.allowsMultipleSelection = true
                panel.canChooseDirectories = false
                panel.prompt = "Send"
                NSApp.activate()
                if panel.runModal() == .OK { self.send(files: panel.urls) }
            }
        }
    }

    func ringPhone() {
        broadcast(Message(Header("ring")))
        live.ringing = true
        ringReset?.cancel()
        ringReset = Task { [weak self] in
            try? await Task.sleep(for: .seconds(30))  // the phone stops by itself after 30 s too
            guard !Task.isCancelled else { return }
            self?.live.ringing = false
        }
    }

    func stopRing() {
        broadcast(Message(Header("ring-stop")))
        ringReset?.cancel()
        live.ringing = false
    }

    /// Opens a link on the phone. `localhost` dev servers are rewritten to this Mac's LAN address.
    func openOnPhone(_ url: URL) {
        guard !connectedPhones.isEmpty else { return notifyNotConnected() }
        let target = Links.forPhone(url, lanHost: LANAddress.candidates().first)
        var header = Header("open")
        header.url = target.absoluteString
        broadcast(Message(header))
        activity.add(ActivityItem(id: UUID().uuidString, direction: .toPhone, kind: .text, title: "Link", text: target.absoluteString, state: .waiting))
    }

    func openClipboardLinkOnPhone() {
        guard case let .text(text, _, _)? = pasteboard.read(), let url = Links.webURL(from: text) else {
            notifier.post(title: "No link on the clipboard", body: "Copy a link (or localhost:3000) first.")
            return
        }
        openOnPhone(url)
    }

    func openOnMac(_ url: URL) {
        NSWorkspace.shared.open(url)
    }

    private func broadcast(_ message: Message) {
        for session in server.sessions.values { session.send(message) }
    }

    // MARK: Notifications from the phone

    func triggerNotificationAction(_ notification: PhoneNotification, index: Int, text: String? = nil) {
        var header = Header("notif-action")
        header.key = notification.key
        header.index = index
        header.text = text
        broadcast(Message(header))
    }

    func reply(to notification: PhoneNotification, text: String) {
        guard let index = notification.replyAction else { return }
        triggerNotificationAction(notification, index: index, text: text)
    }

    private func replyToNotification(key: String, text: String) {
        guard let notification = phoneNotifications.item(key) else { return }
        reply(to: notification, text: text)
    }

    func dismissNotification(_ notification: PhoneNotification) {
        var header = Header("notif-dismiss")
        header.key = notification.key
        broadcast(Message(header))
        phoneNotifications.remove(key: notification.key)
        notifier.removePhone(key: notification.key)
    }

    func copyCode(_ code: String, from app: String? = nil) {
        pasteboard.apply(text: code, html: nil, sensitive: true)
        arrival.show(
            title: "Code \(code) copied",
            detail: app.map { "From \($0) · ⌘V to paste" } ?? "⌘V to paste",
            image: nil,
            actions: [],
            onClick: { [weak self] in self?.showMainWindow() }
        )
    }

    // MARK: Timeline actions

    func copy(_ item: ActivityItem) {
        switch item.kind {
        case .text:
            if let text = item.text { pasteboard.apply(text: text, html: nil, sensitive: false) }
        case .image:
            if let url = item.fileURL, let data = try? Data(contentsOf: url) {
                pasteboard.apply(image: data, mime: UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "image/png", sensitive: false)
            }
        case .file:
            if let url = item.fileURL { pasteboard.apply(fileURL: url) }
        }
    }

    func open(_ item: ActivityItem) {
        if let url = item.fileURL { NSWorkspace.shared.open(url) }
    }

    func reveal(_ item: ActivityItem) {
        if let url = item.fileURL { NSWorkspace.shared.activateFileViewerSelecting([url]) }
    }

    func sendAgain(_ item: ActivityItem) {
        guard let url = item.fileURL else { return }
        activity.remove(item.id)
        send(files: [url])
    }

    // MARK: Services (Finder → right-click → Services; registered in Info.plist)

    @objc func sendToPhone(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        if let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL],
           !urls.isEmpty {
            send(files: urls)
        } else if let text = pasteboard.string(forType: .string) {
            send(.text(text, html: nil, sensitive: false), explicit: true)
        }
    }

    @objc func openOnPhoneService(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        guard let text = pasteboard.string(forType: .string), let url = Links.webURL(from: text) else { return }
        openOnPhone(url)
    }

    // MARK: Windows and settings

    func showMainWindow() {
        mainWindow.show(app: self)
        Task { await refreshNotificationPermission() }
    }

    func openNotificationSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=dev.copier.mac") {
            NSWorkspace.shared.open(url)
        }
    }

    func toggleLaunchAtLogin() {
        do {
            if launchAtLogin { try SMAppService.mainApp.unregister() } else { try SMAppService.mainApp.register() }
        } catch {
            notifier.post(title: "Couldn't change Launch at Login", body: error.localizedDescription)
        }
    }

    func openClipboardPrivacySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Pasteboard") {
            NSWorkspace.shared.open(url)
        }
    }

    /// The optional one-time USB step (PhoneSetup). `copier usb-setup` reads the result file
    /// instead of getting an alert.
    func setUpPhone(alert: Bool = true) {
        Task {
            let outcome = await PhoneSetup.run(apk: Bundle.main.url(forResource: "Copier", withExtension: "apk"))
            let title = outcome.succeeded ? "Phone is set up" : "Phone setup didn't finish"
            do {
                try FileManager.default.createDirectory(at: Self.supportDirectory, withIntermediateDirectories: true)
                try Data("\(outcome.succeeded ? "✓" : "✗") \(title)\n\(outcome.summary)\n".utf8)
                    .write(to: Self.supportDirectory.appendingPathComponent("usb-setup.txt"), options: .atomic)
            } catch {
                Log.app.error("writing the USB setup result failed: \(String(describing: error), privacy: .public)")
            }
            if alert { showAlert(title, outcome.summary) }
        }
    }

    /// Opens the QR window. The QR carries the Mac's addresses and public key plus a one-time
    /// token (PROTOCOL.md § QR pairing); the install page is served only while it's open.
    func showAddPhone() {
        let hosts = LANAddress.candidates()
        guard let host = hosts.first else {
            showAlert("This Mac isn't on a network", "Connect it to Wi-Fi (or to your phone's hotspot), then choose Add Phone… again.")
            return
        }
        let token = Crypto.randomBytes(16)
        server.beginInvite(token: token)
        installServer.start()
        let fragment = [
            "h=" + hosts.prefix(3).joined(separator: ","),
            "p=\(server.boundPort ?? Proto.port)",
            "k=" + identity.publicKey.base64URL,
            "t=" + token.base64URL,
        ].joined(separator: "&")
        let invite = "http://\(host):\(Proto.installPort)/#\(fragment)"
        writeInvite(invite)
        addPhoneWindow.show(url: invite, apkMissing: !installServer.hasAPK)
    }

    func unpair(_ phone: PairedPhone) {
        store.remove(id: phone.id)
        server.disconnect(phoneID: phone.id)
        sessionsChanged()
    }

    /// A one-line summary of the latest timeline item, for the menu.
    var latestSummary: String? {
        guard let item = activity.items.first else { return nil }
        let what = switch item.kind {
        case .text: item.title == "Link" ? "Link" : item.sensitive ? "Password" : "Text"
        case .image: "Image"
        case .file: item.title
        }
        let way = item.direction == .toPhone ? "to phone" : "from phone"
        return "\(what) \(way) · \(item.date.formatted(date: .omitted, time: .shortened))"
    }

    // MARK: Helpers

    private func warnAboutClipboardAccess() {
        guard !warnedAboutClipboardAccess else { return }
        warnedAboutClipboardAccess = true
        notifier.post(
            title: "Clipboard sync is paused",
            body: "Set Copier to “Always Allow” in Privacy & Security → Paste from Other Apps. The menu-bar icon has a shortcut."
        )
    }

    private func notifyNotConnected() {
        notifier.post(title: "Phone not connected", body: "Open Copier on your phone and keep it on the same Wi-Fi as this Mac.")
    }

    /// Alerts run from a fresh main-queue block so no async job sits blocked inside a modal loop.
    private func showAlert(_ title: String, _ text: String) {
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                let alert = NSAlert()
                alert.messageText = title
                alert.informativeText = text
                NSApp.activate()
                alert.runModal()
            }
        }
    }

    private static var computerName: String {
        (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? "Mac"
    }

    private static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "dev"
    }
}

/// The Mac half of code pairing: shows the code and waits for the user's answer.
@MainActor
final class PairingPrompt {
    private var isShowing = false

    func ask(phoneName: String, code: String) async -> Bool {
        await withCheckedContinuation { continuation in
            // runModal spins a nested run loop. Starting it from a fresh main-queue block keeps the
            // calling task suspended rather than blocked, so network callbacks keep flowing.
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    let alert = NSAlert()
                    alert.messageText = "Pair with “\(phoneName)”?"
                    alert.informativeText = "Only pair if your phone shows the same code."
                    let codeLabel = NSTextField(labelWithString: Pairing.display(code))
                    codeLabel.font = .monospacedDigitSystemFont(ofSize: 30, weight: .semibold)
                    codeLabel.alignment = .center
                    codeLabel.frame = NSRect(x: 0, y: 0, width: 240, height: 40)
                    alert.accessoryView = codeLabel
                    alert.addButton(withTitle: "Pair")
                    alert.addButton(withTitle: "Cancel")
                    NSApp.activate()
                    self.isShowing = true
                    let response = alert.runModal()
                    self.isShowing = false
                    continuation.resume(returning: response == .alertFirstButtonReturn)
                }
            }
        }
    }

    /// Called when the phone cancels or disconnects first; `runModal` then returns `.abort`.
    func dismiss() {
        if isShowing { NSApp.abortModal() }
    }
}
