// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

/**
 * Whether the wall clock may be believed before deleting by it.
 *
 * The app deletes expired envelopes and old messages by the date, so one forward jump of the clock
 * (a wrong network time, say) would delete everything at once. A date further ahead than the time
 * passed explains is believed only once it has lasted [SETTLE_MS] of checks; a date that went
 * back is always believed, since deleting less does no harm.
 *
 * The time passed comes from a clock that counts from boot (Android's elapsedRealtime), so it can
 * only be measured within one boot. Time waited adds up across reboots: a phone switched off every
 * night still gets there.
 *
 * [day]: the last day (days since 1970, UTC) the clock was believed. [boot]: the boot count then.
 * [at]: milliseconds since boot at the last check that was stored. [waited]: 0 while the clock is
 * believed, otherwise how long it has stayed ahead (at least 1).
 */
class ClockGuard(val day: Int, val boot: Long, val at: Long, val waited: Long) {
    /** [next] is this same object when there is nothing new to store. */
    class Verdict(val believed: Boolean, val next: ClockGuard)

    fun check(today: Int, boot: Long, now: Long): Verdict {
        val sameBoot = boot == this.boot && now >= at
        val passed = if (sameBoot) now - at else 0L
        if (today.toLong() <= day.toLong() + passed / DAY_MS + TOLERANCE_DAYS) {
            // Stored about once a day and once per boot, not at every check.
            val next = if (today > day || !sameBoot || waited != 0L) ClockGuard(maxOf(today, day), boot, now, 0) else this
            return Verdict(true, next)
        }
        // Ahead of what the time passed explains. The first such check does not count the time
        // before it, when the clock was still fine.
        val total = when {
            waited == 0L -> 1L
            passed > Long.MAX_VALUE - waited -> Long.MAX_VALUE
            else -> waited + passed
        }
        if (total >= SETTLE_MS) return Verdict(true, ClockGuard(today, boot, now, 0))
        return Verdict(false, ClockGuard(day, boot, now, total))
    }

    fun encode(): String = "$day:$boot:$at:$waited"

    companion object {
        const val TOLERANCE_DAYS = 2
        const val SETTLE_MS = 24 * 60 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** Believed from the start. */
        fun start(today: Int, boot: Long, now: Long) = ClockGuard(today, boot, now, 0)

        /** Null when [s] is missing or unreadable. */
        fun decode(s: String?): ClockGuard? {
            val parts = s?.split(':') ?: return null
            if (parts.size != 4) return null
            val n = parts.map { it.toLongOrNull() ?: return null }
            if (n.any { it < 0 } || n[0] > Int.MAX_VALUE) return null
            return ClockGuard(n[0].toInt(), n[1], n[2], n[3])
        }
    }
}
