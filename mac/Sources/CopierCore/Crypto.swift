import CryptoKit
import Foundation

/// A device's long-term P-256 key pair. Its public key is the device's identity.
public struct Identity: Sendable {
    public let privateKey: P256.KeyAgreement.PrivateKey

    public init(privateKey: P256.KeyAgreement.PrivateKey = .init()) {
        self.privateKey = privateKey
    }

    public init(rawPrivateKey: Data) throws {
        privateKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: rawPrivateKey)
    }

    public var publicKey: Data { privateKey.publicKey.x963Representation }
    public var deviceID: String { Crypto.deviceID(publicKey) }
}

public enum Crypto {
    public static func sha256(_ parts: Data...) -> Data {
        var hasher = SHA256()
        for part in parts { hasher.update(data: part) }
        return Data(hasher.finalize())
    }

    public static func deviceID(_ publicKey: Data) -> String {
        sha256(publicKey).prefix(8).hex
    }

    /// Raw ECDH output (the 32-byte x-coordinate), matching Java's `KeyAgreement("ECDH")`.
    public static func ecdh(_ privateKey: P256.KeyAgreement.PrivateKey, _ peerPublicKey: Data) throws -> Data {
        guard let peer = try? P256.KeyAgreement.PublicKey(x963Representation: peerPublicKey) else {
            throw CopierError.badPublicKey
        }
        return try privateKey.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
    }

    public static func hkdf(ikm: Data, salt: Data, info: Data, count: Int) -> Data {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: count
        ).withUnsafeBytes { Data($0) }
    }

    public static func randomBytes(_ count: Int) -> Data {
        SymmetricKey(size: SymmetricKeySize(bitCount: count * 8)).withUnsafeBytes { Data($0) }
    }

    public static func isValidPublicKey(_ data: Data) -> Bool {
        (try? P256.KeyAgreement.PublicKey(x963Representation: data)) != nil
    }
}

/// Per-direction keys from the session handshake (PROTOCOL.md § Session handshake).
public struct SessionKeys: Sendable, Equatable {
    public let initiatorToResponder: Data
    public let responderToInitiator: Data

    /// `hello*` are the exact JSON payloads exchanged on the wire; hashing them binds the keys to
    /// this transcript, so a tampered hello yields keys the peer cannot match.
    public static func derive(
        staticKey: P256.KeyAgreement.PrivateKey,
        peerStaticKey: Data,
        ephemeralKey: P256.KeyAgreement.PrivateKey,
        peerEphemeralKey: Data,
        helloInitiator: Data,
        helloResponder: Data
    ) throws -> SessionKeys {
        let ee = try Crypto.ecdh(ephemeralKey, peerEphemeralKey)
        let ss = try Crypto.ecdh(staticKey, peerStaticKey)
        let okm = Crypto.hkdf(
            ikm: ee + ss,
            salt: Crypto.sha256(helloInitiator, helloResponder),
            info: Data("copier/v1/session".utf8),
            count: 64
        )
        return SessionKeys(initiatorToResponder: Data(okm.prefix(32)), responderToInitiator: Data(okm.suffix(32)))
    }
}

/// One direction of an encrypted connection: AES-256-GCM with a counter nonce. Because the counter
/// is implicit, a replayed, dropped or reordered frame fails authentication.
public struct CipherState: Sendable {
    private let key: SymmetricKey
    public private(set) var counter: UInt64 = 0

    public init(key: Data) {
        self.key = SymmetricKey(data: key)
    }

    public mutating func seal(_ plaintext: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: key, nonce: nonce())
        counter += 1
        return box.ciphertext + box.tag
    }

    public mutating func open(_ frame: Data) throws -> Data {
        guard frame.count >= 16 else { throw CopierError.frameTooShort }
        do {
            let box = try AES.GCM.SealedBox(nonce: nonce(), ciphertext: frame.dropLast(16), tag: frame.suffix(16))
            let plaintext = try AES.GCM.open(box, using: key)
            counter += 1
            return plaintext
        } catch {
            throw CopierError.decryptFailed
        }
    }

    private func nonce() throws -> AES.GCM.Nonce {
        try AES.GCM.Nonce(data: Data(count: 4) + withUnsafeBytes(of: counter.bigEndian) { Data($0) })
    }
}
