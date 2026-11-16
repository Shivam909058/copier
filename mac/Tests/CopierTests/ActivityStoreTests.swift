import AppKit
import Foundation
import Testing
@testable import Copier

@MainActor
@Suite struct ActivityStoreTests {
    private func makeStore() -> (ActivityStore, URL) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("copier-activity-\(UUID().uuidString)")
        return (ActivityStore(directory: directory), directory)
    }

    private func text(_ id: String, _ value: String = "hi", state: ActivityItem.State = .waiting, sensitive: Bool = false) -> ActivityItem {
        ActivityItem(id: id, direction: .toPhone, kind: .text, title: "Text", text: value, sensitive: sensitive, state: state)
    }

    @Test func keepsNewestFirstAndUpdatesInPlace() {
        let (store, directory) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        store.add(text("a"))
        store.add(text("b"))
        store.update("a") { $0.state = .delivered }
        #expect(store.items.map(\.id) == ["b", "a"])
        #expect(store.item("a")?.state == .delivered)
    }

    @Test func capsHistoryAtTheLimit() {
        let (store, directory) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        for index in 0..<(ActivityStore.limit + 5) { store.add(text("\(index)")) }
        #expect(store.items.count == ActivityStore.limit)
        #expect(store.item("0") == nil)
        #expect(store.items.first?.id == "\(ActivityStore.limit + 4)")
    }

    @Test func survivesRestartWithoutPasswordsAndMarksInterruptedTransfers() {
        let (store, directory) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        store.add(text("sent-text"))
        store.add(text("password", "hunter2", state: .received, sensitive: true))
        store.add(ActivityItem(id: "photo", direction: .fromPhone, kind: .image, title: "IMG.jpg", size: 10, state: .transferring(0.4)))
        store.save()

        let reloaded = ActivityStore(directory: directory)
        #expect(reloaded.items.count == 3)
        #expect(reloaded.item("password")?.text == nil)
        #expect(reloaded.item("photo")?.state == .failed("Interrupted"))
        #expect(reloaded.item("sent-text")?.state == .waiting)  // text without an ack is just "sent"
        #expect(reloaded.item("sent-text")?.text == "hi")
    }

    @Test func writesSmallThumbnailsAndClearsThem() throws {
        let (store, directory) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        let rep = try #require(NSBitmapImageRep(
            bitmapDataPlanes: nil, pixelsWide: 1200, pixelsHigh: 800, bitsPerSample: 8, samplesPerPixel: 4,
            hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
        ))
        let png = try #require(rep.representation(using: .png, properties: [:]))

        #expect(store.storeThumbnail(for: "img", imageData: png))
        let thumbnail = try #require(NSImage(contentsOf: store.thumbnailURL(for: "img")))
        let pixels = try #require(thumbnail.representations.first)
        #expect(max(pixels.pixelsWide, pixels.pixelsHigh) <= 360)

        store.clear()
        #expect(!FileManager.default.fileExists(atPath: store.thumbnailURL(for: "img").path))
    }
}
