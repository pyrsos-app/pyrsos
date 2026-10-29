// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Published test vectors: each primitive is wired the way its standard says. */
class CryptoVectorsTest {
    private fun h(s: String) = s.replace(" ", "").hexToBytes()

    @Test
    fun x25519Rfc7748() {
        val alicePriv = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPub = h("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", Crypto.x25519Public(alicePriv).toHex())
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            Crypto.x25519(alicePriv, bobPub).toHex(),
        )
    }

    @Test
    fun x25519RefusesLowOrderPoint() {
        val zero = ByteArray(32)
        val priv = Crypto.x25519KeyPair().private
        try {
            Crypto.x25519(priv, zero)
            throw AssertionError("accepted a low-order point")
        } catch (_: CryptoException) {
        }
    }

    @Test
    fun ed25519Rfc8032Test1() {
        val priv = h("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val pub = h("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = Crypto.sign(priv, ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            sig.toHex(),
        )
        assertTrue(Crypto.verify(pub, ByteArray(0), sig))
        assertFalse(Crypto.verify(pub, byteArrayOf(1), sig))
    }

    @Test
    fun chacha20Poly1305Rfc8439() {
        val key = h("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = h("070000004041424344454647")
        val aad = h("50515253c0c1c2c3c4c5c6c7")
        val pt = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the " +
            "future, sunscreen would be it.").toByteArray()
        val ct = Crypto.seal(key, nonce, pt, aad)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", ct.copyOfRange(ct.size - 16, ct.size).toHex())
        assertEquals("d31a8d34648e60db7b86afbc53ef7ec2", ct.copyOf(16).toHex())
        assertArrayEquals(pt, Crypto.open(key, nonce, ct, aad))
    }

    @Test
    fun hkdfRfc5869Case1() {
        val ikm = h("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = h("000102030405060708090a0b0c")
        val gen = org.bouncycastle.crypto.generators.HKDFBytesGenerator(org.bouncycastle.crypto.digests.SHA256Digest())
        gen.init(org.bouncycastle.crypto.params.HKDFParameters(ikm, salt, h("f0f1f2f3f4f5f6f7f8f9")))
        val viaBc = ByteArray(42).also { gen.generateBytes(it, 0, 42) }
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            viaBc.toHex(),
        )
    }

    @Test
    fun hmacRfc4231Case2() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Crypto.hmac("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray()).toHex(),
        )
    }

    @Test
    fun mlKemRoundTripAndSizes() {
        val kp = Crypto.mlKemKeyPair()
        assertEquals(Crypto.ML_KEM_PUBLIC, kp.public.size)
        val enc = Crypto.mlKemEncapsulate(kp.public)
        assertEquals(Crypto.ML_KEM_CIPHERTEXT, enc.ciphertext.size)
        assertArrayEquals(enc.secret, Crypto.mlKemDecapsulate(kp.private, enc.ciphertext))
        // ML-KEM rejects implicitly: a changed ciphertext yields a different secret, never an error.
        val bad = enc.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(enc.secret.contentEquals(Crypto.mlKemDecapsulate(kp.private, bad)))
    }

    @Test
    fun scryptRfc7914() {
        // RFC 7914 section 12, third vector (N = 16384, r = 8, p = 1).
        val out = Crypto.scrypt("pleaseletmein".toByteArray(), "SodiumChloride".toByteArray(), n = 16384, length = 64)
        assertEquals(
            "7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2" +
                "d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887",
            out.toHex(),
        )
    }
}
