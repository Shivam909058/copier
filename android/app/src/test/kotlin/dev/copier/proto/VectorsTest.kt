package dev.copier.proto

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Reproduces every value in testvectors/v1.json (recorded by the Swift/CryptoKit suite). */
class VectorsTest {
    private val v = JSONObject(
        File(requireNotNull(System.getProperty("copier.vectors")) { "copier.vectors system property not set" }).readText(),
    )

    private fun hex(key: String) = v.getString(key).hexToBytes()
    private fun str(key: String) = v.getString(key)
    private fun priv(key: String) = Crypto.privateKeyFromScalar(hex(key))

    @Test
    fun ecdhAgreesFromBothSidesAndMatchesVectors() {
        assertEquals(str("ecdh_ss"), Crypto.ecdh(priv("static_phone_priv"), hex("static_mac_pub")).toHex())
        assertEquals(str("ecdh_ss"), Crypto.ecdh(priv("static_mac_priv"), hex("static_phone_pub")).toHex())
        assertEquals(str("ecdh_ee"), Crypto.ecdh(priv("eph_phone_priv"), hex("eph_mac_pub")).toHex())
        assertEquals(str("ecdh_ee"), Crypto.ecdh(priv("eph_mac_priv"), hex("eph_phone_pub")).toHex())
    }

    @Test
    fun deviceIdsMatch() {
        assertEquals(str("phone_id"), Crypto.deviceId(hex("static_phone_pub")))
        assertEquals(str("mac_id"), Crypto.deviceId(hex("static_mac_pub")))
    }

    @Test
    fun sessionKeysMatchFromBothSides() {
        val helloI = str("hello_initiator").toByteArray(Charsets.UTF_8)
        val helloR = str("hello_responder").toByteArray(Charsets.UTF_8)
        assertEquals(str("transcript_hash"), Crypto.sha256(helloI, helloR).toHex())

        val phone = SessionKeys.derive(priv("static_phone_priv"), hex("static_mac_pub"), priv("eph_phone_priv"), hex("eph_mac_pub"), helloI, helloR)
        val mac = SessionKeys.derive(priv("static_mac_priv"), hex("static_phone_pub"), priv("eph_mac_priv"), hex("eph_phone_pub"), helloI, helloR)
        for (keys in listOf(phone, mac)) {
            assertEquals(str("k_i2r"), keys.initiatorToResponder.toHex())
            assertEquals(str("k_r2i"), keys.responderToInitiator.toHex())
        }
    }

    @Test
    fun framesEncryptAndDecryptWithCounters() {
        val phoneOut = CipherState(hex("k_i2r"))
        assertEquals(str("frame_i2r_0_ciphertext"), phoneOut.seal(hex("frame_i2r_0_plaintext")).toHex())
        assertEquals(str("frame_i2r_1_ciphertext"), phoneOut.seal(hex("frame_i2r_1_plaintext")).toHex())

        val macIn = CipherState(hex("k_i2r"))
        assertArrayEquals(hex("frame_i2r_0_plaintext"), macIn.open(hex("frame_i2r_0_ciphertext")))
        assertArrayEquals(hex("frame_i2r_1_plaintext"), macIn.open(hex("frame_i2r_1_ciphertext")))

        assertEquals(str("frame_r2i_0_ciphertext"), CipherState(hex("k_r2i")).seal(hex("frame_r2i_0_plaintext")).toHex())
        assertArrayEquals(hex("frame_r2i_0_plaintext"), CipherState(hex("k_r2i")).open(hex("frame_r2i_0_ciphertext")))
    }

    @Test
    fun swiftEncodedMessagesDecode() {
        val auth = Message.decode(hex("frame_i2r_0_plaintext"))
        assertEquals("auth", auth.header.t)
        assertEquals("Test Phone", auth.header.name)
        assertEquals("0.1.0", auth.header.app)
        assertEquals(0, auth.body.size)

        val clip = Message.decode(hex("frame_i2r_1_plaintext"))
        assertEquals("clip", clip.header.t)
        assertEquals(false, clip.header.sensitive)
        assertEquals(9, clip.header.htmlBytes)
        assertEquals("<b>hi</b>hi", String(clip.body, Charsets.UTF_8))

        val authOk = Message.decode(hex("frame_r2i_0_plaintext"))
        assertEquals("auth-ok", authOk.header.t)
        assertEquals("Test Mac", authOk.header.name)
    }

    @Test
    fun pairingCommitmentAndCodeMatch() {
        assertEquals(str("pair_commit"), Pairing.commitment(hex("static_phone_pub"), hex("pair_phone_nonce")).toHex())
        assertEquals(
            str("pair_code"),
            Pairing.code(hex("static_phone_pub"), hex("static_mac_pub"), hex("pair_phone_nonce"), hex("pair_mac_nonce")),
        )
    }

    @Test
    fun qrProofMatches() {
        val proof = Pairing.qrProof(hex("qr_token"), hex("static_phone_pub"), hex("static_mac_pub"), hex("pair_phone_nonce"), hex("pair_mac_nonce"))
        assertEquals(str("qr_proof"), proof.toHex())
    }

    @Test
    fun qrPayloadParses() {
        val payload = QrPayload.parse(str("qr_payload"))
        assertEquals(listOf("192.168.1.5", "10.0.0.2"), payload.hosts)
        assertEquals(47474, payload.port)
        assertArrayEquals(hex("static_mac_pub"), payload.macPublicKey)
        assertArrayEquals(hex("qr_token"), payload.token)
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val okm = Crypto.hkdf(hex("hkdf_rfc5869_ikm"), hex("hkdf_rfc5869_salt"), hex("hkdf_rfc5869_info"), 42)
        assertEquals(str("hkdf_rfc5869_okm"), okm.toHex())
    }
}
