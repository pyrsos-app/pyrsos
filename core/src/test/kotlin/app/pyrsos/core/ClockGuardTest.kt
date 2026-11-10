// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockGuardTest {
    private val day = 20_000
    private val hour = 60 * 60 * 1000L
    private val minute = 60 * 1000L

    /** The app's loop: check, store what is new. */
    private class App(var guard: ClockGuard) {
        var writes = 0

        fun check(today: Int, boot: Long, now: Long): Boolean {
            val v = guard.check(today, boot, now)
            if (v.next !== guard) writes++
            guard = v.next
            return v.believed
        }
    }

    @Test
    fun aNormalChangeOfDayIsBelievedAndStoredOnce() {
        val app = App(ClockGuard.start(day, 1, 0))
        var now = 0L
        // Every minute for two days.
        repeat(2 * 24 * 60) {
            now += minute
            assertTrue(app.check(day + (now / (24 * hour)).toInt(), 1, now))
        }
        assertEquals(2, app.writes)
        assertEquals(day + 2, app.guard.day)
        assertEquals(0L, app.guard.waited)
    }

    @Test
    fun aJumpIsBelievedOnlyAfterADayOfChecks() {
        val app = App(ClockGuard.start(day, 1, 0))
        var now = 0L
        repeat(23 * 60) {
            now += minute
            assertFalse(app.check(day + 30, 1, now))
        }
        assertEquals(day, app.guard.day)
        repeat(61) { now += minute; app.check(day + 30, 1, now) }
        assertTrue(app.check(day + 30, 1, now + minute))
        assertEquals(day + 30, app.guard.day)
        assertEquals(0L, app.guard.waited)
    }

    @Test
    fun theFirstCheckAfterAJumpDoesNotCountTheTimeBefore() {
        val g = ClockGuard.start(day, 1, 0)
        // Believed and not stored for twenty hours, then the clock jumps.
        val v = g.check(day + 30, 1, 20 * hour)
        assertFalse(v.believed)
        assertEquals(1L, v.next.waited)
        assertFalse(v.next.check(day + 30, 1, 22 * hour).believed)
        assertTrue(v.next.check(day + 30, 1, 20 * hour + ClockGuard.SETTLE_MS).believed)
    }

    @Test
    fun timeWaitedAddsUpAcrossReboots() {
        val app = App(ClockGuard.start(day, 1, 0))
        var boot = 1L
        var waited = 0L
        // Twelve hours a night on, the phone switched off between: the jump is believed on the third boot.
        var believed = false
        while (!believed) {
            boot++
            var now = 30_000L
            assertTrue(boot <= 4)
            repeat(12 * 60) {
                now += minute
                if (!believed) believed = app.check(day + 30, boot, now)
            }
            waited = app.guard.waited
        }
        assertEquals(4L, boot)
        assertEquals(0L, waited)
        assertEquals(day + 30, app.guard.day)
    }

    @Test
    fun aClockThatReturnsIsBelievedAgain() {
        val g = ClockGuard.start(day, 1, 0)
        val ahead = g.check(day + 30, 1, hour).next
        assertTrue(ahead.waited > 0)
        val back = ahead.check(day, 1, 2 * hour)
        assertTrue(back.believed)
        assertEquals(0L, back.next.waited)
        assertEquals(day, back.next.day)
        // Then a new jump starts waiting afresh.
        assertEquals(1L, back.next.check(day + 30, 1, 3 * hour).next.waited)
    }

    @Test
    fun aRebootWithoutAJumpIsBelieved() {
        val g = ClockGuard.start(day, 1, 5 * hour)
        val v = g.check(day + 1, 2, minute)
        assertTrue(v.believed)
        assertEquals(2L, v.next.boot)
        assertEquals(minute, v.next.at)
        // Stored once per boot, not at every check.
        assertSame(v.next, v.next.check(day + 1, 2, 2 * minute).next)
    }

    @Test
    fun aRebootAfterAMonthOffIsNotBelievedAtOnce() {
        val g = ClockGuard.start(day, 1, 5 * hour)
        val v = g.check(day + 30, 2, minute)
        assertFalse(v.believed)
        assertEquals(day, v.next.day)
    }

    @Test
    fun aLongTimeOnTheSameBootExplainsTheDate() {
        val g = ClockGuard.start(day, 1, 0)
        assertTrue(g.check(day + 30, 1, 30 * 24 * hour).believed)
    }

    @Test
    fun aClockGoingBackIsBelieved() {
        val g = ClockGuard.start(day, 1, 0)
        val v = g.check(day - 400, 1, minute)
        assertTrue(v.believed)
        assertSame(g, v.next)
        assertTrue(g.check(day - 400, 2, minute).believed)
    }

    @Test
    fun encodeAndDecode() {
        val g = ClockGuard(day, 7, 123_456_789_012L, 42)
        assertEquals("$day:7:123456789012:42", g.encode())
        val back = ClockGuard.decode(g.encode())!!
        assertEquals(g.day, back.day)
        assertEquals(g.boot, back.boot)
        assertEquals(g.at, back.at)
        assertEquals(g.waited, back.waited)
        for (bad in listOf(null, "", "garbage", "1:2:3", "1:2:3:4:5", "1:2:x:4", "-1:2:3:4", "1:2:3:-4", "1:-2:3:4", "99999999999:1:2:3", "1::3:4")) {
            assertNull(bad, ClockGuard.decode(bad))
        }
    }

    @Test
    fun nothingOverflows() {
        val g = ClockGuard(Int.MAX_VALUE, Long.MAX_VALUE, 0, Long.MAX_VALUE - 1)
        assertTrue(g.check(Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE).believed)
        val h = ClockGuard(0, 1, 0, Long.MAX_VALUE - 1)
        assertTrue(h.check(1000, 1, 2).believed)
    }
}
