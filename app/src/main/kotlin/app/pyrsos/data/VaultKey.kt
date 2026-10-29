// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.pyrsos.core.Crypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The hardware half of the vault key: 32 random bytes, kept on disk only encrypted by a key in the
 * phone's secure hardware that works **only while the phone is unlocked**. On Android 12 and later
 * the system enforces this cryptographically: when the screen locks, the means to use the key are
 * dropped from memory until the person unlocks the phone again with their own screen lock.
 *
 * So opening the vault needs both the Pyrsos PIN and a phone unlocked by its owner. A tool that
 * copies a locked phone's files, or even runs code on it, gets neither.
 */
internal object VaultKey {
    private const val ALIAS = "pyrsos-vault"

    /** Makes a new hardware key and secret; returns the secret and its wrapped form to store. */
    fun create(): Pair<ByteArray, ByteArray> {
        destroy()
        val key = generate()
        val secret = Crypto.random(32)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return secret to (c.iv + c.doFinal(secret))
    }

    /** The secret back from its wrapped form. Throws while the phone is locked. */
    fun unwrap(wrapped: ByteArray): ByteArray {
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as SecretKey?
            ?: throw IllegalStateException("the vault's hardware key is gone")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12))
        return c.doFinal(wrapped, 12, wrapped.size - 12)
    }

    fun destroy() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
    }

    private fun generate(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            gen.init(spec(strongBox = true))
            gen.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            gen.init(spec(strongBox = false))
            gen.generateKey()
        }
    }
}
