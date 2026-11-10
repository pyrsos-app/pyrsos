// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RatchetTest {
    private val ad = Crypto.random(64)

    /** A replier that only sends and an inviter that only receives; each message with its header. */
    private fun sent(count: Int): Pair<Ratchet, List<Ratchet.Encrypted>> {
        val root = Crypto.random(32)
        val rKey = Crypto.x25519KeyPair()
        val iKey = Crypto.x25519KeyPair()
        var sender = Ratchet.forReplier(root, rKey, iKey.public)
        val receiver = Ratchet.forInviter(root, iKey, rKey.public)
        val out = ArrayList<Ratchet.Encrypted>()
        repeat(count) { i ->
            val e = sender.encrypt("m$i".toByteArray(), ad)
            sender = e.next
            out += e
        }
        return receiver to out
    }

    private fun Ratchet.open(e: Ratchet.Encrypted) = decrypt(e.header, e.ciphertext, ad)

    @Test
    fun aJumpOverMaxSkipIsRefused() {
        val (rx, msgs) = sent(Ratchet.MAX_SKIP.toInt() + 2)
        try {
            rx.open(msgs[Ratchet.MAX_SKIP.toInt() + 1])
            fail("skipped ${Ratchet.MAX_SKIP + 1}")
        } catch (_: CryptoException) {
        }
        assertEquals("m${Ratchet.MAX_SKIP}", String(rx.open(msgs[Ratchet.MAX_SKIP.toInt()]).plaintext))
    }

    @Test
    fun skippedKeysAreTrimmedOldestFirst() {
        val skip = 1000
        val (rx0, msgs) = sent(3 * skip + 3)
        // Three jumps of 1000 leave 3000 keys; only MAX_STORED_SKIPPED stay.
        var rx = rx0.open(msgs[skip]).next
        rx = rx.open(msgs[2 * skip + 1]).next
        rx = rx.open(msgs[3 * skip + 2]).next
        assertTrue(3 * skip > Ratchet.MAX_STORED_SKIPPED && 2 * skip <= Ratchet.MAX_STORED_SKIPPED)
        try {
            rx.open(msgs[0])
            fail("oldest key survived")
        } catch (_: CryptoException) {
        }
        val kept = rx.open(msgs[skip + 1])
        assertEquals("m${skip + 1}", String(kept.plaintext))
        // The state survives storage with the keys it kept.
        val again = Ratchet.decode(rx.encode()).open(msgs[2 * skip])
        assertEquals("m${2 * skip}", String(again.plaintext))
    }

    @Test
    fun aLongJumpOpensAndKeepsOnlyTheNewestSkippedKeys() {
        val jump = 5000
        val (rx0, msgs) = sent(jump + 1)
        val opened = rx0.open(msgs[jump])
        assertEquals("m$jump", String(opened.plaintext))
        var rx = Ratchet.decode(opened.next.encode())
        assertTrue(rx.skippedCount() <= Ratchet.MAX_STORED_SKIPPED)
        // The newest of the gap still open late, in any order; the oldest were never kept.
        for (i in listOf(jump - 1, jump - Ratchet.MAX_STORED_SKIPPED, jump - 2)) {
            val late = rx.open(msgs[i])
            assertEquals("m$i", String(late.plaintext))
            rx = late.next
        }
        for (i in listOf(0, jump - Ratchet.MAX_STORED_SKIPPED - 1)) {
            try {
                rx.open(msgs[i])
                fail("m$i opened")
            } catch (_: CryptoException) {
            }
        }
    }
}
