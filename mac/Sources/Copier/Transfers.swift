import CopierCore
import CryptoKit
import Foundation
import UniformTypeIdentifiers

enum TransferError: Error, CustomStringConvertible {
    case notAFile(String)

    var description: String {
        switch self {
        case let .notAFile(name): "“\(name)” is a folder — only files can be sent for now."
        }
    }
}

/// What arrived from the phone over a data connection.
enum Received {
    case file(URL)
    case clip(Data, mime: String, sensitive: Bool)
}

/// Blob transfers (PROTOCOL.md § Data connections). Mac → phone blobs are offered and the phone
/// pulls them; phone → Mac blobs are pushed. One blob per connection. Every blob reports its life
/// (started, progress, delivered/received, failed) under its protocol id, which the timeline uses.
@MainActor
final class Transfers {
    private struct Offer {
        let header: Header
        let source: Source
        let created: Date
    }

    private enum Source {
        case file(URL)
        case data(Data)
    }

    private static let offerLifetime: TimeInterval = 600
    private static let maxOffers = 50  // bounds memory held by in-memory clipboard images
    private static let maxClipBytes: Int64 = 100_000_000  // bigger "clips" are saved as files

    private let downloads: URL
    private var offers: [String: Offer] = [:]

    var onIncoming: @MainActor (Header, PairedPhone) -> Void = { _, _ in }
    var onProgress: @MainActor (_ id: String, _ fraction: Double) -> Void = { _, _ in }
    var onReceived: @MainActor (Received, Header, PairedPhone) -> Void = { _, _, _ in }
    var onDelivered: @MainActor (_ id: String) -> Void = { _ in }
    var onFailed: @MainActor (_ id: String, _ reason: String) -> Void = { _, _ in }

