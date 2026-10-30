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
        assertEquals("m1000", String(rx.open(msgs[Ratchet.MAX_SKIP.toInt()]).plaintext))
    }

    @Test
    fun skippedKeysAreTrimmedOldestFirst() {
        val skip = Ratchet.MAX_SKIP.toInt()
        val (rx0, msgs) = sent(3 * skip + 3)
        // Three jumps of MAX_SKIP leave 3 * MAX_SKIP keys; only MAX_STORED_SKIPPED stay.
        var rx = rx0.open(msgs[skip]).next
        rx = rx.open(msgs[2 * skip + 1]).next
        rx = rx.open(msgs[3 * skip + 2]).next
        assertTrue(3 * Ratchet.MAX_SKIP > Ratchet.MAX_STORED_SKIPPED)
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
}
