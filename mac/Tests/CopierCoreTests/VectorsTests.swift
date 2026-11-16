import CryptoKit
import Foundation
import Testing
@testable import CopierCore

/// Fixed inputs → expected outputs, shared with the Kotlin suite (android/app/src/test).
/// After an intentional protocol change, regenerate with `COPIER_RECORD_VECTORS=1 swift test`.
@Suite struct VectorsTests {
    static let file = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()  // CopierCoreTests
        .deletingLastPathComponent()  // Tests
        .deletingLastPathComponent()  // mac
        .deletingLastPathComponent()  // repo root
        .appendingPathComponent("testvectors/v1.json")

    static func scalar(_ label: String) -> Data {
        Crypto.sha256(Data("copier/test/\(label)".utf8))
    }

    static func compute() throws -> [String: String] {
        let phone = try Identity(rawPrivateKey: scalar("static-phone"))
        let mac = try Identity(rawPrivateKey: scalar("static-mac"))
        let phoneEph = try P256.KeyAgreement.PrivateKey(rawRepresentation: scalar("eph-phone"))
        let macEph = try P256.KeyAgreement.PrivateKey(rawRepresentation: scalar("eph-mac"))
        let phoneEphPub = phoneEph.publicKey.x963Representation
        let macEphPub = macEph.publicKey.x963Representation

        let helloI = #"{"eph":"\#(phoneEphPub.base64EncodedString())","id":"\#(phone.deviceID)","kind":"control","nonce":"AAECAwQFBgcICQoLDA0ODw==","t":"hello","v":1}"#
        let helloR = #"{"eph":"\#(macEphPub.base64EncodedString())","id":"\#(mac.deviceID)","nonce":"EBESExQVFhcYGRobHB0eHw==","t":"hello","v":1}"#

        let keys = try SessionKeys.derive(
            staticKey: phone.privateKey, peerStaticKey: mac.publicKey,
            ephemeralKey: phoneEph, peerEphemeralKey: macEphPub,
            helloInitiator: Data(helloI.utf8), helloResponder: Data(helloR.utf8)
        )
        let responderKeys = try SessionKeys.derive(
            staticKey: mac.privateKey, peerStaticKey: phone.publicKey,
            ephemeralKey: macEph, peerEphemeralKey: phoneEphPub,
            helloInitiator: Data(helloI.utf8), helloResponder: Data(helloR.utf8)
        )
        #expect(keys == responderKeys)

        var auth = Header("auth")
        auth.name = "Test Phone"
        auth.app = "0.1.0"
        var authOK = Header("auth-ok")
        authOK.name = "Test Mac"
        authOK.app = "0.1.0"
        var clip = Header("clip")
        clip.sensitive = false
        clip.htmlBytes = 9
        let i2r0 = try Message(auth).encoded()
        let i2r1 = try Message(clip, body: Data("<b>hi</b>hi".utf8)).encoded()
        let r2i0 = try Message(authOK).encoded()

        var phoneOut = CipherState(key: keys.initiatorToResponder)
        var macOut = CipherState(key: keys.responderToInitiator)

        let phoneNonce = Data((0..<16).map { UInt8($0) })
        let macNonce = Data((16..<32).map { UInt8($0) })
        let qrToken = Data((32..<48).map { UInt8($0) })

        return [
            "static_phone_priv": phone.privateKey.rawRepresentation.hex,
            "static_mac_priv": mac.privateKey.rawRepresentation.hex,
            "eph_phone_priv": phoneEph.rawRepresentation.hex,
            "eph_mac_priv": macEph.rawRepresentation.hex,
            "static_phone_pub": phone.publicKey.hex,
            "static_mac_pub": mac.publicKey.hex,
            "eph_phone_pub": phoneEphPub.hex,
            "eph_mac_pub": macEphPub.hex,
            "phone_id": phone.deviceID,
            "mac_id": mac.deviceID,
            "ecdh_ss": try Crypto.ecdh(phone.privateKey, mac.publicKey).hex,
            "ecdh_ee": try Crypto.ecdh(phoneEph, macEphPub).hex,
            "hello_initiator": helloI,
            "hello_responder": helloR,
            "transcript_hash": Crypto.sha256(Data(helloI.utf8), Data(helloR.utf8)).hex,
            "k_i2r": keys.initiatorToResponder.hex,
            "k_r2i": keys.responderToInitiator.hex,
            "frame_i2r_0_plaintext": i2r0.hex,
            "frame_i2r_0_ciphertext": try phoneOut.seal(i2r0).hex,
            "frame_i2r_1_plaintext": i2r1.hex,
            "frame_i2r_1_ciphertext": try phoneOut.seal(i2r1).hex,
            "frame_r2i_0_plaintext": r2i0.hex,
            "frame_r2i_0_ciphertext": try macOut.seal(r2i0).hex,
            "pair_phone_nonce": phoneNonce.hex,
            "pair_mac_nonce": macNonce.hex,
            "pair_commit": Pairing.commitment(publicKey: phone.publicKey, nonce: phoneNonce).hex,
            "pair_code": Pairing.code(
                phonePublicKey: phone.publicKey, macPublicKey: mac.publicKey,
                phoneNonce: phoneNonce, macNonce: macNonce
            ),
            "qr_token": qrToken.hex,
            "qr_proof": Pairing.qrProof(
                token: qrToken, phonePublicKey: phone.publicKey, macPublicKey: mac.publicKey,
                phoneNonce: phoneNonce, macNonce: macNonce
            ).hex,
            "qr_payload": "h=192.168.1.5,10.0.0.2&p=47474&k=\(mac.publicKey.base64URL)&t=\(qrToken.base64URL)",
            "hkdf_rfc5869_ikm": Data(repeating: 0x0b, count: 22).hex,
            "hkdf_rfc5869_salt": "000102030405060708090a0b0c",
            "hkdf_rfc5869_info": "f0f1f2f3f4f5f6f7f8f9",
            "hkdf_rfc5869_okm": "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
        ]
    }

    @Test func computedValuesMatchRecordedFile() throws {
        let computed = try Self.compute()
        if ProcessInfo.processInfo.environment["COPIER_RECORD_VECTORS"] == "1" {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
            try FileManager.default.createDirectory(
                at: Self.file.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            try encoder.encode(computed).write(to: Self.file)
            return
        }
        let recorded = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: Self.file))
        #expect(Set(recorded.keys) == Set(computed.keys))
        for (key, value) in computed.sorted(by: { $0.key < $1.key }) {
            #expect(recorded[key] == value, "\(key)")
        }
    }

    @Test func hkdfMatchesRFC5869TestCase1() throws {
        let okm = Crypto.hkdf(
            ikm: Data(repeating: 0x0b, count: 22),
            salt: try #require(Data(hex: "000102030405060708090a0b0c")),
            info: try #require(Data(hex: "f0f1f2f3f4f5f6f7f8f9")),
            count: 42
        )
        #expect(okm.hex == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    }
}
