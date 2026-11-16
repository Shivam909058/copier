import AppKit
import CopierCore
import CoreImage
import CryptoKit
import Foundation
import Network
import Testing
@testable import Copier

/// Drives the real `Server` + `Transfers` over loopback with a fake phone that speaks
/// PROTOCOL.md as the initiator — the same steps the Android app performs.
@MainActor
@Suite(.serialized)
struct ServerTests {
    @Test func pairingThenControlSessionDeliversClipAndAnswersPing() async throws {
        try await withHarness { h in
            let phone = FakePhone()
            let mac = try await phone.pair(port: h.port)
            #expect(mac.id == h.macIdentity.deviceID)
            try await eventually { h.store.phone(id: phone.id) != nil }

            let control = try await phone.open("control", port: h.port, macKey: mac.key)
            defer { control.close() }
            var clip = Header("clip")
            clip.htmlBytes = 9
            try await control.send(Message(clip, body: Data("<b>hi</b>hi".utf8)))
            try await eventually { !h.messages.isEmpty }
            #expect(h.messages.first?.header.t == "clip")
            #expect(h.messages.first?.body == Data("<b>hi</b>hi".utf8))
            #expect(h.server.sessions[phone.id] != nil)

            try await control.send(Message(Header("ping")))
            #expect(try await control.read().header.t == "pong")
        }
    }

    @Test func qrPairingWithTheRightTokenSkipsThePrompt() async throws {
        try await withHarness { h in
            h.server.confirmPairing = { _, _ in
                Issue.record("QR pairing must not show the code prompt")
                return false
            }
            let token = Crypto.randomBytes(16)
            h.server.beginInvite(token: token)
            let phone = FakePhone()
            let mac = try await phone.pair(port: h.port, token: token)
            #expect(mac.key == h.macIdentity.publicKey)
            try await eventually { h.store.phone(id: phone.id) != nil }
        }
    }

