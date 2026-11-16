import AppKit
import CopierCore
import SwiftUI
import UniformTypeIdentifiers

extension Color {
    /// Carbon-copy indigo: the one accent — relay spine, dots, progress, primary actions.
    static let carbon = Color(nsColor: NSColor(name: "carbon") { appearance in
        appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            ? NSColor(srgbRed: 0x8C / 255, green: 0x9B / 255, blue: 0xFF / 255, alpha: 1)
            : NSColor(srgbRed: 0x33 / 255, green: 0x46 / 255, blue: 0xA8 / 255, alpha: 1)
    })
}

/// The Copier window: phone status, the relay timeline and mirrored notifications.
@MainActor
final class MainWindow: NSObject, NSWindowDelegate {
    private var window: NSWindow?

    var isVisible: Bool { window?.isVisible == true }

    func show(app: AppController) {
        if window == nil {
            let controller = NSHostingController(rootView: RelayView(app: app))
            controller.sizingOptions = [.minSize]
            let window = NSWindow(contentViewController: controller)
            window.title = "Copier"
            window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
            window.setContentSize(NSSize(width: 780, height: 620))
            window.isReleasedWhenClosed = false
            window.delegate = self
            window.center()
            window.setFrameAutosaveName("CopierMainWindow")
            self.window = window
        }
        NSApp.activate()
        window?.makeKeyAndOrderFront(nil)
    }
}

// MARK: - Root

struct RelayView: View {
    let app: AppController
    @State private var section: Section = .activity
    @State private var filter: Filter = .all
    @State private var dropTargeted = false

    enum Section: Hashable { case activity, notifications }

    enum Filter: String, CaseIterable, Identifiable {
        case all = "Everything", clipboard = "Clipboard", files = "Photos & files"
        var id: String { rawValue }
    }

    var body: some View {
        VStack(spacing: 0) {
            HeaderView(app: app)
            if !app.live.notificationsAllowed {
                NotificationsOffBanner { app.openNotificationSettings() }
            }
            if app.live.connected, app.live.autoCopy == false {
                AutoCopyOffBanner(phone: app.live.phoneName ?? "the phone", note: app.live.autoCopyNote)
            }
            HStack(spacing: 12) {
                Picker("Section", selection: $section) {
                    Text("Activity").tag(Section.activity)
                    Text(notificationsTitle).tag(Section.notifications)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
                Spacer()
                if section == .activity {
                    Picker("Show", selection: $filter) {
                        ForEach(Filter.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.menu)
                    .fixedSize()
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 10)
            Divider()
            switch section {
            case .activity: ActivityTimeline(app: app, items: visibleItems)
            case .notifications: NotificationsList(app: app)
            }
        }
        .frame(minWidth: 580, minHeight: 460)
        .onDrop(of: [.fileURL], isTargeted: $dropTargeted, perform: drop)
        .overlay {
            if dropTargeted { DropOverlay(phone: app.live.phoneName ?? "your phone") }
        }
    }

    private var notificationsTitle: String {
        let count = app.phoneNotifications.items.count
        return count == 0 ? "Notifications" : "Notifications (\(count))"
    }

    private var visibleItems: [ActivityItem] {
        switch filter {
        case .all: app.activity.items
        case .clipboard: app.activity.items.filter { $0.kind == .text || ($0.kind == .image && $0.fileURL?.path.hasPrefix(app.activity.clipsDirectory.path) != false) }
        case .files: app.activity.items.filter { $0.kind != .text }
        }
    }

    private func drop(_ providers: [NSItemProvider]) -> Bool {
        for provider in providers {
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                guard let url else { return }
                Task { @MainActor in app.send(files: [url]) }
            }
        }
        return !providers.isEmpty
    }
}

// MARK: - Header

private struct HeaderView: View {
    let app: AppController

    var body: some View {
        let live = app.live
        HStack(spacing: 14) {
            Image(systemName: "iphone.gen3")
                .font(.system(size: 28))
                .foregroundStyle(Color.carbon)
                .frame(width: 34)
            VStack(alignment: .leading, spacing: 3) {
                Text(live.phoneName ?? "No phone yet")
                    .font(.title2.weight(.semibold))
                    .fontDesign(.rounded)
                HStack(spacing: 6) {
                    Circle()
                        .fill(live.connected ? Color.green : Color.secondary.opacity(0.5))
                        .frame(width: 7, height: 7)
                    Text(statusText(live))
                    if live.connected, let battery = live.battery {
                        Image(systemName: batterySymbol(battery, charging: live.charging))
                            .foregroundStyle(battery <= 15 && !live.charging ? Color.red : Color.secondary)
                            .accessibilityHidden(true)
                        Text("\(battery)%").monospacedDigit()
                    }
                }
                .font(.callout)
                .foregroundStyle(.secondary)
            }
            Spacer(minLength: 12)
            if live.hasPairedPhone {
                Button(live.ringing ? "Stop Ringing" : "Ring Phone") {
                    live.ringing ? app.stopRing() : app.ringPhone()
                }
                .disabled(!live.connected)
                Button("Send Clipboard") { app.sendClipboardNow() }
                    .disabled(!live.connected)
                Button("Send Files…") { app.chooseAndSendFiles() }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.carbon)
                    .disabled(!live.connected)
            } else {
                Button("Add Phone…") { app.showAddPhone() }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.carbon)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
    }

    private func statusText(_ live: LiveState) -> String {
        if live.connected { return "Connected" }
        guard let name = live.phoneName else { return "Scan the QR code to add your phone" }
        return "Waiting for \(name)…"
    }

    private func batterySymbol(_ level: Int, charging: Bool) -> String {
        if charging { return "battery.100.bolt" }
        switch level {
        case ...10: return "battery.0"
        case ...35: return "battery.25"
        case ...60: return "battery.50"
        case ...85: return "battery.75"
        default: return "battery.100"
        }
    }
}

private struct NotificationsOffBanner: View {
    let turnOn: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "bell.slash").foregroundStyle(.secondary)
            Text("Notifications are off for Copier, so you only hear about files while this window or the arrival panel is up.")
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)
            Spacer()
            Button("Turn On…", action: turnOn)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
        .background(Color.carbon.opacity(0.08))
    }
}

