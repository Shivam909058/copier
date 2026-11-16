import CopierCore
import CryptoKit
import Foundation
import Network

/// An authenticated control connection to one paired phone.
@MainActor
final class PhoneSession {
    let id: String
    let name: String
    let link: Link

    init(phone: PairedPhone, link: Link) {
        id = phone.id
        name = phone.name
        self.link = link
    }

    /// Fire-and-forget. A failed send closes the link, which ends the session.
    func send(_ message: Message) {
        Task {
            do {
                try await link.send(message)
            } catch {
                Log.net.error("send \(message.header.t, privacy: .public) failed: \(String(describing: error), privacy: .public)")
                link.close()
            }
        }
    }
}

/// Accepts phone connections (PROTOCOL.md: the Mac is always the responder) and runs the
/// handshake, pairing and control loops. Everything runs on the main actor.
@MainActor
final class Server {
    private let identity: Identity
    private let store: Store
    let deviceName: String
    private let appVersion: String
    private var listener: NWListener?
    private var localOnly = false
    private(set) var boundPort: UInt16?
    private(set) var sessions: [String: PhoneSession] = [:]
    private var pairingInProgress = false
    private var invite: (token: Data, expires: Date)?

    var onSessionsChanged: @MainActor () -> Void = {}
    var onMessage: @MainActor (Message, PhoneSession) -> Void = { _, _ in }
    var onDataConnection: @MainActor (Link, PairedPhone) async -> Void = { link, _ in link.close() }
    var confirmPairing: @MainActor (_ phoneName: String, _ code: String) async -> Bool = { _, _ in false }
    var dismissPairingPrompt: @MainActor () -> Void = {}
    var onPaired: @MainActor (PairedPhone) -> Void = { _ in }

    init(identity: Identity, store: Store, deviceName: String, appVersion: String) {
        self.identity = identity
        self.store = store
        self.deviceName = deviceName
        self.appVersion = appVersion
    }

    /// `localOnly` (tests) binds to 127.0.0.1 and skips Bonjour, so nothing reaches the LAN.
    /// Port 0 picks a free port; read it back from `boundPort` once ready.
    func start(port: UInt16 = Proto.port, localOnly: Bool = false) {
        self.localOnly = localOnly
        listen(on: NWEndpoint.Port(rawValue: port) ?? .any)
    }

    func stop() {
        listener?.cancel()
        listener = nil
        boundPort = nil
        for session in sessions.values { session.link.close() }
    }

    /// Before sleep: tell phones goodbye, then withdraw the Bonjour service. The phone reconnects
    /// when the service reappears (see `resume()`), instead of dialing an address that a Bonjour
    /// sleep proxy (Apple TV, HomePod) would answer by waking the Mac.
    func pause() {
        for session in sessions.values { session.send(Message(Header("bye"))) }
        listener?.cancel()
        listener = nil
        boundPort = nil
    }

    /// On wake: listen and announce again.
    func resume() {
        guard listener == nil else { return }
        listen(on: NWEndpoint.Port(rawValue: Proto.port) ?? .any)
    }

    func disconnect(phoneID: String) {
        sessions[phoneID]?.link.close()
    }

    /// Arms QR pairing: the token travels only inside the QR code. Single use, 10 minutes.
    func beginInvite(token: Data) {
        invite = (token, Date.now.addingTimeInterval(600))
    }

    func endInvite() {
        invite = nil
    }

    // MARK: Listener

