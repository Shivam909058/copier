import AppKit
import CopierCore
import SwiftUI
import Testing
@testable import Copier

/// Renders the Copier window with sample history to a PNG for visual review.
/// Opt-in: `COPIER_SNAPSHOT=/path/out.png swift test --filter Snapshot` (dark: COPIER_SNAPSHOT_DARK=1).
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["COPIER_SNAPSHOT"] != nil))
struct SnapshotTests {
    @Test func renderRelayWindow() throws {
        let environment = ProcessInfo.processInfo.environment
        let output = URL(fileURLWithPath: try #require(environment["COPIER_SNAPSHOT"]))
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("copier-snapshot-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }

        let app = AppController(identity: Identity(), activityDirectory: directory)
        app.live.phoneName = "realme GT 6T"
        app.live.connected = true
        app.live.hasPairedPhone = true
        app.live.battery = 82
        app.live.charging = true
        app.live.notificationsAllowed = false

        let photo = try samplePhoto()
        let store = app.activity
        store.add(ActivityItem(id: "1", direction: .fromPhone, kind: .text, title: "Text", text: "Meet at 5 near the metro gate, I'll be in the blue jacket", state: .received, date: .now.addingTimeInterval(-900)))
        store.add(ActivityItem(id: "2", direction: .toPhone, kind: .text, title: "Text", text: "https://github.com/anthropics/claude-code", state: .delivered, date: .now.addingTimeInterval(-600)))
        store.add(ActivityItem(id: "3", direction: .toPhone, kind: .file, title: "Q3-roadmap.pdf", size: 2_480_000, state: .transferring(0.62), date: .now.addingTimeInterval(-300)))
        store.add(ActivityItem(id: "4", direction: .fromPhone, kind: .image, title: "IMG_20261116_224012.jpg", size: 10_888_570, hasThumbnail: store.storeThumbnail(for: "4", imageData: photo), state: .received, date: .now.addingTimeInterval(-120)))
        store.add(ActivityItem(id: "5", direction: .fromPhone, kind: .text, title: "Text", sensitive: true, state: .received, date: .now.addingTimeInterval(-30)))

        // cacheDisplay skips the window's own background, which a real window paints behind the view.
        let host = NSHostingView(rootView: RelayView(app: app).background(Color(nsColor: .windowBackgroundColor)))
        let height = Double(environment["COPIER_SNAPSHOT_HEIGHT"] ?? "") ?? 620
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 780, height: height), styleMask: [.titled], backing: .buffered, defer: false)
        window.appearance = NSAppearance(named: environment["COPIER_SNAPSHOT_DARK"] == "1" ? .darkAqua : .aqua)
        window.contentView = host
        host.layoutSubtreeIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))  // let SwiftUI settle its layout

        let rep = try #require(host.bitmapImageRepForCachingDisplay(in: host.bounds))
        host.cacheDisplay(in: host.bounds, to: rep)
        try #require(rep.representation(using: .png, properties: [:])).write(to: output)
    }

    /// A gradient "photo" so the thumbnail path is exercised with real image data.
    private func samplePhoto() throws -> Data {
        let size = NSSize(width: 1600, height: 1200)
        let image = NSImage(size: size, flipped: false) { rect in
            NSGradient(colors: [.systemTeal, .systemIndigo, .systemOrange])?.draw(in: rect, angle: 35)
            NSColor.white.withAlphaComponent(0.85).setFill()
            NSBezierPath(ovalIn: NSRect(x: 1050, y: 760, width: 260, height: 260)).fill()
            return true
        }
        let tiff = try #require(image.tiffRepresentation)
        return try #require(NSBitmapImageRep(data: tiff)?.representation(using: .jpeg, properties: [:]))
    }
}
