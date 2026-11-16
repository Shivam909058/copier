import Foundation

/// Wire constants from PROTOCOL.md. Changing any of these is a protocol change.
public enum Proto {
    public static let version = 1
    public static let port: UInt16 = 47474
    public static let installPort: UInt16 = 47475  // HTTP page + APK, only while "Add Phone" is open
    public static let serviceType = "_copier._tcp"
    public static let maxHandshakeFrame = 65_536
    public static let maxMessage = 1_048_576
    public static let maxFrame = maxMessage + 16
    public static let maxHeader = 65_536
    public static let chunkSize = 262_144
    public static let remoteClipMarker = "dev.copier.remote"
}

public enum CopierError: Error, Equatable, Sendable {
    case badPublicKey
    case decryptFailed
    case frameTooShort
    case malformedMessage
}

/// Length-prefixed framing shared by the plaintext and encrypted phases.
public enum Frame {
    public static func encode(_ payload: Data) -> Data {
        UInt32(payload.count).bigEndianBytes + payload
    }

    public static func length(ofPrefix prefix: Data) -> Int {
        Int(prefix.readUInt32(at: 0))
    }
}

extension UInt32 {
    var bigEndianBytes: Data { withUnsafeBytes(of: bigEndian) { Data($0) } }
}

extension Data {
    public var hex: String { map { String(format: "%02x", $0) }.joined() }

    /// RFC 4648 §5 base64url without padding — safe inside URLs and QR codes.
    public var base64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }

    public init?(hex: String) {
        guard hex.count.isMultiple(of: 2) else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }

    func readUInt32(at offset: Int) -> UInt32 {
        let start = startIndex + offset
        return self[start..<start + 4].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
    }
}