    @Test func qrPairingWithAWrongTokenIsRejected() async throws {
        try await withHarness { h in
            h.server.beginInvite(token: Crypto.randomBytes(16))
            await #expect(throws: (any Error).self) {
                _ = try await FakePhone().pair(port: h.port, token: Crypto.randomBytes(16))
            }
            #expect(h.store.phones.isEmpty)
        }
    }

    @Test func qrTokenWorksOnlyOnce() async throws {
        try await withHarness { h in
            let token = Crypto.randomBytes(16)
            h.server.beginInvite(token: token)
            _ = try await FakePhone().pair(port: h.port, token: token)
            try await eventually { h.store.phones.count == 1 }
            await #expect(throws: (any Error).self) {
                _ = try await FakePhone().pair(port: h.port, token: token)
            }
            #expect(h.store.phones.count == 1)
        }
    }

    @Test func installServerServesOnlyThePageAndTheAPK() async throws {
        let apk = FileManager.default.temporaryDirectory.appendingPathComponent("copier-test-\(UUID().uuidString).apk")
        try Data("fake apk".utf8).write(to: apk)
        defer { try? FileManager.default.removeItem(at: apk) }
        let server = InstallServer(apk: apk)
        server.start(port: 0, localOnly: true)
        defer { server.stop() }
        try await eventually { server.boundPort != nil }
        let port = try #require(server.boundPort)

        let page = try await httpGet(port: port, path: "/")
        #expect(page.status == 200)
        #expect(String(decoding: page.body, as: UTF8.self).contains("intent://pair?"))
        let download = try await httpGet(port: port, path: "/copier.apk")
        #expect(download.status == 200)
        #expect(download.body == Data("fake apk".utf8))
        #expect(try await httpGet(port: port, path: "/../etc/passwd").status == 404)
    }

    @Test func qrCodeDecodesBackToTheInstallURL() throws {
        let url = "http://192.168.1.5:47475/#h=192.168.1.5,10.0.0.2&p=47474&k=\(Identity().publicKey.base64URL)&t=\(Crypto.randomBytes(16).base64URL)"
        let image = try #require(QRCode.image(for: url))
        let pixels = try #require(image.cgImage(forProposedRect: nil, context: nil, hints: nil))
        // Detectors need several pixels per module; scale up without smoothing.
        let scaled = CIImage(cgImage: pixels).samplingNearest().transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        let detector = try #require(CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]))
        let decoded = detector.features(in: scaled).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
        #expect(decoded == [url])
    }

    @Test func unpairedPhoneIsRefused() async throws {
        try await withHarness { h in
            let reply = try await FakePhone().helloReply(port: h.port)
            #expect(reply.t == "error")
            #expect(reply.code == "unpaired")
        }
    }

    @Test func versionMismatchIsRefused() async throws {
        try await withHarness { h in
            let reply = try await FakePhone().helloReply(port: h.port, version: 2)
            #expect(reply.code == "version")
            #expect(reply.v == Proto.version)
        }
    }

    @Test func impostorUsingAPairedIDCannotAuthenticate() async throws {
        try await withHarness { h in
            let real = FakePhone()
            let mac = try await real.pair(port: h.port)
            try await eventually { h.store.phone(id: real.id) != nil }

            let impostor = FakePhone(claimingID: real.id)
            await #expect(throws: (any Error).self) {
                _ = try await impostor.open("control", port: h.port, macKey: mac.key)
            }
            #expect(h.server.sessions.isEmpty)
        }
    }

    @Test func pushedFileIsVerifiedAndNeverOverwrites() async throws {
        try await withHarness { h in
            let phone = FakePhone()
            let mac = try await phone.pair(port: h.port)
            try await eventually { h.store.phone(id: phone.id) != nil }
            let existing = h.downloads.appendingPathComponent("note.txt")
            try Data("keep me".utf8).write(to: existing)

            let payload = Data((0..<600_000).map { UInt8($0 % 251) })  // three chunks
            let reply = try await phone.push(payload, name: "note.txt", port: h.port, macKey: mac.key)
            #expect(reply.t == "done")
            try await eventually { !h.received.isEmpty }
            guard case let .file(url)? = h.received.first else {
                Issue.record("expected a saved file, got \(String(describing: h.received.first))")
                return
            }
            #expect(url.lastPathComponent == "note (1).txt")
            #expect(try Data(contentsOf: url) == payload)
            #expect(try Data(contentsOf: existing) == Data("keep me".utf8))
            #expect(h.events.first == "incoming:note.txt")
            #expect(h.progress.last == 1.0)
            #expect(h.progress == h.progress.sorted())  // monotonic, throttled
        }
    }

    @Test func pushWithWrongHashIsRejectedAndLeavesNoFile() async throws {
        try await withHarness { h in
            let phone = FakePhone()
            let mac = try await phone.pair(port: h.port)
            try await eventually { h.store.phone(id: phone.id) != nil }

            let reply = try await phone.push(Data("tampered".utf8), name: "x.bin", sha256: String(repeating: "0", count: 64), port: h.port, macKey: mac.key)
            #expect(reply.t == "error")
            #expect(reply.code == "hash")
            #expect(try FileManager.default.contentsOfDirectory(atPath: h.downloads.path).isEmpty)
            #expect(h.received.isEmpty)
            #expect(h.events.contains { $0.hasPrefix("failed:") && $0.hasSuffix(":Arrived damaged") })
        }
    }

    @Test func offeredBlobCanBePulled() async throws {
        try await withHarness { h in
            let phone = FakePhone()
            let mac = try await phone.pair(port: h.port)
            try await eventually { h.store.phone(id: phone.id) != nil }

            let blob = Data((0..<300_000).map { UInt8($0 % 7) })
            let offer = h.transfers.offerData(blob, name: "Clipboard.png", mime: "image/png", purpose: "clip", sensitive: false)
            let offerID = try #require(offer.id)
            let pulled = try await phone.pull(offerID, port: h.port, macKey: mac.key)
            #expect(pulled.put?.name == "Clipboard.png")
            #expect(pulled.put?.purpose == "clip")
            #expect(pulled.data == blob)
            try await eventually { h.events.contains("delivered:\(offerID)") }
        }
    }

    @Test func unknownOfferIsGone() async throws {
        try await withHarness { h in
            let phone = FakePhone()
            let mac = try await phone.pair(port: h.port)
            try await eventually { h.store.phone(id: phone.id) != nil }

            let pulled = try await phone.pull(UUID().uuidString, port: h.port, macKey: mac.key)
            #expect(pulled.put == nil)
            #expect(pulled.error == "gone")
        }
    }
}

// MARK: - Harness