/// Copies on the phone won't arrive by themselves until this is fixed, so say it where people look.
private struct AutoCopyOffBanner: View {
    let phone: String
    let note: String?

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "pause.circle").foregroundStyle(.orange)
            Text(message).font(.callout).fixedSize(horizontal: false, vertical: true)
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
        .background(Color.orange.opacity(0.10))
    }

    private var message: String {
        let reason = note.map { " (\($0))" } ?? ""
        return "Automatic copy is off on \(phone)\(reason). Copying there won't reach this Mac until you turn it back on: open Copier on the phone, or tap its notification."
    }
}

// MARK: - Activity timeline

private struct ActivityTimeline: View {
    let app: AppController
    let items: [ActivityItem]

    var body: some View {
        if items.isEmpty {
            VStack(spacing: 12) {
                Image(systemName: "arrow.left.arrow.right")
                    .font(.system(size: 30, weight: .light))
                    .foregroundStyle(Color.carbon)
                Text("Copy something on \(app.live.phoneName ?? "your phone"), or drop a file here.")
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            VStack(spacing: 0) {
                HStack {
                    Text("From \(app.live.phoneName ?? "phone")").frame(maxWidth: .infinity, alignment: .trailing)
                    Color.clear.frame(width: 36, height: 1)
                    Text("From this Mac").frame(maxWidth: .infinity, alignment: .leading)
                }
                .font(.caption)
                .foregroundStyle(.tertiary)
                .padding(.horizontal, 16)
                .padding(.top, 10)
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(items) { item in
                            RelayRow(app: app, item: item)
                        }
                    }
                    .padding(.vertical, 6)
                }
            }
        }
    }
}

/// One item on its device's side of the spine: the phone's on the left, this Mac's on the right.
private struct RelayRow: View {
    let app: AppController
    let item: ActivityItem

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            side(mine: false).frame(maxWidth: .infinity, alignment: .trailing)
            Spine(failed: { if case .failed = item.state { true } else { false } }())
            side(mine: true).frame(maxWidth: .infinity, alignment: .leading)
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, 16)
        .contextMenu { ItemMenu(app: app, item: item) }
    }

    @ViewBuilder
    private func side(mine: Bool) -> some View {
        if (item.direction == .toPhone) == mine {
            ItemView(app: app, item: item, hugsTrailing: !mine).padding(.vertical, 9)
        } else {
            Color.clear.frame(height: 1)
        }
    }
}

private struct Spine: View {
    let failed: Bool

