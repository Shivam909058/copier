import Foundation

/// Every JSON header on the wire. One flat struct instead of a type per message: the wire format
/// is flat, unknown fields are ignored, and each handler checks the fields its type requires.
public struct Header: Codable, Sendable, Equatable {
    public var t: String
    public var v: Int?
    public var kind: String?
    public var id: String?
    public var name: String?
    public var app: String?
    public var eph: Data?
    public var nonce: Data?
    public var pub: Data?
    public var commit: Data?
    public var proof: Data?
    public var code: String?
    public var msg: String?
    public var sensitive: Bool?
    public var htmlBytes: Int?
    public var size: Int64?
    public var mime: String?
    public var purpose: String?
    public var sha256: String?
    public var battery: Int?
    public var charging: Bool?
    // Notifications, ring and links (PROTOCOL.md § Control messages).
    public var key: String?
    public var pkg: String?
    public var appName: String?
    public var title: String?
    public var text: String?
    public var time: Int64?
    public var actions: [String]?
    public var replyAction: Int?
    public var call: Bool?
    public var index: Int?
    public var url: String?
    /// `status`: whether the phone's automatic-copy service is running.
    public var watcher: Bool?
    /// `status`: the phone can turn that service back on by itself (one-time USB setup done).
    public var managed: Bool?

    public init(_ t: String) {
        self.t = t
    }

    /// Sorted keys keep the output deterministic, which the test vectors rely on.
    public func json() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(self)
    }

    public static func parse(_ data: Data) throws -> Header {
        try JSONDecoder().decode(Header.self, from: data)
    }
}

/// The plaintext inside an encrypted frame: `u32 headerLength ‖ header JSON ‖ body`.
public struct Message: Sendable, Equatable {
    public var header: Header
    public var body: Data

    public init(_ header: Header, body: Data = Data()) {
        self.header = header
        self.body = body
    }

    public func encoded() throws -> Data {
        let json = try header.json()
        return UInt32(json.count).bigEndianBytes + json + body
    }

    public static func decode(_ data: Data) throws -> Message {
        guard data.count >= 4 else { throw CopierError.malformedMessage }
        let length = Int(data.readUInt32(at: 0))
        guard length <= Proto.maxHeader, data.count - 4 >= length else { throw CopierError.malformedMessage }
        let start = data.startIndex + 4
        let header = try Header.parse(data[start..<start + length])
        return Message(header, body: Data(data[(start + length)...]))
    }
}
