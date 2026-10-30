// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.pyrsos.core.Crypto
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database key: 32 random bytes, stored on disk only encrypted by a key that lives in the
 * phone's secure hardware and never leaves it. Copying the app's files to another device gives
 * nothing that opens.
 */
internal object DbKey {
    private const val ALIAS = "pyrsos-db"
    private const val FILE = "db.key"

    /** The key that opens the database is gone for good. */
    class LostException(message: String) : Exception(message)

    /**
     * [fresh]: no database file exists yet. Then a key without its file (or the file without its
     * key) is what a crash during [create] leaves, and nothing is lost by starting over.
     */
    fun load(context: Context, fresh: Boolean): ByteArray {
        val file = File(context.noBackupFilesDir, FILE)
        // Only errors that last are a loss. Any other may pass (the keystore not ready yet): it
        // fails this start and is tried again next time, never offered as a reason to wipe.
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val wrapping = ks.getKey(ALIAS, null) as SecretKey?
            return when {
                wrapping != null && file.exists() -> unwrap(wrapping, file.readBytes())
                wrapping == null && !file.exists() -> create(file)
                fresh -> {
                    file.delete()
                    create(file)
                }
                // One half without the other means the data can no longer be opened. Say so; never
                // quietly start an empty database over it.
                wrapping == null -> throw LostException("the hardware key is gone but the wrapped database key exists")
                else -> throw LostException("the wrapped database key file is missing")
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw LostException("the hardware key was invalidated")
        } catch (e: UnrecoverableKeyException) {
            throw LostException("the hardware key cannot be read")
        } catch (e: AEADBadTagException) {
            throw LostException("the hardware key does not match the database key")
        }

    }

    /** Deletes the hardware key and the wrapped database key: the database can never open again. */
    fun destroy(context: Context) {
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        } finally {
            File(context.noBackupFilesDir, FILE).delete()
            File(context.noBackupFilesDir, "$FILE.tmp").delete()
        }
    }

    private fun create(file: File): ByteArray {
        val wrapping = generate()
        val raw = Crypto.random(32)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapping)
        val out = c.iv + c.doFinal(raw)
        // On disk for good before the database is made with the key: a crash after that would
        // leave a database nothing can open.
        val tmp = File(file.parentFile, "$FILE.tmp")
        FileOutputStream(tmp).use {
            it.write(out)
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IllegalStateException("could not store the database key")
        return raw
    }

    private fun unwrap(wrapping: SecretKey, stored: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, stored, 0, 12))
        return c.doFinal(stored, 12, stored.size - 12)
    }

    private fun generate(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(strongBox) }
            .build()

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            gen.init(spec(strongBox = true))
            gen.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            // A phone without a separate security chip keeps the key in its trusted environment.
            gen.init(spec(strongBox = false))
            gen.generateKey()
        }
    }
}
