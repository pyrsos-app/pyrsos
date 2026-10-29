// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionTest {
    private val day = 20_000

    /** One phone's view of one contact, the way the app keeps it. */
    private class Peer(var session: Session) {
        fun send(text: String, today: Int): Envelope {
            val sealed = session.seal(Payload.Text(Crypto.random(16), 1L, text), today)
            session = sealed.next
            return sealed.envelope
        }

        /** Looks the tag up among expected slots, as the app's index does. Null: not for us. */
        fun receive(e: Envelope, today: Int): Payload? {
            val slot = session.expected(today).firstOrNull { it.tag.contentEquals(e.tag) } ?: return null
            val opened = session.open(e, slot, today)
            session = opened.next
            return opened.payload
        }
    }

    private fun pair(today: Int = day): Pair<Peer, Peer> {
        val a = Crypto.ed25519KeyPair()
        val b = Crypto.ed25519KeyPair()
        val pending = Handshake.invite(a, "Alice", today)
        val (reply, bSide) = Handshake.reply(b, "Bob", pending.invite.bytes, today)
        val aSide = Handshake.complete(Handshake.PendingInvite.decode(pending.encode()), reply)
        assertArrayEquals(b.public, aSide.peerIdentity)
        assertArrayEquals(a.public, bSide.peerIdentity)
        assertEquals("Bob", aSide.peerName)
        assertEquals("Alice", bSide.peerName)
        return Peer(aSide.session) to Peer(bSide.session)
    }

    private fun text(p: Payload?) = (p as Payload.Text).text

    @Test
    fun eitherSideMayWriteFirst() {
        val (a, b) = pair()
        assertEquals("from a", text(b.receive(a.send("from a", day), day)))
        val (a2, b2) = pair()
        assertEquals("from b", text(a2.receive(b2.send("from b", day), day)))
    }

    @Test
    fun conversationBothWaysWithCrossingMessages() {
        val (a, b) = pair()
        val a1 = a.send("a1", day)
        val b1 = b.send("b1", day)
        assertEquals("b1", text(a.receive(b1, day)))
        assertEquals("a1", text(b.receive(a1, day)))
        repeat(5) { i ->
            assertEquals("a$i", text(b.receive(a.send("a$i", day), day)))
            assertEquals("b$i", text(a.receive(b.send("b$i", day), day)))
        }
    }

    @Test
    fun outOfOrderAndLateArrivalsOpen() {
        val (a, b) = pair()
        val es = (0 until 10).map { a.send("m$it", day) }
        for (i in listOf(9, 3, 0, 7, 1, 2, 8, 4, 6, 5)) assertEquals("m$i", text(b.receive(es[i], day)))
    }

    @Test
    fun lateArrivalAcrossARatchetStep() {
        val (a, b) = pair()
        val early = a.send("early", day)
        assertEquals("x", text(b.receive(a.send("x", day), day)))
        assertEquals("reply", text(a.receive(b.send("reply", day), day)))
        assertEquals("after step", text(b.receive(a.send("after step", day), day)))
        assertEquals("early", text(b.receive(early, day)))
    }

    @Test
    fun lateMessageFromAnOlderSendingChain() {
        val (a, b) = pair()
        assertEquals("m1", text(b.receive(a.send("m1", day), day)))
        val late = a.send("m2, delayed", day)
        assertEquals("reply", text(a.receive(b.send("reply", day), day)))
        // a moved to a new sending chain; b hears from it before the message from the old one.
        assertEquals("m3", text(b.receive(a.send("m3", day), day)))
        assertEquals("m2, delayed", text(b.receive(late, day)))
    }

    @Test
    fun anEnvelopeOpensOnce() {
        val (a, b) = pair()
        val e = a.send("once", day)
        assertNotNull(b.receive(e, day))
        assertNull("a used slot is no longer expected", b.receive(e, day))
    }

    @Test
    fun anyChangedByteIsRefusedAndChangesNothing() {
        val (a, b) = pair()
        val e = a.send("intact", day)
        val before = b.session.encode()
        for (i in listOf(0, 1, 2, 3, 18, 19, 30, 31, 100, Envelope.SIZE - 1)) {
            val bad = e.bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val parsed = try {
                Envelope.parse(bad)
            } catch (_: MalformedException) {
                continue
            }
            try {
                b.receive(parsed, day)?.let { fail("byte $i: changed envelope opened") }
            } catch (_: CryptoException) {
            }
            assertArrayEquals("byte $i changed the session", before, b.session.encode())
        }
        assertEquals("intact", text(b.receive(e, day)))
    }

    @Test
    fun anotherConversationCannotOpenIt() {
        val (a, _) = pair()
        val (_, c) = pair()
        assertNull(c.receive(a.send("not yours", day), day))
    }

    @Test
    fun envelopesLookAlikeFromOutside() {
        val (a, b) = pair()
        val es = (0 until 20).map { a.send(if (it % 2 == 0) "x" else "y".repeat(400), day) } + b.send("z", day)
        assertTrue(es.all { it.bytes.size == Envelope.SIZE })
        assertEquals("tags never repeat", es.size, es.map { it.tag.toHex() }.toSet().size)
        assertEquals(es.size, es.map { it.id.toHex() }.toSet().size)
    }

    @Test
    fun sessionSurvivesStorageMidConversation() {
        val (a, b) = pair()
        a.send("lost on the way", day)
        val e = a.send("kept", day)
        b.session = Session.decode(b.session.encode())
        assertEquals("kept", text(b.receive(e, day)))
        a.session = Session.decode(a.session.encode())
        assertEquals("back", text(a.receive(b.send("back", day), day)))
    }

    @Test
    fun aClockGoingBackwardsNeverReusesASlot() {
        val (a, b) = pair()
        val e1 = a.send("today", day + 1)
        val e2 = a.send("clock went back", day)
        assertEquals(e1.expiryDay, e2.expiryDay)
        assertEquals("today", text(b.receive(e1, day + 1)))
        assertEquals("clock went back", text(b.receive(e2, day + 1)))
    }

    @Test
    fun aLossWithinOneDayRecoversTheNextDay() {
        val (a, b) = pair()
        repeat((TagReceiver.LOOKAHEAD + 5).toInt()) { a.send("lost", day) }
        assertNull("too many lost in a row today", b.receive(a.send("unseen", day), day))
        assertEquals("tomorrow", text(b.receive(a.send("tomorrow", day + 1), day + 1)))
    }

    @Test
    fun aSenderClockAheadIsStillHeard() {
        val (a, b) = pair()
        assertEquals("ahead", text(b.receive(a.send("ahead", day + TagReceiver.FUTURE_DAYS), day)))
    }

    @Test
    fun oldDaysAreForgotten() {
        val (a, b) = pair()
        val old = a.send("old", day)
        assertNull(b.receive(old, day + TagReceiver.KEEP_DAYS + 1))
    }

    @Test
    fun tagsMoveOnWithTheRatchet() {
        val (a, b) = pair()
        val seen = HashSet<String>()
        repeat(4) { i ->
            val ea = a.send("a$i", day)
            assertEquals("a$i", text(b.receive(ea, day)))
            val eb = b.send("b$i", day)
            assertEquals("b$i", text(a.receive(eb, day)))
            assertTrue(seen.add(ea.tag.toHex()) && seen.add(eb.tag.toHex()))
        }
        // Both stay on few epochs however long the conversation.
        assertTrue(a.session.receivers.size <= Session.MAX_EPOCHS && b.session.receivers.size <= Session.MAX_EPOCHS)
    }

    @Test
    fun aCopiedSessionLosesTheConversationAfterARoundTrip() {
        val (a, b) = pair()
        assertEquals("hi", text(b.receive(a.send("hi", day), day)))
        // Someone copies b's phone now.
        val copy = Peer(Session.decode(b.session.encode()))
        assertEquals("seen by the copy too", text(copy.receive(a.send("seen by the copy too", day), day)))
        b.receive(a.send("seen by the copy too", day), day)
        // b and a each write once more: the ratchet turns on fresh keys the copy never had.
        assertEquals("b", text(a.receive(b.send("b", day), day)))
        assertEquals("a", text(b.receive(a.send("a", day), day)))
        assertEquals("b2", text(a.receive(b.send("b2", day), day)))
        val later = a.send("after", day)
        assertNull("the copy no longer even recognises the envelope", copy.receive(later, day))
        assertEquals("after", text(b.receive(later, day)))
    }

    @Test
    fun aLateEnvelopeFromTheEpochBeforeStillOpens() {
        val (a, b) = pair()
        assertEquals("1", text(b.receive(a.send("1", day), day)))
        assertEquals("2", text(a.receive(b.send("2", day), day)))
        val late = a.send("late", day)
        assertEquals("3", text(b.receive(a.send("3", day), day)))
        assertEquals("4", text(a.receive(b.send("4", day), day)))
        assertEquals("5", text(b.receive(a.send("5", day), day)))
        assertEquals("late", text(b.receive(late, day)))
    }

    @Test
    fun epochsWorkAcrossDaysAndClocksApart() {
        val (a, b) = pair()
        // a's clock runs two days ahead of b's; they write over three weeks.
        for (d in 0 until 21 step 3) {
            assertEquals("a$d", text(b.receive(a.send("a$d", day + d + TagReceiver.FUTURE_DAYS), day + d)))
            assertEquals("b$d", text(a.receive(b.send("b$d", day + d), day + d + TagReceiver.FUTURE_DAYS)))
        }
    }

    @Test
    fun sessionsFromBeforeEpochsCarryOn() {
        val (a, b) = pair()
        // Talk the way the first version did: tags never moved.
        fun oldReceive(p: Peer, e: Envelope): String {
            val s = p.session
            val slot = s.receivers[0].receiver.expected(day).first { it.tag.contentEquals(e.tag) }
            val inner = Crypto.open(slot.key, e.nonce, e.outerCiphertext, e.outerAad)
            val header = Ratchet.Header.decode(inner.copyOfRange(0, Ratchet.Header.SIZE))
            val dec = s.ratchet.decrypt(header, inner.copyOfRange(Ratchet.Header.SIZE, inner.size), s.ad)
            p.session = Session(dec.next, s.sender, listOf(Session.Epoch(0, s.receivers[0].receiver.consume(slot, day))), s.ad)
            return text(Payload.decodePadded(dec.plaintext))
        }
        fun asVersionOne(s: Session) = Session.decode(
            ByteWriter().u8(1).bytes16(s.ratchet.encode()).bytes16(s.sender.encode())
                .bytes16(s.receivers[0].receiver.encode()).bytes16(s.ad).bytes(),
        )
        a.session = Session(a.session.ratchet, a.session.sender, a.session.receivers.take(1), a.session.ad)
        assertEquals("1", oldReceive(b, a.send("1", day)))
        assertEquals("2", oldReceive(a, b.send("2", day)))
        assertEquals("3", oldReceive(b, a.send("3", day)))
        val inFlight = a.send("in flight during the update", day)
        // Both phones update.
        a.session = asVersionOne(a.session)
        b.session = asVersionOne(b.session)
        assertEquals("in flight during the update", text(b.receive(inFlight, day)))
        repeat(3) { i ->
            assertEquals("b$i", text(a.receive(b.send("b$i", day), day)))
            assertEquals("a$i", text(b.receive(a.send("a$i", day), day)))
        }
    }

    @Test
    fun aClockFarOffIsACryptoError() {
        val (a, b) = pair()
        val e = a.send("x", day)
        try {
            b.receive(e, day + TagChain.MAX_ADVANCE + 100)
            fail("opened")
        } catch (_: CryptoException) {
        }
        assertEquals("x", text(b.receive(e, day)))
    }

    @Test
    fun aHugeCounterIsRefused() {
        val r = TagReceiver.start(Crypto.random(32), day)
        val slot = TagChain.slot(Crypto.random(32), day, TagReceiver.MAX_USED_PER_DAY + 1L)
        try {
            r.consume(slot, day)
            fail("accepted")
        } catch (_: CryptoException) {
        }
        assertTrue(r.expected(day).size < 100)
    }
}
