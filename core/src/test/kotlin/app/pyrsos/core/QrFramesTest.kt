// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrFramesTest {
    private val day = 20_000

    private fun inviteText(): String =
        Base45.encode(Handshake.invite(Crypto.ed25519KeyPair(), "x".repeat(Handshake.MAX_NAME_BYTES), day).invite.bytes)

    @Test
    fun aHandshakeCodeSplitsIntoFewSmallFrames() {
        val frames = QrFrames.split(inviteText())
        assertTrue("${frames.size} frames", frames.size in 2..5)
        // QR alphanumeric mode: digits, capital letters and " $%*+-./:".
        val alphanumeric = Regex("[0-9A-Z $%*+\\-./:]+")
        frames.forEach { assertTrue(it.length <= 560 + 12); assertTrue(alphanumeric.matches(it)) }
    }

    @Test
    fun framesJoinInAnyOrderAndWithRepeats() {
        val text = inviteText()
        val frames = QrFrames.split(text)
        val c = QrFrames.Collector()
        val order = frames.indices.reversed().flatMap { listOf(it, it) }.dropLast(1)
        var result: String? = null
        for (i in order) result = c.add(frames[i]) ?: result
        assertEquals(text, result)
    }

    @Test
    fun framesOfAnotherCodeStartOver() {
        val first = QrFrames.split(inviteText())
        val second = QrFrames.split(inviteText())
        val c = QrFrames.Collector()
        assertNull(c.add(first[0]))
        assertNull(c.add(second[1]))
        assertEquals(1, c.have)
        var result: String? = null
        second.forEach { result = c.add(it) ?: result }
        assertEquals(second.joinToString("") { it.substring(12) }, result)
    }

    @Test
    fun aFrameChangedInTransitIsDropped() {
        val frames = QrFrames.split(inviteText()).toMutableList()
        frames[1] = frames[1].substring(0, 20) + (if (frames[1][20] == 'A') 'B' else 'A') + frames[1].substring(21)
        val c = QrFrames.Collector()
        var result: String? = "unset"
        frames.forEach { result = c.add(it) }
        assertNull(result)
    }

    @Test
    fun plainTextPassesThrough() {
        assertEquals("https://example.org", QrFrames.Collector().add("https://example.org"))
    }

    @Test
    fun nonAsciiDigitsAreNotAFrameHeader() {
        val text = inviteText()
        val frame = QrFrames.split(text)[0]
        val arabic = "PF\u0660" + frame.substring(3)
        val c = QrFrames.Collector()
        // Not a frame: handed back as it is, and nothing is collected.
        assertEquals(arabic, c.add(arabic))
        assertEquals(0, c.of)
    }
}
