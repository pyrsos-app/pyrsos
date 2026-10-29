// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

/**
 * Base45 (RFC 9285). Its alphabet is exactly the QR code's alphanumeric set, so a QR code holds
 * Base45 text almost as densely as raw bytes, and no scanner can misread it as some other text
 * encoding, which raw bytes in a QR code invite.
 */
object Base45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    private val index = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(b: ByteArray): String {
        val sb = StringBuilder((b.size + 1) / 2 * 3)
        var i = 0
        while (i + 1 < b.size) {
            var x = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
            repeat(3) {
                sb.append(ALPHABET[x % 45])
                x /= 45
            }
            i += 2
        }
        if (i < b.size) {
            val x = b[i].toInt() and 0xff
            sb.append(ALPHABET[x % 45]).append(ALPHABET[x / 45])
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        if (s.length % 3 == 1) throw MalformedException("base45 length")
        val out = ByteWriter()
        var i = 0
        while (i < s.length) {
            val chunk = minOf(3, s.length - i)
            var x = 0
            var mul = 1
            for (j in 0 until chunk) {
                val c = s[i + j].code
                val v = if (c < 128) index[c] else -1
                if (v < 0) throw MalformedException("base45 character")
                x += v * mul
                mul *= 45
            }
            if (chunk == 3) {
                if (x > 0xffff) throw MalformedException("base45 value")
                out.u8(x ushr 8).u8(x and 0xff)
            } else {
                if (x > 0xff) throw MalformedException("base45 value")
                out.u8(x)
            }
            i += chunk
        }
        return out.bytes()
    }
}