    init(downloads: URL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]) {
        self.downloads = downloads
    }

    func offerFile(_ url: URL, purpose: String) throws -> Header {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentTypeKey])
        guard values.isRegularFile == true, let size = values.fileSize else {
            throw TransferError.notAFile(url.lastPathComponent)
        }
        return register(
            name: url.lastPathComponent,
            size: Int64(size),
            mime: values.contentType?.preferredMIMEType ?? "application/octet-stream",
            purpose: purpose,
            sensitive: false,
            source: .file(url)
        )
    }

    func offerData(_ data: Data, name: String, mime: String, purpose: String, sensitive: Bool) -> Header {
        register(name: name, size: Int64(data.count), mime: mime, purpose: purpose, sensitive: sensitive, source: .data(data))
    }

    private func register(name: String, size: Int64, mime: String, purpose: String, sensitive: Bool, source: Source) -> Header {
        prune()
        let id = UUID().uuidString
        var header = Header("offer")
        header.id = id
        header.name = name
        header.size = size
        header.mime = mime
        header.purpose = purpose
        header.sensitive = sensitive
        offers[id] = Offer(header: header, source: source, created: .now)
        return header
    }

    private func prune() {
        let cutoff = Date.now.addingTimeInterval(-Self.offerLifetime)
        for (id, offer) in offers where offer.created <= cutoff {
            offers[id] = nil
            onFailed(id, "The phone didn't pick it up")
        }
        while offers.count >= Self.maxOffers, let oldest = offers.min(by: { $0.value.created < $1.value.created }) {
            offers[oldest.key] = nil
        }
    }

    /// Runs one authenticated data connection to completion.
    func serve(_ link: Link, phone: PairedPhone) async {
        var transferID: String?
        do {
            let first = try await link.read()
            transferID = first.header.id
            switch first.header.t {
            case "put": try await receive(first.header, over: link, from: phone)
            case "get": try await sendOffer(first.header.id, over: link)
            default: throw LinkError.protocolViolation("unexpected \(first.header.t) on data connection")
            }
        } catch {
            Log.transfer.error("transfer failed: \(String(describing: error), privacy: .public)")
            if let transferID { onFailed(transferID, "Connection lost") }
        }
        link.close()
    }

    // MARK: Phone → Mac

    private func receive(_ put: Header, over link: Link, from phone: PairedPhone) async throws {
        guard let size = put.size, size >= 0, let name = put.name else {
            try await reply(error: "bad-request", over: link)
            return
        }
        let id = put.id ?? UUID().uuidString
        var header = put
        header.id = id
        onIncoming(header, phone)

        let mime = put.mime ?? "application/octet-stream"
        let asClip = put.purpose == "clip" && size <= Self.maxClipBytes
        // Same volume as Downloads, so the final move is an atomic rename.
        let tempDir = try FileManager.default.url(for: .itemReplacementDirectory, in: .userDomainMask, appropriateFor: downloads, create: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }
        let temp = tempDir.appendingPathComponent("incoming")
        guard FileManager.default.createFile(atPath: temp.path, contents: nil) else {
            try await fail(id, code: "io", reason: "Couldn't write to Downloads", over: link)
            return
        }
        let handle = try FileHandle(forWritingTo: temp)
        defer { try? handle.close() }

        let started = Date.now
        var progress = ProgressThrottle(total: size)
        var hasher = SHA256()
        var received: Int64 = 0
        while true {
            let message = try await link.read()
            switch message.header.t {
            case "chunk":
                received += Int64(message.body.count)
                guard received <= size else {
                    try await fail(id, code: "size", reason: "Sent more than it announced", over: link)
                    return
                }
                hasher.update(data: message.body)
                try handle.write(contentsOf: message.body)
                if let fraction = progress.report(received) { onProgress(id, fraction) }
            case "end":
                guard received == size else {
                    try await fail(id, code: "size", reason: "Arrived incomplete", over: link)
                    return
                }
                guard Data(hasher.finalize()).hex == message.header.sha256 else {
                    try await fail(id, code: "hash", reason: "Arrived damaged", over: link)
                    return
                }
                try handle.close()
                let result: Received
                if asClip {
                    result = .clip(try Data(contentsOf: temp), mime: mime, sensitive: put.sensitive ?? false)
                } else {
                    let destination = FileNames.uniqueURL(in: downloads, name: FileNames.sanitize(name))
                    try FileManager.default.moveItem(at: temp, to: destination)  // fails rather than overwrite
                    result = .file(destination)
                }
                try await link.send(Message(Header("done")))
                let seconds = Date.now.timeIntervalSince(started)
                Log.transfer.info("received \(size) bytes (\(mime, privacy: .public), purpose: \(put.purpose ?? "file", privacy: .public)) in \(seconds, format: .fixed(precision: 2)) s")
                onReceived(result, header, phone)
                return
            default:
                throw LinkError.protocolViolation("unexpected \(message.header.t) during upload")
            }
        }
    }

    private func fail(_ id: String, code: String, reason: String, over link: Link) async throws {
        onFailed(id, reason)
        try await reply(error: code, over: link)
    }

    // MARK: Mac → phone

    private func sendOffer(_ id: String?, over link: Link) async throws {
        prune()
        guard let id, let offer = offers[id] else {
            try await reply(error: "gone", over: link)
            return
        }
        var put = offer.header
        put.t = "put"
        try await link.send(Message(put))

        let started = Date.now
        let total = put.size ?? 0
        var progress = ProgressThrottle(total: total)
        var sent: Int64 = 0
        var hasher = SHA256()
        onProgress(id, 0)
        switch offer.source {
        case let .data(data):
            var offset = data.startIndex
            while offset < data.endIndex {
                let chunk = data[offset..<min(offset + Proto.chunkSize, data.endIndex)]
                hasher.update(data: chunk)
                try await link.send(Message(Header("chunk"), body: Data(chunk)))
                offset += chunk.count
                sent += Int64(chunk.count)
                if let fraction = progress.report(sent) { onProgress(id, fraction) }
            }
        case let .file(url):
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            while let chunk = try handle.read(upToCount: Proto.chunkSize), !chunk.isEmpty {
                hasher.update(data: chunk)
                try await link.send(Message(Header("chunk"), body: chunk))  // awaiting = backpressure
                sent += Int64(chunk.count)
                if let fraction = progress.report(sent) { onProgress(id, fraction) }
            }
        }
        var end = Header("end")
        end.sha256 = Data(hasher.finalize()).hex
        try await link.send(Message(end))

        let answer = try await link.read()
        guard answer.header.t == "done" else {
            Log.transfer.error("phone rejected \(id, privacy: .public): \(answer.header.code ?? "unknown", privacy: .public)")
            onFailed(id, "The phone couldn't save it")
            return
        }
        offers[id] = nil
        let seconds = Date.now.timeIntervalSince(started)
        Log.transfer.info("sent \(total) bytes (\(put.mime ?? "?", privacy: .public)) in \(seconds, format: .fixed(precision: 2)) s")
        onDelivered(id)
    }

    private func reply(error code: String, over link: Link) async throws {
        var header = Header("error")
        header.code = code
        try await link.send(Message(header))
    }
}

/// Emits progress only when it moved ≥ 2 % or 150 ms passed, so the UI isn't flooded by 4 000
/// chunk updates for a 1 GB file.
struct ProgressThrottle {
    let total: Int64
    private var lastFraction = -1.0
    private var lastTime = ContinuousClock.now

    init(total: Int64) {
        self.total = total
    }

    mutating func report(_ done: Int64) -> Double? {
        let fraction = total > 0 ? min(1, Double(done) / Double(total)) : 1
        let now = ContinuousClock.now
        guard fraction - lastFraction >= 0.02 || now - lastTime >= .milliseconds(150) || fraction == 1 else { return nil }
        lastFraction = fraction
        lastTime = now
        return fraction
    }
}
