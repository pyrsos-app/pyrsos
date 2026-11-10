// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

class BytesTest {
    @Test
    fun bytes32RoundTrips() {
        for (size in listOf(0, 1, 0xffff, 0x10000, 200_000)) {
            val b = Crypto.random(size)
            val r = ByteReader(ByteWriter().bytes32(b).u8(7).bytes())
            assertArrayEquals(b, r.bytes32())
            r.u8()
            r.end()
        }
    }

    @Test
    fun bytes32LongerThanTheInputIsMalformed() {
        val whole = ByteWriter().bytes32(Crypto.random(100)).bytes()
        for (cut in listOf(0, 2, 4, 50, whole.size - 1)) {
            try {
                ByteReader(whole.copyOf(cut)).bytes32()
                fail("read $cut bytes")
            } catch (_: MalformedException) {
            }
        }
        // A length near 4 GB fails before anything is allocated.
        try {
            ByteReader(ByteWriter().u32(0xffffffffL).bytes()).bytes32()
            fail("read")
        } catch (_: MalformedException) {
        }
    }
}
