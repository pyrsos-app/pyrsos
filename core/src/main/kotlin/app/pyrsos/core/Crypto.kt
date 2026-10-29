// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import java.security.SecureRandom

/** Thrown when an authenticated decryption or a key agreement fails. Never a parse error. */
open class CryptoException(message: String) : Exception(message)

class KeyPair(val private: ByteArray, val public: ByteArray)

class Encapsulation(val secret: ByteArray, val ciphertext: ByteArray)

/**
 * Every primitive Pyrsos uses, in one place, on Bouncy Castle's lightweight API.
 * X25519 and Ed25519 (RFC 7748 / 8032), ML-KEM-768 (FIPS 203), HKDF and HMAC on SHA-256,
 * ChaCha20-Poly1305 (RFC 8439).
 */
object Crypto {
    const val KEY = 32
    const val NONCE = 12
    const val TAG = 16
    const val ML_KEM_PUBLIC = 1184
    const val ML_KEM_CIPHERTEXT = 1088

    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun x25519KeyPair(): KeyPair {
        val priv = X25519PrivateKeyParameters(rng)
        return KeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    fun x25519Public(private: ByteArray): ByteArray =
        X25519PrivateKeyParameters(private, 0).generatePublicKey().encoded

    fun x25519(private: ByteArray, public: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(private, 0))
        val out = ByteArray(agreement.agreementSize)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(public, 0), out, 0)
        } catch (e: IllegalStateException) {
            // Bouncy Castle refuses a low-order public key, whose result would be all zero.
            throw CryptoException("x25519: ${e.message}")
        }
        return out
    }

    fun ed25519KeyPair(): KeyPair {
        val priv = Ed25519PrivateKeyParameters(rng)
        return KeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    fun sign(private: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(private, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(public: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (public.size != 32 || signature.size != 64) return false
        val key = try {
            Ed25519PublicKeyParameters(public, 0)
        } catch (_: IllegalArgumentException) {
            return false // not a point on the curve: nothing can verify against it
        }
        val signer = Ed25519Signer()
        signer.init(false, key)
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    fun mlKemKeyPair(): KeyPair {
        val gen = MLKEMKeyPairGenerator()
        gen.init(MLKEMKeyGenerationParameters(rng, MLKEMParameters.ml_kem_768))
        val kp = gen.generateKeyPair()
        return KeyPair(
            (kp.private as MLKEMPrivateKeyParameters).encoded,
            (kp.public as MLKEMPublicKeyParameters).encoded,
        )
    }

    fun mlKemEncapsulate(public: ByteArray): Encapsulation {
        if (public.size != ML_KEM_PUBLIC) throw CryptoException("ml-kem public key size ${public.size}")
        val result = MLKEMGenerator(rng)
            .generateEncapsulated(MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, public))
        return Encapsulation(result.secret, result.encapsulation)
    }

    fun mlKemDecapsulate(private: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size != ML_KEM_CIPHERTEXT) throw CryptoException("ml-kem ciphertext size ${ciphertext.size}")
        return MLKEMExtractor(MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, private))
            .extractSecret(ciphertext)
    }

    /** scrypt (RFC 7914), memory-hard: for short secrets such as a PIN, never for keys. */
    fun scrypt(secret: ByteArray, salt: ByteArray, n: Int = 1 shl 15, length: Int = 32): ByteArray =
        SCrypt.generate(secret, salt, n, 8, 1, length)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = SHA256Digest()
        for (p in parts) d.update(p, 0, p.size)
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        for (p in parts) mac.update(p, 0, p.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    fun hkdf(salt: ByteArray, ikm: ByteArray, info: String, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.UTF_8)))
        return ByteArray(length).also { gen.generateBytes(it, 0, length) }
    }

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), TAG * 8, nonce, aad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), TAG * 8, nonce, aad))
        val out = ByteArray(c.getOutputSize(ciphertext.size))
        try {
            val n = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            c.doFinal(out, n)
        } catch (e: InvalidCipherTextException) {
            throw CryptoException("aead: authentication failed")
        }
        return out
    }
}
