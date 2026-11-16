import CopierCore
import Foundation
import Network

enum LinkError: Error {
    case closed
    case notSecured
    case frameTooLarge(Int)
    case protocolViolation(String)
}

/// One TCP connection: length-prefixed frames, plaintext during the handshake, AES-GCM after.
/// The connection runs on the main queue, so all state is confined to the main actor.
@MainActor
final class Link {
    private let connection: NWConnection
    private var sender: CipherState?
    private var receiver: CipherState?
    private var closed = false

    init(_ connection: NWConnection) {
        self.connection = connection
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            MainActor.assumeIsolated {
                if case .failed = state { self?.close() }
            }
        }
        connection.start(queue: .main)
    }

    /// Cancels the socket; every pending read or write fails, which unwinds whoever awaits it.
    func close() {
        guard !closed else { return }
        closed = true
        connection.cancel()
    }

    /// The Mac app is always the responder; the initiator side exists for the in-process tests.
    func secure(_ keys: SessionKeys, asInitiator: Bool = false) {
        sender = CipherState(key: asInitiator ? keys.initiatorToResponder : keys.responderToInitiator)
        receiver = CipherState(key: asInitiator ? keys.responderToInitiator : keys.initiatorToResponder)
    }

    func readPlain() async throws -> Data {
        try await readFrame(max: Proto.maxHandshakeFrame)
    }

    /// Returns the exact JSON bytes sent, which the handshake hashes into the transcript.
    @discardableResult
    func writePlain(_ header: Header) async throws -> Data {
        let json = try header.json()
        try await write(Frame.encode(json))
        return json
    }

    func read() async throws -> Message {
        let frame = try await readFrame(max: Proto.maxFrame)
        guard let plaintext = try receiver?.open(frame) else { throw LinkError.notSecured }
        return try Message.decode(plaintext)
    }

    func send(_ message: Message) async throws {
        // Seal and hand to the socket in one synchronous step, so wire order always matches the
        // nonce counter even when several tasks send at once.
        let plaintext = try message.encoded()
        guard let sealed = try sender?.seal(plaintext) else { throw LinkError.notSecured }
        try await write(Frame.encode(sealed))
    }

    private func readFrame(max: Int) async throws -> Data {
        let length = Frame.length(ofPrefix: try await receive(exactly: 4))
        guard length <= max else { throw LinkError.frameTooLarge(length) }
        return try await receive(exactly: length)
    }

    private func receive(exactly count: Int) async throws -> Data {
        guard !closed else { throw LinkError.closed }
        guard count > 0 else { return Data() }
        return try await withCheckedThrowingContinuation { continuation in
            connection.receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let data, data.count == count {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(throwing: LinkError.closed)
                }
            }
        }
    }

    private func write(_ data: Data) async throws {
        guard !closed else { throw LinkError.closed }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
    }
}
