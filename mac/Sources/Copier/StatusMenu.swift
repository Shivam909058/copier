import AppKit

/// The menu-bar icon and its menu. The menu is rebuilt each time it opens so it always shows
/// live state. Files dropped on the icon are sent to the phone.
@MainActor
final class StatusMenu: NSObject, NSMenuDelegate, NSWindowDelegate, NSDraggingDestination {
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    private unowned let app: AppController
    private var flashing: Task<Void, Never>?

    init(app: AppController) {
        self.app = app
        super.init()
        let menu = NSMenu()
        menu.autoenablesItems = false
        menu.delegate = self
        statusItem.menu = menu
        // The status item's window forwards drag events to its delegate.
        statusItem.button?.window?.registerForDraggedTypes([.fileURL])
        statusItem.button?.window?.delegate = self
        refreshIcon()
    }

    func refreshIcon() {
        let connected = app.live.connected
        setIcon(connected ? "iphone.radiowaves.left.and.right" : "iphone", label: connected ? "Copier: phone connected" : "Copier: phone not connected")
        statusItem.button?.appearsDisabled = !connected
    }

    /// A one-second glance-able "got it" when text arrives from the phone.
    func flash() {
        flashing?.cancel()
        setIcon("doc.on.clipboard.fill", label: "Copier: copied from phone")
        flashing = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            self?.refreshIcon()
        }
    }

    private func setIcon(_ symbol: String, label: String) {
        let image = NSImage(systemSymbolName: symbol, accessibilityDescription: label)
        image?.isTemplate = true
        statusItem.button?.image = image
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let live = app.live
        let connected = live.connected

        menu.addItem(label(statusLine(live)))
        if let summary = app.latestSummary { menu.addItem(label("Last: \(summary)")) }
        menu.addItem(.separator())

        let open = ActionItem("Open Copier") { [app] in app.showMainWindow() }
        open.image = NSImage(systemSymbolName: "rectangle.stack", accessibilityDescription: nil)
        menu.addItem(open)
        for item in app.activity.items.prefix(5) {
            let entry = ActionItem(recentTitle(item)) { [app] in app.activate(item) }
            entry.image = NSImage(
                systemSymbolName: item.direction == .fromPhone ? "arrow.down.left" : "arrow.up.right",
                accessibilityDescription: item.direction == .fromPhone ? "from phone" : "to phone"
            )
            entry.indentationLevel = 1
            entry.toolTip = item.kind == .text ? "Copy" : "Open"
            menu.addItem(entry)
        }
        menu.addItem(ActionItem("Send Clipboard to Phone", enabled: connected) { [app] in app.sendClipboardNow() })
        menu.addItem(ActionItem("Send Files…", key: "o", enabled: connected) { [app] in app.chooseAndSendFiles() })
        menu.addItem(ActionItem("Open Copied Link on Phone", enabled: connected) { [app] in app.openClipboardLinkOnPhone() })
        menu.addItem(ActionItem(live.ringing ? "Stop Ringing Phone" : "Ring Phone", enabled: connected) { [app] in
            app.live.ringing ? app.stopRing() : app.ringPhone()
        })
        menu.addItem(.separator())

        menu.addItem(ActionItem("Sync Clipboard to Phone", on: app.store.syncClipboard) { [app] in app.store.syncClipboard.toggle() })
        menu.addItem(ActionItem("Sync Copied Files (up to 50 MB)", on: app.store.syncCopiedFiles) { [app] in app.store.syncCopiedFiles.toggle() })
        menu.addItem(ActionItem("Copy Codes from Phone Notifications", on: app.store.copyCodes) { [app] in app.store.copyCodes.toggle() })
        menu.addItem(ActionItem("Launch at Login", on: app.launchAtLogin) { [app] in app.toggleLaunchAtLogin() })
        menu.addItem(.separator())

        if app.pasteboardAccess != .allowed {
            menu.addItem(ActionItem("⚠︎ Allow Clipboard Access…") { [app] in app.openClipboardPrivacySettings() })
        }
        menu.addItem(ActionItem("Add Phone…") { [app] in app.showAddPhone() })
        let paired = app.store.phones
        if !paired.isEmpty {
            let submenu = NSMenu()
            submenu.autoenablesItems = false
            for phone in paired {
                submenu.addItem(ActionItem("Unpair \(phone.name)") { [app] in app.unpair(phone) })
            }
            let parent = NSMenuItem(title: "Paired Phones", action: nil, keyEquivalent: "")
            parent.submenu = submenu
            menu.addItem(parent)
        }
        let advanced = NSMenu()
        advanced.autoenablesItems = false
        advanced.addItem(ActionItem("Self-Managing Automatic Copy (one-time USB setup)…") { [app] in app.setUpPhone() })
        advanced.addItem(ActionItem("Install Command-Line Tool (copier)…") { [app] in app.installCommandLineTool() })
        advanced.addItem(ActionItem("Clear History…") { [app] in app.activity.clear() })
        let advancedItem = NSMenuItem(title: "Advanced", action: nil, keyEquivalent: "")
        advancedItem.submenu = advanced
        menu.addItem(advancedItem)
        menu.addItem(.separator())
        menu.addItem(ActionItem("Quit Copier", key: "q") { NSApp.terminate(nil) })
    }

    private func statusLine(_ live: LiveState) -> String {
        if live.connected, let name = live.phoneName {
            guard let battery = live.battery else { return "Connected to \(name)" }
            return "Connected to \(name) · \(battery)%\(live.charging ? " ⚡︎" : "")"
        }
        if let name = live.phoneName { return "Waiting for \(name)…" }
        return "No phone yet — choose Add Phone…"
    }

    private func recentTitle(_ item: ActivityItem) -> String {
        let raw: String
        switch item.kind {
        case .text: raw = item.sensitive ? "Password" : (item.text ?? "Text").split(separator: "\n").first.map(String.init) ?? "Text"
        case .image, .file: raw = item.title
        }
        return raw.count > 42 ? String(raw.prefix(41)) + "…" : raw
    }

    private func label(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    // MARK: Drag and drop onto the icon

    func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        app.live.connected ? .copy : []
    }

    func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        guard let urls = sender.draggingPasteboard.readObjects(
            forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]
        ) as? [URL], !urls.isEmpty else { return false }
        app.send(files: urls)
        return true
    }
}

/// A menu item that runs a closure.
final class ActionItem: NSMenuItem {
    private let handler: @MainActor () -> Void

    init(_ title: String, key: String = "", on: Bool = false, enabled: Bool = true, _ handler: @escaping @MainActor () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(fire), keyEquivalent: key)
        target = self
        state = on ? .on : .off
        isEnabled = enabled
    }

    @available(*, unavailable)
    required init(coder: NSCoder) {
        fatalError("ActionItem is never loaded from a nib")
    }

    @objc private func fire() {
        let handler = handler
        MainActor.assumeIsolated { handler() }  // AppKit sends menu actions on the main thread
    }
}
