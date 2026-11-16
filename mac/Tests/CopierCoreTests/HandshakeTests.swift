import CryptoKit
import Foundation
import Testing
@testable import CopierCore

@Suite struct HandshakeTests {
    /// Runs both sides of the key schedule. `macHasOnFile` is the phone key the Mac stored at
    /// pairing time — passing a different key models an impostor.
    static func handshake(
        phone: Identity,
        mac: Identity,
        macHasOnFile: Data? = nil,
        phoneHello: Data = Data("hello-i".utf8),
        macSeesHello: Data? = nil
    ) throws -> (phone: SessionKeys, mac: SessionKeys) {
        let phoneEph = P256.KeyAgreement.PrivateKey()
        let macEph = P256.KeyAgreement.PrivateKey()
        let helloR = Data("hello-r".utf8)
        let phoneKeys = try SessionKeys.derive(
            staticKey: phone.privateKey, peerStaticKey: mac.publicKey,
            ephemeralKey: phoneEph, peerEphemeralKey: macEph.publicKey.x963Representation,
            helloInitiator: phoneHello, helloResponder: helloR
        )
        let macKeys = try SessionKeys.derive(
            staticKey: mac.privateKey, peerStaticKey: macHasOnFile ?? phone.publicKey,
            ephemeralKey: macEph, peerEphemeralKey: phoneEph.publicKey.x963Representation,
            helloInitiator: macSeesHello ?? phoneHello, helloResponder: helloR
        )
        return (phoneKeys, macKeys)
    }

    @Test func pairedDevicesAgreeAndExchangeFramesBothWays() throws {
        let keys = try Self.handshake(phone: Identity(), mac: Identity())
        #expect(keys.phone == keys.mac)
        var phoneOut = CipherState(key: keys.phone.initiatorToResponder)
        var macIn = CipherState(key: keys.mac.initiatorToResponder)
        var macOut = CipherState(key: keys.mac.responderToInitiator)
        var phoneIn = CipherState(key: keys.phone.responderToInitiator)
        for i in 0..<3 {
            let up = Data("up \(i)".utf8)
            let down = Data("down \(i)".utf8)
            #expect(try macIn.open(phoneOut.seal(up)) == up)
            #expect(try phoneIn.open(macOut.seal(down)) == down)
        }
    }

    @Test func impostorClaimingPairedIDCannotTalkToMac() throws {
        let keys = try Self.handshake(phone: Identity(), mac: Identity(), macHasOnFile: Identity().publicKey)
        #expect(keys.phone != keys.mac)
        var impostorOut = CipherState(key: keys.phone.initiatorToResponder)
        var macIn = CipherState(key: keys.mac.initiatorToResponder)
        let frame = try impostorOut.seal(Data("auth".utf8))
        #expect(throws: CopierError.decryptFailed) { try macIn.open(frame) }
    }

    @Test func tamperedHelloYieldsMismatchedKeys() throws {
        let keys = try Self.handshake(
            phone: Identity(), mac: Identity(),
            phoneHello: Data("hello-i".utf8), macSeesHello: Data("hello-I".utf8)
        )
        #expect(keys.phone != keys.mac)
    }

    @Test func tamperedFrameIsRejected() throws {
        let key = Crypto.randomBytes(32)
        var sender = CipherState(key: key)
        var receiver = CipherState(key: key)
        var frame = try sender.seal(Data("payload".utf8))
        frame[frame.startIndex] ^= 0x01
        #expect(throws: CopierError.decryptFailed) { try receiver.open(frame) }
    }

    @Test func replayedFrameIsRejected() throws {
        let key = Crypto.randomBytes(32)
        var sender = CipherState(key: key)
        var receiver = CipherState(key: key)
        let frame = try sender.seal(Data("once".utf8))
        #expect(try receiver.open(frame) == Data("once".utf8))
        #expect(throws: CopierError.decryptFailed) { try receiver.open(frame) }
    }

    @Test func shortFrameIsRejected() {
        var receiver = CipherState(key: Crypto.randomBytes(32))
        #expect(throws: CopierError.frameTooShort) { try receiver.open(Data(count: 15)) }
    }

    @Test func badPublicKeyIsRejected() {
        #expect(throws: CopierError.badPublicKey) { try Crypto.ecdh(Identity().privateKey, Data(count: 65)) }
    }
}
