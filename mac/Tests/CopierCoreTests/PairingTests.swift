import Foundation
import Testing
@testable import CopierCore

@Suite struct PairingTests {
    static let phone = Data(repeating: 1, count: 65)
    static let mac = Data(repeating: 2, count: 65)
    static let phoneNonce = Data(repeating: 3, count: 16)
    static let macNonce = Data(repeating: 4, count: 16)

    @Test func codeIsAlwaysSixDigits() {
        for _ in 0..<200 {
            let code = Pairing.code(
                phonePublicKey: Identity().publicKey, macPublicKey: Identity().publicKey,
                phoneNonce: Crypto.randomBytes(16), macNonce: Crypto.randomBytes(16)
            )
            #expect(code.count == 6 && code.allSatisfy(\.isASCII) && code.allSatisfy(\.isNumber))
        }
    }

    @Test func codeDependsOnEveryInput() {
        let base = Pairing.code(phonePublicKey: Self.phone, macPublicKey: Self.mac, phoneNonce: Self.phoneNonce, macNonce: Self.macNonce)
        let other = Data(repeating: 9, count: 65)
        let otherNonce = Data(repeating: 9, count: 16)
        #expect(base != Pairing.code(phonePublicKey: other, macPublicKey: Self.mac, phoneNonce: Self.phoneNonce, macNonce: Self.macNonce))
        #expect(base != Pairing.code(phonePublicKey: Self.phone, macPublicKey: other, phoneNonce: Self.phoneNonce, macNonce: Self.macNonce))
        #expect(base != Pairing.code(phonePublicKey: Self.phone, macPublicKey: Self.mac, phoneNonce: otherNonce, macNonce: Self.macNonce))
        #expect(base != Pairing.code(phonePublicKey: Self.phone, macPublicKey: Self.mac, phoneNonce: Self.phoneNonce, macNonce: otherNonce))
    }

    @Test func commitmentBindsKeyAndNonce() {
        let commit = Pairing.commitment(publicKey: Self.phone, nonce: Self.phoneNonce)
        #expect(commit != Pairing.commitment(publicKey: Self.phone, nonce: Self.macNonce))
        #expect(commit != Pairing.commitment(publicKey: Self.mac, nonce: Self.phoneNonce))
    }

    @Test func qrProofVerifiesOnlyForTheSameTokenAndExchange() {
        let token = Data(repeating: 7, count: 16)
        let proof = Pairing.qrProof(token: token, phonePublicKey: Self.phone, macPublicKey: Self.mac, phoneNonce: Self.phoneNonce, macNonce: Self.macNonce)
        #expect(Pairing.isValidQRProof(proof, token: token, phonePublicKey: Self.phone, macPublicKey: Self.mac, phoneNonce: Self.phoneNonce, macNonce: Self.macNonce))
        #expect(!Pairing.isValidQRProof(proof, token: Data(repeating: 8, count: 16), phonePublicKey: Self.phone, macPublicKey: Self.mac, phoneNonce: Self.phoneNonce, macNonce: Self.macNonce))
        // A proof captured from one exchange is useless with a different phone key.
        #expect(!Pairing.isValidQRProof(proof, token: token, phonePublicKey: Self.mac, macPublicKey: Self.mac, phoneNonce: Self.phoneNonce, macNonce: Self.macNonce))
    }

    @Test func base64URLHasNoPaddingOrUnsafeCharacters() {
        #expect(Data([0xFB, 0xFF]).base64URL == "-_8")
    }

    @Test func displayGroupsDigits() {
        #expect(Pairing.display("012345") == "012 345")
    }
}
