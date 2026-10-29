// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

/**
 * The 16-byte tag on the outside of an envelope, and the key that opens its outer layer.
 *
 * A phone carrying envelopes for others sees only tags, and every tag is new: two envelopes for
 * the same person look unrelated. The recipient knows which tags to expect, because both sides
 * derive them from a secret agreed in person, per day and per counter within the day.
 *
 * The day comes from the sender's clock and resets the counter, so envelopes lost on the way can
 * never leave the recipient unable to recognise later ones for more than that day. Day keys are
 * a one-way chain: once a day leaves the window its key is gone, and envelopes from it can no
 * longer be linked to the conversation even by someone holding the phone.
 */
class TagSlot(val day: Int, val counter: Long, val tag: ByteArray, val key: ByteArray) {
    /** Which of a session's receiving epochs this slot belongs to; set by [Session.expected]. */
    var epoch: Int = 0
        internal set
}

internal object TagChain {
    const val TAG_SIZE = 16

    fun nextDay(dayKey: ByteArray): ByteArray = Crypto.hmac(dayKey, "day".toByteArray())

    fun advance(dayKey: ByteArray, days: Int): ByteArray {
        // A clock far off is refused as a crypto error the caller handles, never a crash.
        if (days !in 0..MAX_ADVANCE) throw CryptoException("cannot advance $days days")
        var k = dayKey
        repeat(days) { k = nextDay(k) }
        return k
    }

    fun slot(dayKey: ByteArray, day: Int, counter: Long): TagSlot {
        val c = ByteWriter().u32(counter).bytes()
        val tag = Crypto.hmac(dayKey, "tag".toByteArray(), c).copyOf(TAG_SIZE)
        val key = Crypto.hmac(dayKey, "key".toByteArray(), c)
        return TagSlot(day, counter, tag, key)
    }

    /** A clock wildly wrong is refused rather than spun through. Reaches past 2070 from [ANCHOR_DAY]. */
    const val MAX_ADVANCE = 20_000

    /**
     * Tag chains made at a ratchet step start their day count here (14 April 2019), so both sides
     * reach the same key for a day without having to agree on when the chain began.
     */
    const val ANCHOR_DAY = 18_000

    /** The key of [day] in a chain that starts at [ANCHOR_DAY] with [seed]. */
    fun anchored(seed: ByteArray, day: Int): ByteArray = advance(seed, day - ANCHOR_DAY)
}

/** The sending side of one direction. */
class TagSender(private val dayKey: ByteArray, private val day: Int, private val counter: Long) {
    class Next(val next: TagSender, val slot: TagSlot)

    /** A clock that went backwards keeps the last day used, so no slot is ever used twice. */
    fun next(today: Int): Next {
        val d = maxOf(today, day)
        val k = TagChain.advance(dayKey, d - day)
        val c = if (d > day) 0L else counter
        return Next(TagSender(k, d, c + 1), TagChain.slot(k, d, c))
    }

    fun encode(): ByteArray = ByteWriter().raw(dayKey).u16(day).u32(counter).bytes()

    companion object {
        fun start(seed: ByteArray, today: Int) = TagSender(seed, today, 0)

        /** A sender for a new epoch, from a seed the other side derived at the same ratchet step. */
        fun anchored(seed: ByteArray, today: Int) = TagSender(TagChain.anchored(seed, today), today, 0)

        fun decode(b: ByteArray): TagSender {
            val r = ByteReader(b)
            return TagSender(r.raw(32), r.u16(), r.u32()).also { r.end() }
        }
    }
}

/** The receiving side of one direction. */
class TagReceiver private constructor(
    /** The key of [baseDay], the oldest day still in the window. */
    private val baseKey: ByteArray,
    internal val baseDay: Int,
    /** Per day: the highest counter seen and the counters already used. */
    private val days: Map<Int, DayState>,
) {
    class DayState(val maxSeen: Long, val used: Set<Long>)

    /**
     * Every slot this direction may arrive on today: the last [KEEP_DAYS] days, the next
     * [FUTURE_DAYS] days for a sender whose clock runs ahead, and per day [LOOKAHEAD] counters
     * past the highest seen.
     */
    fun expected(today: Int): List<TagSlot> {
        val from = maxOf(baseDay, today - KEEP_DAYS)
        val to = today + FUTURE_DAYS
        if (to < from) return emptyList()
        var k = TagChain.advance(baseKey, from - baseDay)
        val out = ArrayList<TagSlot>()
        for (d in from..to) {
            val st = days[d]
            // Capped: a contact must not be able to make this list grow without end.
            val upTo = minOf((st?.maxSeen ?: -1L) + LOOKAHEAD, MAX_USED_PER_DAY + LOOKAHEAD)
            for (c in 0..upTo) {
                if (st != null && c in st.used) continue
                out += TagChain.slot(k, d, c)
            }
            k = TagChain.nextDay(k)
        }
        return out
    }

    /** Records that [slot] was used, and forgets every day that left the window. */
    fun consume(slot: TagSlot, today: Int): TagReceiver {
        val st = days[slot.day]
        if (st != null && slot.counter in st.used) throw CryptoException("tag slot already used")
        if (st != null && st.used.size >= MAX_USED_PER_DAY) throw CryptoException("too many envelopes in one day")
        if (slot.counter > MAX_USED_PER_DAY) throw CryptoException("counter out of range")
        val nextDays = HashMap(days)
        nextDays[slot.day] = DayState(maxOf(st?.maxSeen ?: -1L, slot.counter), (st?.used ?: emptySet()) + slot.counter)
        return TagReceiver(baseKey, baseDay, nextDays).forgetBefore(today - KEEP_DAYS)
    }

    /** Moves the window to [today] even when nothing arrived: old day keys are then gone. */
    fun aged(today: Int): TagReceiver = forgetBefore(today - KEEP_DAYS)

    private fun forgetBefore(day: Int): TagReceiver {
        if (day <= baseDay) return this
        return TagReceiver(TagChain.advance(baseKey, day - baseDay), day, days.filterKeys { it >= day })
    }

    fun encode(): ByteArray {
        val w = ByteWriter().raw(baseKey).u16(baseDay).u16(days.size)
        for ((d, st) in days.toSortedMap()) {
            w.u16(d).u32(st.maxSeen + 1).u16(st.used.size)
            for (c in st.used.sorted()) w.u32(c)
        }
        return w.bytes()
    }

    companion object {
        const val KEEP_DAYS = 15
        const val FUTURE_DAYS = 2
        const val LOOKAHEAD = 16L
        const val MAX_USED_PER_DAY = 4096

        fun start(seed: ByteArray, today: Int) = TagReceiver(seed, today, emptyMap())

        /**
         * A receiver for a new epoch, starting at [from]: [KEEP_DAYS] before today covers a sender
         * whose clock lags.
         */
        fun anchored(seed: ByteArray, from: Int) = TagReceiver(TagChain.anchored(seed, from), from, emptyMap())

        fun decode(b: ByteArray): TagReceiver {
            val r = ByteReader(b)
            val key = r.raw(32)
            val base = r.u16()
            val days = HashMap<Int, DayState>()
            repeat(r.u16()) {
                val d = r.u16()
                val maxSeen = r.u32() - 1
                val used = HashSet<Long>()
                repeat(r.u16()) { used += r.u32() }
                days[d] = DayState(maxSeen, used)
            }
            r.end()
            return TagReceiver(key, base, days)
        }
    }
}
