// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

/**
 * Splits a long QR text into a few smaller codes that the screen shows in turn, and joins them
 * back on the scanning side.
 *
 * A handshake code is about 2100 characters, mostly the post-quantum key. As one QR code that is
 * about 150 modules a side, more than many phone cameras can read off another screen. Four codes
 * of a quarter each are about 70 modules, which almost any camera reads.
 *
 * Each frame is QR alphanumeric text: `PF` | index (1 digit) | count (1 digit) | id (8 hex) | part.
 * The id is the start of the SHA-256 of the whole text, so frames of two different codes never mix
 * and a joined text that does not match its id is dropped.
 */
object QrFrames {
    private const val PREFIX = "PF"
    private const val ID_CHARS = 8
    private const val HEADER = 2 + 1 + 1 + ID_CHARS
    const val MAX_FRAMES = 9

    /** Splits [text] into at most [MAX_FRAMES] frames of at most [partChars] characters each. */
    fun split(text: String, partChars: Int = 560): List<String> {
        require(text.isNotEmpty())
        val count = (text.length + partChars - 1) / partChars
        require(count <= MAX_FRAMES) { "too long for $MAX_FRAMES frames" }
        val id = idOf(text)
        return (0 until count).map { i ->
            PREFIX + i + count + id + text.substring(i * partChars, minOf(text.length, (i + 1) * partChars))
        }
    }

    /** Collects scanned frames until one code is whole. Not thread-safe: feed it from one thread. */
    class Collector {
        private var id: String? = null
        private var parts = arrayOfNulls<String>(0)

        /** Frames seen so far and how many the code has; 0 of 0 before the first frame. */
        val have: Int get() = parts.count { it != null }
        val of: Int get() = parts.size

        /**
         * Adds one scanned text. Returns the whole code once every frame is in, else null.
         * A text that is not a frame is returned as it is, so a plain single code still works.
         */
        fun add(text: String): String? {
            val frame = parse(text) ?: return text
            if (frame.id != id || frame.count != parts.size) {
                id = frame.id
                parts = arrayOfNulls(frame.count)
            }
            parts[frame.index] = frame.part
            if (parts.any { it == null }) return null
            val whole = parts.joinToString("")
            val ok = idOf(whole) == id
            id = null
            parts = arrayOfNulls(0)
            return if (ok) whole else null
        }
    }

    private class Frame(val index: Int, val count: Int, val id: String, val part: String)

    private fun parse(text: String): Frame? {
        if (text.length <= HEADER || !text.startsWith(PREFIX)) return null
        // ASCII only: digitToIntOrNull also takes digits of other scripts.
        if (text[2] !in '0'..'9' || text[3] !in '0'..'9') return null
        val index = text[2] - '0'
        val count = text[3] - '0'
        if (count !in 1..MAX_FRAMES || index >= count) return null
        val id = text.substring(4, HEADER)
        if (!id.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return Frame(index, count, id, text.substring(HEADER))
    }

    private fun idOf(text: String): String =
        Crypto.sha256(text.toByteArray(Charsets.US_ASCII)).copyOf(ID_CHARS / 2).toHex().uppercase()
}