    var body: some View {
        ZStack(alignment: .top) {
            Rectangle()
                .fill(Color.carbon.opacity(0.22))
                .frame(width: 2)
                .frame(maxHeight: .infinity)
            Circle()
                .fill(failed ? Color.red : Color.carbon)
                .frame(width: 9, height: 9)
                .overlay(Circle().stroke(Color(nsColor: .windowBackgroundColor), lineWidth: 2))
                .padding(.top, 15)
        }
        .frame(width: 36)
        .accessibilityHidden(true)
    }
}

private struct ItemView: View {
    let app: AppController
    let item: ActivityItem
    let hugsTrailing: Bool

    var body: some View {
        VStack(alignment: hugsTrailing ? .trailing : .leading, spacing: 6) {
            switch item.kind {
            case .text: TextContent(item: item)
            case .image: ImageContent(app: app, item: item, hugsTrailing: hugsTrailing)
            case .file: FileContent(item: item)
            }
            if case let .transferring(fraction) = item.state {
                ProgressView(value: fraction).tint(Color.carbon).frame(maxWidth: 220)
            }
            HStack(spacing: 10) {
                Text(timeLabel(item.date)).monospacedDigit()
                StateLabel(app: app, item: item)
                ItemActions(app: app, item: item)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: 360, alignment: hugsTrailing ? .trailing : .leading)
    }
}

private struct TextContent: View {
    let item: ActivityItem
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            RoundedRectangle(cornerRadius: 1.5)
                .fill(item.direction == .toPhone ? Color.carbon : Color.secondary.opacity(0.45))
                .frame(width: 3)
            if item.sensitive {
                Label("Password", systemImage: "lock.fill").foregroundStyle(.secondary)
            } else {
                Text(item.text ?? "")
                    .lineLimit(6)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.vertical, 8)
        .padding(.trailing, 12)
        .padding(.leading, 8)
        .background(
            (item.direction == .toPhone ? Color.carbon.opacity(colorScheme == .dark ? 0.18 : 0.08) : Color.primary.opacity(0.05)),
            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
        )
    }
}

private struct ImageContent: View {
    let app: AppController
    let item: ActivityItem
    let hugsTrailing: Bool

    var body: some View {
        VStack(alignment: hugsTrailing ? .trailing : .leading, spacing: 4) {
            if item.hasThumbnail, let thumbnail = app.activity.thumbnail(for: item.id) {
                Image(nsImage: thumbnail)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 260, maxHeight: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .onTapGesture(count: 2) { app.open(item) }
                    .accessibilityLabel(item.title)
            } else {
                Label(item.title, systemImage: "photo").padding(10)
            }
            Text(captionLabel(item)).font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
        }
    }
}

private struct FileContent: View {
    let item: ActivityItem

    var body: some View {
        HStack(spacing: 10) {
            Image(nsImage: icon).resizable().frame(width: 32, height: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).lineLimit(1).truncationMode(.middle)
                if let size = item.size {
                    Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file)).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .padding(10)
        .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var icon: NSImage {
        if let url = item.fileURL, FileManager.default.fileExists(atPath: url.path) {
            return NSWorkspace.shared.icon(forFile: url.path)
        }
        let type = UTType(filenameExtension: (item.title as NSString).pathExtension) ?? .data
        return NSWorkspace.shared.icon(for: type)
    }
}

/// The item's state in words, so nobody has to guess whether it arrived.
private struct StateLabel: View {
    let app: AppController
    let item: ActivityItem

    var body: some View {
        switch (item.direction, item.state) {
        case let (direction, .transferring(fraction)):
            Text("\(direction == .toPhone ? "Sending" : "Receiving") \(Int(fraction * 100))%").monospacedDigit()
        case (.toPhone, .waiting):
            Text(item.kind == .text ? "Sent" : "Waiting for phone…")
        case (.toPhone, .delivered):
            Label("On phone", systemImage: "checkmark").foregroundStyle(.green)
        case (.fromPhone, .received):
            Text(receivedLabel)
        case let (_, .failed(reason)):
            Text("Didn't arrive · \(reason)").foregroundStyle(.red)
        default:
            EmptyView()
        }
    }

    private var receivedLabel: String {
        if item.kind == .text { return "Copied here" }
        if let url = item.fileURL, url.path.hasPrefix(app.activity.clipsDirectory.path) { return "Copied here" }
        return "In Downloads"
    }
}

private struct ItemActions: View {
    let app: AppController
    let item: ActivityItem