    private func listen(on port: NWEndpoint.Port) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        // Keepalive drops phones that vanished without closing (left Wi-Fi, died) in ~90 s.
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 60
        tcp.keepaliveInterval = 10
        tcp.keepaliveCount = 3
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.allowLocalEndpointReuse = true
        do {
            let listener: NWListener
            if localOnly {
                parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: port)
                listener = try NWListener(using: parameters)
            } else {
                listener = try NWListener(using: parameters, on: port)
            }
            if !localOnly {
                listener.service = NWListener.Service(
                    name: deviceName,
                    type: Proto.serviceType,
                    domain: nil,
                    txtRecord: NWTXTRecord(["id": identity.deviceID, "v": String(Proto.version)])
                )
            }
            listener.newConnectionHandler = { [weak self] connection in
                MainActor.assumeIsolated { self?.accept(connection) }
            }
            listener.stateUpdateHandler = { [weak self] state in
                MainActor.assumeIsolated { self?.listenerChanged(state, requestedPort: port) }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            Log.net.error("listener could not start: \(String(describing: error), privacy: .public)")
        }
    }

    private func listenerChanged(_ state: NWListener.State, requestedPort: NWEndpoint.Port) {
        switch state {
        case .ready:
            boundPort = listener?.port?.rawValue
            Log.net.info("listening on port \(self.boundPort ?? 0)")
        case let .failed(error):
            Log.net.error("listener failed: \(String(describing: error), privacy: .public)")
            listener?.cancel()
            listener = nil
            if requestedPort != .any, case .posix(.EADDRINUSE) = error {
                listen(on: .any)  // Bonjour advertises whichever port we end up on.
            } else {
                Task {
                    try? await Task.sleep(for: .seconds(5))
                    self.listen(on: requestedPort)
                }
            }
        default:
            break
        }
    }

    private func accept(_ connection: NWConnection) {
        let link = Link(connection)
        link.start()
        Task { await self.handshake(link) }
    }

    // MARK: Handshake

    private func handshake(_ link: Link) async {
        let deadline = Task {
            try await Task.sleep(for: .seconds(10))
            link.close()
        }
        defer { deadline.cancel() }
        do {
            let helloBytes = try await link.readPlain()
            let hello = try Header.parse(helloBytes)
            guard hello.v == Proto.version else {
                var refusal = Header("error")
                refusal.code = "version"
                refusal.v = Proto.version
                _ = try? await link.writePlain(refusal)
                link.close()
                return
            }
            switch hello.t {
            case "pair":
                deadline.cancel()  // pairing waits on a human and has its own 60 s limit
                await runPairing(link, request: hello)
            case "hello":
                guard let opened = try await openSession(link, hello: hello, helloBytes: helloBytes) else {
                    link.close()
                    return
                }
                deadline.cancel()
                let phone = refreshName(opened.phone, opened.auth.name)
                if hello.kind == "control" {
                    await runControl(PhoneSession(phone: phone, link: link))
                } else {
                    await onDataConnection(link, phone)
                }
            default:
                link.close()
            }
        } catch {
            Log.net.info("connection ended: \(String(describing: error), privacy: .public)")
            link.close()
        }
    }

    private func openSession(_ link: Link, hello: Header, helloBytes: Data) async throws -> (phone: PairedPhone, auth: Header)? {
        guard hello.kind == "control" || hello.kind == "data", let peerEphemeral = hello.eph else {
            var refusal = Header("error")
            refusal.code = "bad-request"
            _ = try? await link.writePlain(refusal)
            return nil
        }
        guard let id = hello.id, let phone = store.phone(id: id) else {
            var refusal = Header("error")
            refusal.code = "unpaired"
            _ = try? await link.writePlain(refusal)
            return nil
        }
        let ephemeral = P256.KeyAgreement.PrivateKey()
        var reply = Header("hello")
        reply.v = Proto.version
        reply.id = identity.deviceID
        reply.eph = ephemeral.publicKey.x963Representation
        reply.nonce = Crypto.randomBytes(16)
        let replyBytes = try await link.writePlain(reply)
        link.secure(try SessionKeys.derive(
            staticKey: identity.privateKey,
            peerStaticKey: phone.publicKey,
            ephemeralKey: ephemeral,
            peerEphemeralKey: peerEphemeral,
            helloInitiator: helloBytes,
            helloResponder: replyBytes
        ))
        let auth = try await link.read()  // fails to decrypt unless the phone holds the paired key
        guard auth.header.t == "auth" else { throw LinkError.protocolViolation("expected auth, got \(auth.header.t)") }
        var ok = Header("auth-ok")
        ok.name = deviceName
        ok.app = appVersion
        try await link.send(Message(ok))
        return (phone, auth.header)
    }

    private func refreshName(_ phone: PairedPhone, _ name: String?) -> PairedPhone {
        guard let name, !name.isEmpty, name != phone.name else { return phone }
        var renamed = phone
        renamed.name = name
        store.save(renamed)
        return renamed
    }

    private func runControl(_ session: PhoneSession) async {
        sessions[session.id]?.link.close()  // a reconnecting phone replaces its stale session
        sessions[session.id] = session
        onSessionsChanged()
        Log.net.info("control session up: \(session.id, privacy: .public)")
        do {
            while true {
                let message = try await session.link.read()
                switch message.header.t {
                case "ping": session.send(Message(Header("pong")))
                case "bye": session.link.close()
                default: onMessage(message, session)
                }
            }
        } catch {
            Log.net.info("control session down: \(String(describing: error), privacy: .public)")
        }
        session.link.close()
        if sessions[session.id] === session {
            sessions[session.id] = nil
            onSessionsChanged()
        }
    }

    // MARK: Pairing

    private func runPairing(_ link: Link, request: Header) async {
        defer { link.close() }
        guard !pairingInProgress else {
            var busy = Header("error")
            busy.code = "busy"
            _ = try? await link.writePlain(busy)
            return
        }
        guard let phoneID = request.id, let commit = request.commit, let phoneName = request.name, !phoneName.isEmpty else {
            return
        }
        pairingInProgress = true
        defer { pairingInProgress = false }
        // Closing the link after 60 s unblocks every pending read below.
        let deadline = Task {
            try await Task.sleep(for: .seconds(60))
            link.close()
        }
        defer { deadline.cancel() }

        do {
            let macNonce = Crypto.randomBytes(16)
            var ack = Header("pair-ack")
            ack.v = Proto.version
            ack.id = identity.deviceID
            ack.name = deviceName
            ack.pub = identity.publicKey
            ack.nonce = macNonce
            try await link.writePlain(ack)

            let reveal = try Header.parse(try await link.readPlain())
            guard reveal.t == "pair-reveal",
                  let phoneKey = reveal.pub, let phoneNonce = reveal.nonce, phoneNonce.count == 16,
                  Pairing.commitment(publicKey: phoneKey, nonce: phoneNonce) == commit,
                  Crypto.deviceID(phoneKey) == phoneID,
                  Crypto.isValidPublicKey(phoneKey)
            else {
                Log.net.error("pairing rejected: reveal does not match the commitment")
                return
            }

            if let proof = reveal.proof {
                // QR pairing: knowing the token from the QR replaces the human code comparison.
                guard let invite, invite.expires > .now,
                      Pairing.isValidQRProof(proof, token: invite.token, phonePublicKey: phoneKey,
                                             macPublicKey: identity.publicKey, phoneNonce: phoneNonce, macNonce: macNonce)
                else {
                    Log.net.error("QR pairing rejected: wrong or expired token")
                    return
                }
                self.invite = nil  // single use
                try await link.writePlain(Header("pair-ok"))
                guard try Header.parse(try await link.readPlain()).t == "pair-ok" else { return }
                completePairing(PairedPhone(id: phoneID, name: phoneName, publicKey: phoneKey))
                return
            }

            let code = Pairing.code(phonePublicKey: phoneKey, macPublicKey: identity.publicKey, phoneNonce: phoneNonce, macNonce: macNonce)
            // The phone's verdict can arrive while our prompt is still open; a cancel closes it.
            let phoneVerdict = Task { () -> Bool in
                let reply = try? await link.readPlain()
                let accepted = reply.flatMap { try? Header.parse($0) }?.t == "pair-ok"
                if !accepted { self.dismissPairingPrompt() }
                return accepted
            }
            let userAccepted = await confirmPairing(phoneName, code)
            _ = try? await link.writePlain(Header(userAccepted ? "pair-ok" : "pair-cancel"))
            guard userAccepted, await phoneVerdict.value else { return }
            completePairing(PairedPhone(id: phoneID, name: phoneName, publicKey: phoneKey))
        } catch {
            Log.net.info("pairing ended: \(String(describing: error), privacy: .public)")
        }
    }

    private func completePairing(_ phone: PairedPhone) {
        store.save(phone)
        Log.net.info("paired with \(phone.id, privacy: .public)")
        onPaired(phone)
    }
}