@MainActor
final class Harness {
    let macIdentity = Identity()
    let suiteName = "dev.copier.tests.\(UUID().uuidString)"
    let downloads: URL
    let store: Store
    let server: Server
    let transfers: Transfers
    var port: UInt16 = 0
    var messages: [Message] = []
    var received: [Received] = []
    var events: [String] = []
    var progress: [Double] = []

    init() throws {
        downloads = FileManager.default.temporaryDirectory.appendingPathComponent(suiteName)
        try FileManager.default.createDirectory(at: downloads, withIntermediateDirectories: true)
        store = Store(defaults: try #require(UserDefaults(suiteName: suiteName)))
        transfers = Transfers(downloads: downloads)
        server = Server(identity: macIdentity, store: store, deviceName: "Test Mac", appVersion: "test")
        server.confirmPairing = { _, _ in true }
        server.onMessage = { [weak self] message, _ in self?.messages.append(message) }
        server.onDataConnection = { [transfers] link, phone in await transfers.serve(link, phone: phone) }
        transfers.onReceived = { [weak self] received, _, _ in self?.received.append(received) }
        transfers.onIncoming = { [weak self] header, _ in self?.events.append("incoming:\(header.name ?? "")") }
        transfers.onProgress = { [weak self] _, fraction in self?.progress.append(fraction) }
        transfers.onDelivered = { [weak self] id in self?.events.append("delivered:\(id)") }
        transfers.onFailed = { [weak self] id, reason in self?.events.append("failed:\(id):\(reason)") }
    }

    func start() async throws {
        server.start(port: 0, localOnly: true)
        try await eventually { self.server.boundPort != nil }
        port = try #require(server.boundPort)
    }

    func tearDown() {
        server.stop()
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        try? FileManager.default.removeItem(at: downloads)
    }
}

@MainActor
func withHarness(_ body: (Harness) async throws -> Void) async throws {
    let harness = try Harness()
    defer { harness.tearDown() }
    try await harness.start()
    try await body(harness)
}

struct Timeout: Error {}

/// Minimal HTTP/1.1 GET over a raw TCP connection (avoids URLSession's ATS rules in tests).
@MainActor
func httpGet(port: UInt16, path: String) async throws -> (status: Int, body: Data) {
    let connection = NWConnection(host: "127.0.0.1", port: try #require(NWEndpoint.Port(rawValue: port)), using: .tcp)
    connection.start(queue: .main)
    defer { connection.cancel() }
    connection.send(content: Data("GET \(path) HTTP/1.1\r\nHost: test\r\n\r\n".utf8), completion: .idempotent)
    var response = Data()
    while true {
        let (chunk, done): (Data?, Bool) = try await withCheckedThrowingContinuation { continuation in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, isComplete, error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume(returning: (data, isComplete)) }
            }
        }
        if let chunk { response.append(chunk) }
        if done { break }
    }
    let headerEnd = try #require(response.range(of: Data("\r\n\r\n".utf8)))
    let statusLine = String(decoding: response[..<headerEnd.lowerBound], as: UTF8.self).split(separator: " ")
    return (Int(statusLine.count > 1 ? statusLine[1] : "") ?? 0, Data(response[headerEnd.upperBound...]))
}

@MainActor
func eventually(within limit: Duration = .seconds(5), _ condition: () -> Bool) async throws {
    let deadline = ContinuousClock.now + limit
    while !condition() {
        guard ContinuousClock.now < deadline else { throw Timeout() }
        try await Task.sleep(for: .milliseconds(10))
    }
}

// MARK: - Fake phone (initiator)

@MainActor
struct FakePhone {
    let identity = Identity()
    let id: String

    init(claimingID: String? = nil) {
        id = claimingID ?? identity.deviceID
    }

