// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

/**
 * The unit phones carry for each other. Every envelope has the same size, and from outside shows
 * only a format version, the day it stops being carried, a tag that looks random, and a nonce.
 *
 *   [0]      version
 *   [1..2]   expiry day (days since 1970-01-01, UTC)
 *   [3..18]  tag
 *   [19..30] nonce
 *   [31..]   ChaCha20-Poly1305 of (ratchet header || inner ciphertext), bound to bytes 0..30
 */
class Envelope private constructor(val bytes: ByteArray) {
    val version: Int get() = bytes[0].toInt() and 0xff
    val expiryDay: Int get() = ((bytes[1].toInt() and 0xff) shl 8) or (bytes[2].toInt() and 0xff)
    val tag: ByteArray get() = bytes.copyOfRange(3, 3 + TagChain.TAG_SIZE)

    /** The envelope's name in inventories: a hash of all of it, so a copy is recognised. */
    val id: ByteArray by lazy { Crypto.sha256(bytes).copyOf(ID_SIZE) }

    internal val outerAad: ByteArray get() = bytes.copyOfRange(0, HEAD)
    internal val nonce: ByteArray get() = bytes.copyOfRange(3 + TagChain.TAG_SIZE, HEAD)
    internal val outerCiphertext: ByteArray get() = bytes.copyOfRange(HEAD, SIZE)

    companion object {
        const val VERSION = 1
        const val ID_SIZE = 16

        /** How many days phones keep carrying an envelope after it was sealed. */
        const val LIFETIME_DAYS = 14

        private const val HEAD = 1 + 2 + TagChain.TAG_SIZE + Crypto.NONCE
        private const val INNER = Payload.PADDED + Crypto.TAG
        const val SIZE = HEAD + Ratchet.Header.SIZE + INNER + Crypto.TAG

        fun parse(bytes: ByteArray): Envelope {
            if (bytes.size != SIZE) throw MalformedException("envelope size ${bytes.size}")
            if ((bytes[0].toInt() and 0xff) != VERSION) throw MalformedException("envelope version")
            return Envelope(bytes.copyOf())
        }

        internal fun build(expiryDay: Int, tag: ByteArray, key: ByteArray, sealedInner: ByteArray): Envelope {
            val head = ByteWriter().u8(VERSION).u16(expiryDay).raw(tag).raw(Crypto.random(Crypto.NONCE)).bytes()
            val outer = Crypto.seal(key, head.copyOfRange(3 + TagChain.TAG_SIZE, HEAD), sealedInner, head)
            return Envelope(concat(head, outer)).also { check(it.bytes.size == SIZE) }
        }
    }
}
