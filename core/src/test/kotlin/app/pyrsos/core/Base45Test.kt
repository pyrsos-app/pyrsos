// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class Base45Test {
    @Test
    fun rfc9285Examples() {
        assertEquals("BB8", Base45.encode("AB".toByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".toByteArray()))
        assertEquals("UJCLQE7W581", Base45.encode("base-45".toByteArray()))
        assertEquals("ietf!", Base45.decode("QED8WEX0").toString(Charsets.US_ASCII))
    }

    @Test
    fun randomRoundTrips() {
        repeat(50) { n ->
            val b = Crypto.random(n * 7)
            assertArrayEquals(b, Base45.decode(Base45.encode(b)))
        }
    }

    @Test
    fun invalidInputIsRefused() {
        for (bad in listOf("GGW", "ZZZ", "a", "AB!", "1")) {
            try {
                Base45.decode(bad)
                fail("accepted $bad")
            } catch (_: MalformedException) {
            }
        }
    }
}
