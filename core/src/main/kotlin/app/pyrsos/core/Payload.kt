// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

/**
 * What travels inside an envelope. Every payload is padded to [PADDED] bytes before it is
 * encrypted, so a text, an acknowledgement and a greeting all look the same from outside.
 */
sealed class Payload {
    /** A message the user wrote. [id] is random and identifies it on both phones. */
    class Text(val id: ByteArray, val sentAt: Long, val text: String) : Payload()

    /** The recipient read these message ids out of their envelopes. */
    class Ack(val ids: List<ByteArray>) : Payload()

    /** Sent once by the inviter after the handshake, so the replier learns it completed. */
    object Hello : Payload()

    fun encodePadded(): ByteArray {
        val body = when (this) {
            is Text -> {
                require(id.size == ID_SIZE)
                val t = text.toByteArray(Charsets.UTF_8)
                require(t.size <= MAX_TEXT_BYTES) { "text too long" }
                ByteWriter().u8(TYPE_TEXT).raw(id).u64(sentAt).bytes16(t).bytes()
            }
            is Ack -> {
                require(ids.size in 1..MAX_ACKS)
                val w = ByteWriter().u8(TYPE_ACK).u8(ids.size)
                ids.forEach { require(it.size == ID_SIZE); w.raw(it) }
                w.bytes()
            }
            Hello -> ByteWriter().u8(TYPE_HELLO).bytes()
        }
        val out = ByteArray(PADDED)
        ByteWriter().u16(body.size).raw(body).bytes().copyInto(out)
        return out
    }

    companion object {
        const val PADDED = 1280
        const val ID_SIZE = 16

        /** The limit on a message, in bytes of UTF-8. The app also limits characters. */
        const val MAX_TEXT_BYTES = 1200
        const val MAX_TEXT_CHARS = 500
        const val MAX_ACKS = 64

        private const val TYPE_TEXT = 1
        private const val TYPE_ACK = 2
        private const val TYPE_HELLO = 3

        fun decodePadded(padded: ByteArray): Payload {
            if (padded.size != PADDED) throw MalformedException("payload size ${padded.size}")
            val outer = ByteReader(padded)
            val len = outer.u16()
            if (len > PADDED - 2) throw MalformedException("payload length $len")
            val r = ByteReader(outer.raw(len))
            for (i in 2 + len until PADDED) if (padded[i] != 0.toByte()) throw MalformedException("padding not zero")
            val p = when (r.u8()) {
                TYPE_TEXT -> {
                    val id = r.raw(ID_SIZE)
                    val sentAt = r.u64()
                    val t = r.bytes16()
                    if (t.size > MAX_TEXT_BYTES) throw MalformedException("text too long")
                    Text(id, sentAt, t.toString(Charsets.UTF_8))
                }
                TYPE_ACK -> {
                    val n = r.u8()
                    if (n !in 1..MAX_ACKS) throw MalformedException("ack count $n")
                    Ack(List(n) { r.raw(ID_SIZE) })
                }
                TYPE_HELLO -> Hello
                else -> throw MalformedException("payload type")
            }
            r.end()
            return p
        }
    }
}