    var body: some View {
        Group {
            if item.kind == .text, !item.sensitive, item.text != nil {
                Button("Copy") { app.copy(item) }
                if let link = item.text.flatMap(Links.webURL(from:)) {
                    Button(item.direction == .fromPhone ? "Open" : "Open on Phone") {
                        item.direction == .fromPhone ? app.openOnMac(link) : app.openOnPhone(link)
                    }
                }
            }
            if item.kind != .text, let url = item.fileURL, FileManager.default.fileExists(atPath: url.path) {
                Button("Open") { app.open(item) }
                if !url.path.hasPrefix(app.activity.clipsDirectory.path) {
                    Button("Show in Finder") { app.reveal(item) }
                }
            }
            if item.direction == .toPhone, case .failed = item.state, item.fileURL != nil {
                Button("Send Again") { app.sendAgain(item) }
            }
        }
        .buttonStyle(.borderless)
        .foregroundStyle(Color.carbon)  // metadata stays grey; anything clickable is indigo
    }
}

private struct ItemMenu: View {
    let app: AppController
    let item: ActivityItem

    var body: some View {
        if item.kind == .text, item.text != nil { Button("Copy") { app.copy(item) } }
        if item.kind != .text, item.fileURL != nil {
            Button("Open") { app.open(item) }
            Button("Copy") { app.copy(item) }
            Button("Show in Finder") { app.reveal(item) }
        }
        Divider()
        Button("Remove from History") { app.activity.remove(item.id) }
    }
}

// MARK: - Notifications

private struct NotificationsList: View {
    let app: AppController

    var body: some View {
        let items = app.phoneNotifications.items
        if items.isEmpty {
            VStack(spacing: 10) {
                Image(systemName: "bell").font(.system(size: 28, weight: .light)).foregroundStyle(Color.carbon)
                Text("Notifications from \(app.live.phoneName ?? "your phone") show up here.")
                Text("On the phone, open Copier and turn on “Notifications on Mac”.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List(items) { notification in
                NotificationRow(app: app, notification: notification)
            }
            .listStyle(.inset)
            .safeAreaInset(edge: .bottom) {
                HStack {
                    Spacer()
                    Button("Clear All") { app.phoneNotifications.clear() }.controlSize(.small)
                }
                .padding(8)
            }
        }
    }
}

private struct NotificationRow: View {
    let app: AppController
    let notification: PhoneNotification
    @State private var reply = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                if notification.isCall {
                    Image(systemName: "phone.fill").foregroundStyle(.green)
                }
                Text(notification.appName).font(.caption.weight(.medium)).foregroundStyle(.secondary)
                Spacer()
                Text(timeLabel(notification.date)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                Button {
                    app.dismissNotification(notification)
                } label: {
                    Image(systemName: "xmark").font(.caption)
                }
                .buttonStyle(.borderless)
                .help("Dismiss on the phone")
            }
            if !notification.title.isEmpty { Text(notification.title).font(.headline) }
            if !notification.text.isEmpty { Text(notification.text).textSelection(.enabled) }
            HStack(spacing: 8) {
                if let code = OneTimeCode.find(in: notification.title + " " + notification.text) {
                    Button("Copy Code \(code)") { app.copyCode(code) }
                }
                ForEach(Array(notification.actions.enumerated()), id: \.offset) { index, title in
                    if index != notification.replyAction {
                        Button(title) { app.triggerNotificationAction(notification, index: index) }
                    }
                }
            }
            .controlSize(.small)
            if notification.replyAction != nil {
                HStack {
                    TextField("Reply", text: $reply).onSubmit(send)
                    Button("Send", action: send).disabled(reply.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .controlSize(.small)
            }
        }
        .padding(.vertical, 6)
    }

    private func send() {
        let text = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        app.reply(to: notification, text: text)
        reply = ""
    }
}

// MARK: - Bits

private struct DropOverlay: View {
    let phone: String

    var body: some View {
        RoundedRectangle(cornerRadius: 16, style: .continuous)
            .strokeBorder(Color.carbon, style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
            .background(Color.carbon.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(Text("Drop to send to \(phone)").font(.title3.weight(.medium)).foregroundStyle(Color.carbon))
            .padding(12)
            .allowsHitTesting(false)
    }
}

private func timeLabel(_ date: Date) -> String {
    Calendar.current.isDateInToday(date)
        ? date.formatted(date: .omitted, time: .shortened)
        : date.formatted(.dateTime.day().month(.abbreviated).hour().minute())
}

private func captionLabel(_ item: ActivityItem) -> String {
    guard let size = item.size else { return item.title }
    return "\(item.title) · \(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))"
}