    private func connect(_ port: UInt16) throws -> Link {
        let link = Link(NWConnection(host: "127.0.0.1", port: try #require(NWEndpoint.Port(rawValue: port)), using: .tcp))
        link.start()
        return link
    }

    /// With `token`, runs QR pairing (adds the proof); without, the 6-digit flow, which the harness
    /// auto-confirms on the Mac. Throws if the Mac refuses.
    func pair(port: UInt16, token: Data? = nil) async throws -> (id: String, key: Data) {
        let link = try connect(port)
        defer { link.close() }
        let nonce = Crypto.randomBytes(16)
        var request = Header("pair")
        request.v = Proto.version
        request.id = id
        request.name = "Test Phone"
        request.commit = Pairing.commitment(publicKey: identity.publicKey, nonce: nonce)
        try await link.writePlain(request)

        let ack = try Header.parse(try await link.readPlain())
        let macKey = try #require(ack.pub)
        #expect(ack.t == "pair-ack")
        #expect(ack.id == Crypto.deviceID(macKey))

        var reveal = Header("pair-reveal")
        reveal.pub = identity.publicKey
        reveal.nonce = nonce
        if let token {
            reveal.proof = Pairing.qrProof(
                token: token, phonePublicKey: identity.publicKey, macPublicKey: macKey,
                phoneNonce: nonce, macNonce: try #require(ack.nonce)
            )
        }
        try await link.writePlain(reveal)
        try await link.writePlain(Header("pair-ok"))
        guard try Header.parse(try await link.readPlain()).t == "pair-ok" else {
            throw LinkError.protocolViolation("pairing refused")
        }
        return (try #require(ack.id), macKey)
    }

    func helloReply(port: UInt16, version: Int = Proto.version) async throws -> Header {
        let link = try connect(port)
        defer { link.close() }
        try await link.writePlain(hello(kind: "control", ephemeral: P256.KeyAgreement.PrivateKey(), version: version))
        return try Header.parse(try await link.readPlain())
    }

    func open(_ kind: String, port: UInt16, macKey: Data) async throws -> Link {
        let link = try connect(port)
        let ephemeral = P256.KeyAgreement.PrivateKey()
        let helloBytes = try await link.writePlain(hello(kind: kind, ephemeral: ephemeral))
        let replyBytes = try await link.readPlain()
        let reply = try Header.parse(replyBytes)
        guard reply.t == "hello", let macEphemeral = reply.eph else {
            link.close()
            throw LinkError.protocolViolation("refused: \(reply.code ?? reply.t)")
        }
        link.secure(try SessionKeys.derive(
            staticKey: identity.privateKey, peerStaticKey: macKey,
            ephemeralKey: ephemeral, peerEphemeralKey: macEphemeral,
            helloInitiator: helloBytes, helloResponder: replyBytes
        ), asInitiator: true)
        var auth = Header("auth")
        auth.name = "Test Phone"
        auth.app = "test"
        try await link.send(Message(auth))
        guard try await link.read().header.t == "auth-ok" else {
            link.close()
            throw LinkError.protocolViolation("expected auth-ok")
        }
        return link
    }

    func push(_ data: Data, name: String, sha256: String? = nil, port: UInt16, macKey: Data) async throws -> Header {
        let link = try await open("data", port: port, macKey: macKey)
        defer { link.close() }
        var put = Header("put")
        put.id = UUID().uuidString
        put.name = name
        put.size = Int64(data.count)
        put.mime = "application/octet-stream"
        put.purpose = "file"
        try await link.send(Message(put))
        var offset = 0
        while offset < data.count {
            let end = min(offset + Proto.chunkSize, data.count)
            try await link.send(Message(Header("chunk"), body: data.subdata(in: offset..<end)))
            offset = end
        }
        var finish = Header("end")
        finish.sha256 = sha256 ?? Crypto.sha256(data).hex
        try await link.send(Message(finish))
        return try await link.read().header
    }

    func pull(_ offerID: String, port: UInt16, macKey: Data) async throws -> (put: Header?, data: Data, error: String?) {
        let link = try await open("data", port: port, macKey: macKey)
        defer { link.close() }
        var get = Header("get")
        get.id = offerID
        try await link.send(Message(get))
        let first = try await link.read().header
        guard first.t == "put" else { return (nil, Data(), first.code) }
        var data = Data()
        while true {
            let message = try await link.read()
            if message.header.t == "chunk" {
                data.append(message.body)
            } else {
                #expect(message.header.t == "end")
                #expect(message.header.sha256 == Crypto.sha256(data).hex)
                try await link.send(Message(Header("done")))
                return (first, data, nil)
            }
        }
    }

    private func hello(kind: String, ephemeral: P256.KeyAgreement.PrivateKey, version: Int = Proto.version) -> Header {
        var hello = Header("hello")
        hello.v = version
        hello.kind = kind
        hello.id = id
        hello.eph = ephemeral.publicKey.x963Representation
        hello.nonce = Crypto.randomBytes(16)
        return hello
    }
}
