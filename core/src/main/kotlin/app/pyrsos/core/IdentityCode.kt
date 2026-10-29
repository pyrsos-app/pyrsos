// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

/**
 * A short code people can read and compare, made from an identity key: `7F3K-92QA-M4TX`.
 *
 * Names are free and anyone can pick the same one; the code is what tells two people apart. It
 * comes from the public half of the identity key, so it never changes while the app keeps its key,
 * and a new install gets a new one. The handshake proves each side holds its key, so a code cannot
 * be borrowed; finding another key with the same 60-bit code takes about 2^60 tries.
 *
 * The letters are Crockford's base 32: no I, L, O or U, so nothing reads as another character.
 */
object IdentityCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val CHARS = 12
    private val CONTEXT = "Pyrsos v1 identity code".toByteArray()

    fun of(identity: ByteArray): String {
        require(identity.size == 32) { "identity key size ${identity.size}" }
        val h = Crypto.sha256(CONTEXT, identity)
        var bits = 0L
        for (i in 0 until 8) bits = (bits shl 8) or (h[i].toLong() and 0xff)
        val out = StringBuilder()
        for (i in 0 until CHARS) {
            if (i > 0 && i % 4 == 0) out.append('-')
            out.append(ALPHABET[((bits ushr (59 - 5 * i)) and 31).toInt()])
        }
        return out.toString()
    }
}
