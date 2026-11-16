import Foundation
import Testing
@testable import CopierCore

@Suite struct MessageTests {
    @Test func roundTripsHeaderAndBody() throws {
        var header = Header("clip")
        header.sensitive = true
        header.htmlBytes = 9
        let message = Message(header, body: Data("<b>hi</b>hi".utf8))
        #expect(try Message.decode(message.encoded()) == message)
    }

    @Test func headerOnlyMessageHasEmptyBody() throws {
        let decoded = try Message.decode(Message(Header("ping")).encoded())
        #expect(decoded.header.t == "ping")
        #expect(decoded.body.isEmpty)
    }

    @Test func rejectsHeaderLengthPastEnd() {
        #expect(throws: CopierError.malformedMessage) {
            try Message.decode(UInt32(100).bigEndianBytes + Data("{}".utf8))
        }
    }

    @Test func rejectsOversizedHeaderLength() {
        let data = UInt32(Proto.maxHeader + 1).bigEndianBytes + Data(count: Proto.maxHeader + 1)
        #expect(throws: CopierError.malformedMessage) { try Message.decode(data) }
    }

    @Test func ignoresUnknownFields() throws {
        #expect(try Header.parse(Data(#"{"t":"clip","fromTheFuture":42}"#.utf8)).t == "clip")
    }

    @Test func binaryFieldsAreStandardBase64() throws {
        var header = Header("pair-ack")
        header.pub = Data([0xFF, 0x00])
        #expect(String(decoding: try header.json(), as: UTF8.self) == #"{"pub":"/wA=","t":"pair-ack"}"#)
    }

    @Test func statusCarriesAutoCopyState() throws {
        let status = try Header.parse(Data(#"{"t":"status","battery":77,"charging":false,"watcher":false,"msg":"paused for Google Pay"}"#.utf8))
        #expect(status.watcher == false)
        #expect(status.msg == "paused for Google Pay")
        #expect(try Header.parse(Data(#"{"t":"status","battery":77}"#.utf8)).watcher == nil)  // older phone app
    }

    @Test func frameEncodingPrefixesBigEndianLength() {
        let frame = Frame.encode(Data([1, 2, 3]))
        #expect(frame == Data([0, 0, 0, 3, 1, 2, 3]))
        #expect(Frame.length(ofPrefix: frame.prefix(4)) == 3)
    }
}
