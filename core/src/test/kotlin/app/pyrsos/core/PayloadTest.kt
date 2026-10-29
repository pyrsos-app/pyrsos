// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PayloadTest {
    @Test
    fun textRoundTripsAtTheLimit() {
        val longest = "ω".repeat(Payload.MAX_TEXT_BYTES / 2)
        val p = Payload.decodePadded(Payload.Text(ByteArray(16), 42, longest).encodePadded()) as Payload.Text
        assertEquals(longest, p.text)
        assertEquals(42L, p.sentAt)
    }

    @Test
    fun everyPayloadHasTheSameSize() {
        val sizes = listOf(
            Payload.Text(ByteArray(16), 1, "a"),
            Payload.Ack(listOf(ByteArray(16))),
            Payload.Hello,
        ).map { it.encodePadded().size }.toSet()
        assertEquals(setOf(Payload.PADDED), sizes)
    }

    @Test
    fun nonZeroPaddingIsRefused() {
        val padded = Payload.Hello.encodePadded().also { it[Payload.PADDED - 1] = 1 }
        try {
            Payload.decodePadded(padded)
            fail("accepted")
        } catch (_: MalformedException) {
        }
    }
}
