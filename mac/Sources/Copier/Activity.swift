import AppKit
import Foundation
import ImageIO
import Observation
import UniformTypeIdentifiers

/// One thing that crossed between the devices. Ids are the protocol ids (clip id, put id, offer id),
/// so the phone's timeline and this one talk about the same item.
struct ActivityItem: Identifiable, Codable, Equatable, Sendable {
    enum Direction: String, Codable, Sendable { case fromPhone, toPhone }
    enum Kind: String, Codable, Sendable { case text, image, file }
    enum State: Codable, Equatable, Sendable {
        case waiting                 // sent or offered, no confirmation yet
        case transferring(Double)    // 0…1
        case delivered               // the phone confirmed (ack or done)
        case received                // arrived here and was applied or saved
        case failed(String)
    }

    let id: String
    let direction: Direction
    let kind: Kind
    var title: String
    var text: String?        // never set for sensitive clips
    var size: Int64?
    var fileURL: URL?
    var hasThumbnail = false
    var sensitive = false
    var state: State
    var date = Date.now
}

/// Live phone status shown in the window header and the menu.
@MainActor
@Observable
final class LiveState {
    var phoneName: String?
    var connected = false
    var hasPairedPhone = false
    var battery: Int?
    var charging = false
    var ringing = false
    var notificationsAllowed = true
    /// Whether automatic copy is running on the phone (nil: unknown, e.g. an older phone app).
    var autoCopy: Bool?
    /// Why it's off, when the phone says ("paused for Google Pay").
    var autoCopyNote: String?
    /// The phone turns automatic copy back on by itself (after `copier usb-setup`).
    var autoCopyManaged = false
}

/// A notification mirrored from the phone. Held in memory only: notification text never touches disk.
struct PhoneNotification: Identifiable, Equatable, Sendable {
    let key: String
    let pkg: String
    let appName: String
    let title: String
    let text: String
    let date: Date
    let actions: [String]
    let replyAction: Int?
    let isCall: Bool

    var id: String { key }
}

@MainActor
@Observable
final class PhoneNotifications {
    private(set) var items: [PhoneNotification] = []

    /// Returns true when the key is new (an update to an existing notification returns false).
    @discardableResult
    func upsert(_ notification: PhoneNotification) -> Bool {
        let isNew = !items.contains { $0.key == notification.key }
        items.removeAll { $0.key == notification.key }
        items.insert(notification, at: 0)
        if items.count > 100 { items.removeLast(items.count - 100) }
        return isNew
    }

    func item(_ key: String) -> PhoneNotification? {
        items.first { $0.key == key }
    }

    func remove(key: String) {
        items.removeAll { $0.key == key }
    }

    func clear() {
        items.removeAll()
    }
}

/// The timeline: newest first, persisted to Application Support, capped. Thumbnails and received
/// clipboard images live next to it and are deleted with their item.
@MainActor
@Observable
final class ActivityStore {
    static let limit = 300

    private(set) var items: [ActivityItem] = []
    @ObservationIgnored private let directory: URL
    @ObservationIgnored private var saveScheduled = false
    @ObservationIgnored private let thumbnails = NSCache<NSString, NSImage>()

    init(directory: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Copier", isDirectory: true)) {
        self.directory = directory
        load()
    }

    var clipsDirectory: URL { directory.appendingPathComponent("clips", isDirectory: true) }
    private var thumbnailsDirectory: URL { directory.appendingPathComponent("thumbs", isDirectory: true) }
    private var file: URL { directory.appendingPathComponent("activity.json") }

    func thumbnailURL(for id: String) -> URL {
        thumbnailsDirectory.appendingPathComponent("\(id).jpg")
    }

    /// Cached so scrolling the timeline doesn't re-read JPEGs from disk on every redraw.
    func thumbnail(for id: String) -> NSImage? {
        if let cached = thumbnails.object(forKey: id as NSString) { return cached }
        guard let image = NSImage(contentsOf: thumbnailURL(for: id)) else { return nil }
        thumbnails.setObject(image, forKey: id as NSString)
        return image
    }

    func item(_ id: String) -> ActivityItem? {
        items.first { $0.id == id }
    }

    func add(_ item: ActivityItem) {
        items.removeAll { $0.id == item.id }
        items.insert(item, at: 0)
        while items.count > Self.limit {
            deleteFiles(of: items.removeLast())
        }
        scheduleSave()
    }

    func update(_ id: String, _ change: (inout ActivityItem) -> Void) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        change(&items[index])
        scheduleSave()
    }

    func remove(_ id: String) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        deleteFiles(of: items.remove(at: index))
        scheduleSave()
    }

    func clear() {
        items.removeAll()
        try? FileManager.default.removeItem(at: thumbnailsDirectory)
        try? FileManager.default.removeItem(at: clipsDirectory)
        scheduleSave()
    }

    /// Writes a ≤ 360 px JPEG thumbnail; returns whether it worked. ImageIO decodes a downscaled
    /// copy directly, so even a 12 MP photo costs a few milliseconds.
    @discardableResult
    func storeThumbnail(for id: String, imageData: Data) -> Bool {
        writeThumbnail(CGImageSourceCreateWithData(imageData as CFData, nil), id: id)
    }

    @discardableResult
    func storeThumbnail(for id: String, fileURL: URL) -> Bool {
        writeThumbnail(CGImageSourceCreateWithURL(fileURL as CFURL, nil), id: id)
    }

    private func writeThumbnail(_ source: CGImageSource?, id: String) -> Bool {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 360,
        ]
        guard let source, let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return false }
        do {
            try FileManager.default.createDirectory(at: thumbnailsDirectory, withIntermediateDirectories: true)
        } catch {
            return false
        }
        guard let destination = CGImageDestinationCreateWithURL(thumbnailURL(for: id) as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            return false
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
        return CGImageDestinationFinalize(destination)
    }

    private func deleteFiles(of item: ActivityItem) {
        try? FileManager.default.removeItem(at: thumbnailURL(for: item.id))
        if let url = item.fileURL, url.path.hasPrefix(clipsDirectory.path) {
            try? FileManager.default.removeItem(at: url)  // only our own clip copies, never Downloads
        }
    }

    // MARK: Persistence

    private func scheduleSave() {
        guard !saveScheduled else { return }
        saveScheduled = true
        Task {
            try? await Task.sleep(for: .milliseconds(300))  // coalesce progress bursts into one write
            saveScheduled = false
            save()
        }
    }

    func save() {
        let persisted = items.map { item -> ActivityItem in
            var copy = item
            if copy.sensitive { copy.text = nil }
            return copy
        }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try JSONEncoder().encode(persisted).write(to: file, options: .atomic)
        } catch {
            Log.app.error("saving activity failed: \(String(describing: error), privacy: .public)")
        }
    }

    private func load() {
        guard let data = try? Data(contentsOf: file),
              let saved = try? JSONDecoder().decode([ActivityItem].self, from: data)
        else { return }
        // Transfers can't survive a restart; text that was sent but never acknowledged stays "sent".
        items = saved.map { item in
            var item = item
            switch item.state {
            case .transferring: item.state = .failed("Interrupted")
            case .waiting where item.kind != .text: item.state = .failed("Interrupted")
            default: break
            }
            return item
        }
    }
}
