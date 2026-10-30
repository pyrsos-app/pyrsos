// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HandshakeTest {
    private val day = 20_000
    private val a = Crypto.ed25519KeyPair()
    private val b = Crypto.ed25519KeyPair()

    private inline fun refuses(block: () -> Unit) {
        try {
            block()
            fail("accepted")
        } catch (_: CryptoException) {
        } catch (_: MalformedException) {
        }
    }

    @Test
    fun codesFitAQrCode() {
        val pending = Handshake.invite(a, "x".repeat(Handshake.MAX_NAME_BYTES), day)
        val (reply, _) = Handshake.reply(b, "y".repeat(Handshake.MAX_NAME_BYTES), pending.invite.bytes, day)
        // Base45 in alphanumeric mode: a version-40 QR code holds 4296 characters at the lowest
        // error correction, about 2860 bytes; stay well below so the code stays easy to scan.
        assertTrue("invite ${pending.invite.bytes.size}", pending.invite.bytes.size < 1500)
        assertTrue("reply ${reply.size}", reply.size < 1500)
        assertEquals(Handshake.Kind.INVITE, Handshake.kindOf(pending.invite.bytes))
        assertEquals(Handshake.Kind.REPLY, Handshake.kindOf(reply))
        assertEquals(Handshake.Kind.NOT_PYRSOS, Handshake.kindOf("https://example.org".toByteArray()))
    }

    @Test
    fun aChangedReplyIsRefused() {
        val pending = Handshake.invite(a, "A", day)
        val (reply, _) = Handshake.reply(b, "B", pending.invite.bytes, day)
        for (i in listOf(5, 30, 60, 100, 200, 1000, reply.size - 20, reply.size - 1)) {
            val bad = reply.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            refuses { Handshake.complete(pending, bad) }
        }
    }

    @Test
    fun aReplyToAnotherInvitationIsRefused() {
        val first = Handshake.invite(a, "A", day)
        val second = Handshake.invite(a, "A", day)
        val (reply, _) = Handshake.reply(b, "B", first.invite.bytes, day)
        refuses { Handshake.complete(second, reply) }
    }

    @Test
    fun anInvitationFromAnotherDayIsRefused() {
        val pending = Handshake.invite(a, "A", day)
        Handshake.reply(b, "B", pending.invite.bytes, day + Handshake.MAX_DAY_SKEW)
        Handshake.reply(b, "B", pending.invite.bytes, day - Handshake.MAX_DAY_SKEW)
        for (today in listOf(day + Handshake.MAX_DAY_SKEW + 1, day - Handshake.MAX_DAY_SKEW - 1, day + 5000)) {
            try {
                Handshake.reply(b, "B", pending.invite.bytes, today)
                fail("accepted on $today")
            } catch (_: StaleInviteException) {
            }
        }
    }

    @Test
    fun ownInvitationIsRefused() {
        val pending = Handshake.invite(a, "A", day)
        refuses { Handshake.reply(a, "A", pending.invite.bytes, day) }
    }

    @Test
    fun aReplySwappedForAnAttackersIsRefused() {
        // Someone photographs A's invitation and answers it with their own reply. A completes only
        // with the reply it scans, and a reply built for a different transcript does not confirm.
        val pending = Handshake.invite(a, "A", day)
        val (honest, _) = Handshake.reply(b, "B", pending.invite.bytes, day)
        val (attacker, _) = Handshake.reply(Crypto.ed25519KeyPair(), "B", pending.invite.bytes, day)
        val spliced = attacker.copyOf(attacker.size - 16) + honest.copyOfRange(honest.size - 16, honest.size)
        refuses { Handshake.complete(pending, spliced) }
    }

    @Test
    fun truncatedCodesAreRefused() {
        val pending = Handshake.invite(a, "A", day)
        refuses { Handshake.reply(b, "B", pending.invite.bytes.copyOf(pending.invite.bytes.size - 1), day) }
        refuses { Handshake.reply(b, "B", pending.invite.bytes + byteArrayOf(0), day) }
    }

    @Test
    fun anInvitationWithABorrowedIdentityIsRefused() {
        // Mallory has seen A's public identity key and puts it into her own invitation.
        val mallory = Crypto.ed25519KeyPair()
        refuses { Handshake.invite(KeyPair(mallory.private, a.public), "A", day) }
        val forged = Handshake.invite(mallory, "A", day).invite.bytes.copyOf()
        a.public.copyInto(forged, IDENTITY_IN_INVITE)
        refuses { Handshake.reply(b, "B", forged, day) }
    }

    @Test
    fun aReplyWithABorrowedIdentityIsRefused() {
        val mallory = Crypto.ed25519KeyPair()
        val pending = Handshake.invite(a, "A", day)
        val (reply, _) = Handshake.reply(mallory, "B", pending.invite.bytes, day)
        val forged = reply.copyOf()
        b.public.copyInto(forged, IDENTITY_IN_REPLY)
        refuses { Handshake.complete(pending, forged) }
    }

    @Test
    fun aSignedReplyCannotBeMovedToAnotherInvitation() {
        // Same invitation id, different invitation: the replier's signature covers all of it.
        val first = Handshake.invite(a, "A", day)
        val (reply, _) = Handshake.reply(b, "B", first.invite.bytes, day)
        val second = Handshake.invite(a, "A", day)
        refuses { Handshake.complete(second, reply) }
    }

    /** An invitation whose name bytes were swapped, signed again by its own identity key. */
    private fun inviteWithName(name: ByteArray): ByteArray {
        val good = Handshake.invite(a, "x".repeat(name.size), day).invite.bytes
        val body = good.copyOf(good.size - 64)
        val at = body.size - name.size
        name.copyInto(body, at)
        return body + Crypto.sign(a.private, "Pyrsos v1 invite".toByteArray() + body)
    }

    @Test
    fun aMalformedMlKemKeyIsRefusedNotACrash() {
        val good = Handshake.invite(a, "A", day).invite.bytes
        val body = good.copyOf(good.size - 64)
        val kemAt = 3 + 1 + 1 + 16 + 2 + 32 * 3
        for (i in 0 until Crypto.ML_KEM_PUBLIC) body[kemAt + i] = 0xFF.toByte()
        val bad = body + Crypto.sign(a.private, "Pyrsos v1 invite".toByteArray() + body)
        try {
            Handshake.reply(b, "B", bad, day)
            fail("accepted")
        } catch (_: MalformedException) {
        }
        try {
            Crypto.mlKemEncapsulate(body.copyOfRange(kemAt, kemAt + Crypto.ML_KEM_PUBLIC))
            fail("accepted")
        } catch (_: MalformedException) {
        }
    }

    @Test
    fun badNamesAreRefused() {
        val bad = listOf(
            byteArrayOf(0x41, 0xC3.toByte(), 0x28),                          // malformed UTF-8
            byteArrayOf(0x41, 0xC0.toByte(), 0xAF.toByte()),                 // overlong
            "A\u0007B".toByteArray(),                                        // control
            "A\u202EB".toByteArray(),                                        // right-to-left override
            "A\u2066B".toByteArray(),                                        // isolate
            "A\u200FB".toByteArray(),                                        // right-to-left mark
            "A\u0000B".toByteArray(),
        )
        for (n in bad) refuses { Handshake.reply(b, "B", inviteWithName(n), day) }
    }

    @Test
    fun ordinaryNamesAreAccepted() {
        for (n in listOf("Alice", "Μαρία", "李雷", "Zoë \uD83D\uDC68\u200D\uD83D\uDC69")) {
            val bytes = n.toByteArray()
            Handshake.reply(b, "B", inviteWithName(bytes), day)
            Handshake.reply(b, n, Handshake.invite(a, n, day).invite.bytes, day)
        }
    }

    private companion object {
        const val IDENTITY_IN_INVITE = 3 + 1 + 1 + 16 + 2
        const val IDENTITY_IN_REPLY = 3 + 1 + 1 + 16
    }
}
