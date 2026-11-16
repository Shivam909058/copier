import CryptoKit
import Foundation

/// Commit–reveal short-authentication-string pairing (PROTOCOL.md § Pairing), plus the QR
/// variant where a one-time token from the QR code replaces the human code comparison.
public enum Pairing {
    /// HMAC over the whole exchange: only a phone that saw the QR knows `token`, and binding the
    /// keys and nonces means a captured proof can't be replayed into a different exchange.
    public static func qrProof(token: Data, phonePublicKey: Data, macPublicKey: Data, phoneNonce: Data, macNonce: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(
            for: qrTranscript(phonePublicKey, macPublicKey, phoneNonce, macNonce),
            using: SymmetricKey(data: token)
        ))
    }

    /// Constant-time check of a phone's QR proof.
    public static func isValidQRProof(_ proof: Data, token: Data, phonePublicKey: Data, macPublicKey: Data, phoneNonce: Data, macNonce: Data) -> Bool {
        HMAC<SHA256>.isValidAuthenticationCode(
            proof,
            authenticating: qrTranscript(phonePublicKey, macPublicKey, phoneNonce, macNonce),
            using: SymmetricKey(data: token)
        )
    }

    private static func qrTranscript(_ phonePublicKey: Data, _ macPublicKey: Data, _ phoneNonce: Data, _ macNonce: Data) -> Data {
        Data("copier/v1/qr".utf8) + phonePublicKey + macPublicKey + phoneNonce + macNonce
    }

    public static func commitment(publicKey: Data, nonce: Data) -> Data {
        Crypto.sha256(publicKey, nonce)
    }

    /// Six decimal digits both users compare. The phone commits to its key before seeing the
    /// Mac's, so an attacker cannot search for keys that make two different codes collide.
    public static func code(phonePublicKey: Data, macPublicKey: Data, phoneNonce: Data, macNonce: Data) -> String {
        let hash = Crypto.sha256(Data("copier/v1/sas".utf8), phonePublicKey, macPublicKey, phoneNonce, macNonce)
        let value = hash.readUInt32(at: 0) % 1_000_000
        let digits = String(value)
        return String(repeating: "0", count: 6 - digits.count) + digits
    }

    public static func display(_ code: String) -> String {
        "\(code.prefix(3)) \(code.suffix(3))"
    }
}
