// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

/**
 * One conversation with one contact: the ratchet and the tags of both directions.
 *
 * The tags move to new seeds with every turn of the ratchet (an *epoch*), so someone who copies a
 * phone's keys stops recognising the conversation's envelopes once both sides have written again.
 * A few receiving epochs are kept, for envelopes that arrive late or out of order.
 *
 * Like [Ratchet], a session is never changed in place. The caller stores the returned session
 * in the same transaction as the envelope it sealed or the message it opened.
 */
class Session internal constructor(
    internal val ratchet: Ratchet,
    internal val sender: TagSender,
    /** Receiving epochs, oldest first. */
    internal val receivers: List<Epoch>,
    /** Both identity keys, inviter first: every message is bound to this pair. */
    internal val ad: ByteArray,
) {
    class Epoch(val id: Int, val receiver: TagReceiver)

    class Sealed(val next: Session, val envelope: Envelope)

    class Opened(val next: Session, val payload: Payload)

    fun seal(payload: Payload, today: Int): Sealed {
        val enc = ratchet.encrypt(payload.encodePadded(), ad)
        val tag = sender.next(today)
        val envelope = Envelope.build(
            tag.slot.day + Envelope.LIFETIME_DAYS,
            tag.slot.tag,
            tag.slot.key,
            concat(enc.header.encode(), enc.ciphertext),
        )
        return Sealed(Session(enc.next, tag.next, receivers, ad), envelope)
    }

    /** Slots this session may receive on today. The caller indexes them by tag. */
    fun expected(today: Int): List<TagSlot> = receivers.flatMap { e ->
        e.receiver.expected(today).onEach { it.epoch = e.id }
    }

    /** Opens an envelope whose tag matched [slot]. Throws if it is not really for this session. */
    fun open(envelope: Envelope, slot: TagSlot, today: Int): Opened {
        if (!constantTimeEquals(envelope.tag, slot.tag)) throw CryptoException("tag does not match slot")
        if (envelope.expiryDay != slot.day + Envelope.LIFETIME_DAYS) throw CryptoException("expiry does not match slot")
        val epoch = receivers.firstOrNull { it.id == slot.epoch } ?: throw CryptoException("tag epoch is gone")
        val inner = Crypto.open(slot.key, envelope.nonce, envelope.outerCiphertext, envelope.outerAad)
        val header = Ratchet.Header.decode(inner.copyOfRange(0, Ratchet.Header.SIZE))
        val dec = ratchet.decrypt(header, inner.copyOfRange(Ratchet.Header.SIZE, inner.size), ad)
        val payload = Payload.decodePadded(dec.plaintext)
        var nextReceivers = receivers.map { if (it === epoch) Epoch(it.id, it.receiver.consume(slot, today)) else it }
        var nextSender = sender
        dec.step?.let { step ->
            nextSender = TagSender.anchored(step.send, today)
            nextReceivers = (nextReceivers + Epoch(receivers.last().id + 1, TagReceiver.anchored(step.receive, today - TagReceiver.KEEP_DAYS)))
                .takeLast(MAX_EPOCHS)
        }
        return Opened(Session(dec.next, nextSender, nextReceivers, ad), payload)
    }

    /** Moves every receiving window to [today], forgetting the day keys that left it. */
    fun aged(today: Int): Session = Session(ratchet, sender, receivers.map { Epoch(it.id, it.receiver.aged(today)) }, ad)

    fun encode(): ByteArray {
        val w = ByteWriter()
            .u8(VERSION)
            .bytes16(ratchet.encode())
            .bytes16(sender.encode())
            .u8(receivers.size)
        for (e in receivers) w.u32(e.id.toLong()).bytes16(e.receiver.encode())
        return w.bytes16(ad).bytes()
    }

    companion object {
        private const val VERSION = 2
        private const val VERSION_ONE_EPOCH = 1

        /**
         * Receiving epochs kept: the newest in use, the next, and several before for envelopes
         * still travelling while the two sides ping-pong. Older sessions hold fewer.
         */
        const val MAX_EPOCHS = 5

        /**
         * A session just out of the handshake, on the handshake's tags. The inviter's ratchet has
         * already turned once, so it also listens on the epoch the replier moves to on its first
         * reply.
         */
        internal fun handshake(ratchet: Ratchet, sender: TagSender, receiver: TagReceiver, ad: ByteArray, inviter: Boolean) =
            Session(ratchet, sender, firstEpochs(ratchet, receiver, inviter), ad)

        private fun firstEpochs(ratchet: Ratchet, receiver: TagReceiver, withPending: Boolean): List<Epoch> =
            if (withPending) {
                listOf(Epoch(0, receiver), Epoch(1, TagReceiver.anchored(ratchet.pendingTagSeed(), receiver.baseDay)))
            } else {
                listOf(Epoch(0, receiver))
            }

        fun decode(b: ByteArray): Session {
            val r = ByteReader(b)
            val s = when (r.u8()) {
                VERSION -> {
                    val ratchet = Ratchet.decode(r.bytes16())
                    val sender = TagSender.decode(r.bytes16())
                    val count = r.u8()
                    if (count !in 1..MAX_EPOCHS) throw MalformedException("session epochs")
                    val receivers = List(count) { Epoch(r.u32().toInt(), TagReceiver.decode(r.bytes16())) }
                    Session(ratchet, sender, receivers, r.bytes16())
                }
                // Sessions from before tag epochs: whichever side turned the ratchet last may hear
                // next on the epoch that turn made, so listen there too.
                VERSION_ONE_EPOCH -> {
                    val ratchet = Ratchet.decode(r.bytes16())
                    val sender = TagSender.decode(r.bytes16())
                    val receiver = TagReceiver.decode(r.bytes16())
                    Session(ratchet, sender, firstEpochs(ratchet, receiver, true), r.bytes16())
                }
                else -> throw MalformedException("session version")
            }
            r.end()
            return s
        }
    }
}
