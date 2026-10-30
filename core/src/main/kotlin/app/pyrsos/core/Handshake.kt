// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.abs

/**
 * Two people standing together become contacts with two QR codes and no network.
 *
 * 1. A shows an invitation: a fresh ML-KEM-768 key, two fresh X25519 keys and A's identity key,
 *    signed with that identity key.
 * 2. B scans it and shows a reply: B's identity key, two fresh X25519 keys, an ML-KEM ciphertext
 *    to A's key, B's signature over the invitation and the reply, and a confirmation code.
 * 3. A scans the reply. Both now hold the same secret and either may write first.
 *
 * The camera is the authentication: each side read the other's code off the screen of the phone
 * in front of it. The signatures make each identity key provable: a code that carries someone
 * else's public identity key cannot be signed without that person's phone, so the short
 * identity code the app shows ([IdentityCode]) cannot be borrowed. The shared secret mixes X25519 with ML-KEM, so recorded traffic stays closed to
 * an attacker who later breaks X25519 (a quantum computer, for one), and every key in the
 * handshake is fresh and deleted after it.
 */
/** A code whose signature does not match the identity key it carries: someone else's key, borrowed. */
class ForgedException(message: String) : CryptoException(message)

/** An invitation made on a day far from this phone's today: an old code, or a clock set wrong. */
class StaleInviteException(message: String) : CryptoException(message)

object Handshake {
    private val MAGIC = "PYR".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val TYPE_INVITE = 1
    private const val TYPE_REPLY = 2
    private const val INVITE_ID = 16
    private const val CONFIRM = 16
    private const val SIGNATURE = 64
    private val INVITE_CONTEXT = "Pyrsos v1 invite".toByteArray()
    private val REPLY_CONTEXT = "Pyrsos v1 reply".toByteArray()

    /** Names travel in the QR code; the app shortens them before they get here. */
    const val MAX_NAME_BYTES = 48

    /** How far, in days, an invitation's day may be from the replier's today. */
    const val MAX_DAY_SKEW = TagReceiver.FUTURE_DAYS

    class Invite(
        val id: ByteArray,
        val day: Int,
        val identity: ByteArray,
        val ratchet: ByteArray,
        val ephemeral: ByteArray,
        val kem: ByteArray,
        val name: String,
        val bytes: ByteArray,
    )

    /** What the inviter keeps until the reply is scanned. Holds secrets: store it encrypted. */
    class PendingInvite(
        val invite: Invite,
        val ratchet: KeyPair,
        val ephemeral: KeyPair,
        val kem: KeyPair,
    ) {
        fun encode(): ByteArray = ByteWriter()
            .bytes16(invite.bytes)
            .raw(ratchet.private).raw(ephemeral.private)
            .bytes16(kem.private)
            .bytes()

        companion object {
            fun decode(b: ByteArray): PendingInvite {
                val r = ByteReader(b)
                val invite = parseInvite(r.bytes16())
                val ratchetPriv = r.raw(32)
                val ephPriv = r.raw(32)
                val kemPriv = r.bytes16()
                r.end()
                return PendingInvite(
                    invite,
                    KeyPair(ratchetPriv, invite.ratchet),
                    KeyPair(ephPriv, invite.ephemeral),
                    KeyPair(kemPriv, invite.kem),
                )
            }
        }
    }

    class Established(val peerIdentity: ByteArray, val peerName: String, val session: Session)

    fun invite(identity: KeyPair, name: String, today: Int): PendingInvite {
        val ratchet = Crypto.x25519KeyPair()
        val eph = Crypto.x25519KeyPair()
        val kem = Crypto.mlKemKeyPair()
        val id = Crypto.random(INVITE_ID)
        val body = ByteWriter()
            .raw(MAGIC).u8(VERSION).u8(TYPE_INVITE)
            .raw(id).u16(today)
            .raw(identity.public).raw(ratchet.public).raw(eph.public)
            .raw(kem.public)
            .bytes16(nameBytes(name))
            .bytes()
        val bytes = concat(body, Crypto.sign(identity.private, concat(INVITE_CONTEXT, body)))
        return PendingInvite(parseInvite(bytes), ratchet, eph, kem)
    }

    /** What a scanned code is, before deciding what to do with it. */
    fun kindOf(code: ByteArray): Kind {
        if (code.size < MAGIC.size + 2 || !code.copyOf(MAGIC.size).contentEquals(MAGIC)) return Kind.NOT_PYRSOS
        if (code[MAGIC.size].toInt() != VERSION) return Kind.UNSUPPORTED_VERSION
        return when (code[MAGIC.size + 1].toInt()) {
            TYPE_INVITE -> Kind.INVITE
            TYPE_REPLY -> Kind.REPLY
            else -> Kind.NOT_PYRSOS
        }
    }

    enum class Kind { INVITE, REPLY, NOT_PYRSOS, UNSUPPORTED_VERSION }

    /** B's side: answers a scanned invitation. Returns the reply to show and the new session. */
    fun reply(identity: KeyPair, name: String, inviteBytes: ByteArray, today: Int): Pair<ByteArray, Established> {
        val invite = parseInvite(inviteBytes)
        if (constantTimeEquals(invite.identity, identity.public)) throw CryptoException("this is your own invitation")
        // The tags start on the invitation's day: one far from today is an old code, or a clock
        // set wrong, and would leave the conversation unable to find its envelopes.
        if (abs(invite.day - today) > MAX_DAY_SKEW) throw StaleInviteException("invitation day ${invite.day} is far from $today")
        val ratchet = Crypto.x25519KeyPair()
        val eph = Crypto.x25519KeyPair()
        val enc = Crypto.mlKemEncapsulate(invite.kem)
        val body = ByteWriter()
            .raw(MAGIC).u8(VERSION).u8(TYPE_REPLY)
            .raw(invite.id)
            .raw(identity.public).raw(ratchet.public).raw(eph.public)
            .raw(enc.ciphertext)
            .bytes16(nameBytes(name))
            .bytes()
        val signature = Crypto.sign(identity.private, replySigned(invite.bytes, body))
        val keys = derive(invite.bytes, body, Crypto.x25519(eph.private, invite.ephemeral), enc.secret)
        val reply = concat(body, signature, Crypto.hmac(keys.confirm, "reply".toByteArray()).copyOf(CONFIRM))
        val session = Session.handshake(
            Ratchet.forReplier(keys.root, ratchet, invite.ratchet),
            TagSender.start(keys.replierToInviter, invite.day),
            TagReceiver.start(keys.inviterToReplier, invite.day),
            concat(invite.identity, identity.public),
            inviter = false,
        )
        return reply to Established(invite.identity, invite.name, session)
    }

    /** A's side: completes the handshake from the scanned reply. */
    fun complete(pending: PendingInvite, replyBytes: ByteArray): Established {
        val r = ByteReader(replyBytes)
        readHeader(r, TYPE_REPLY)
        val inviteId = r.raw(INVITE_ID)
        if (!constantTimeEquals(inviteId, pending.invite.id)) throw CryptoException("reply is for another invitation")
        val identity = r.raw(32)
        val ratchet = r.raw(32)
        val eph = r.raw(32)
        val ct = r.raw(Crypto.ML_KEM_CIPHERTEXT)
        val name = parseName(r.bytes16())
        val body = replyBytes.copyOf(replyBytes.size - r.remaining)
        val signature = r.raw(SIGNATURE)
        val confirm = r.raw(CONFIRM)
        r.end()
        if (constantTimeEquals(identity, pending.invite.identity)) throw CryptoException("reply carries your own identity")
        if (!Crypto.verify(identity, replySigned(pending.invite.bytes, body), signature)) {
            throw ForgedException("reply is not signed by its identity key")
        }

        val keys = derive(
            pending.invite.bytes,
            body,
            Crypto.x25519(pending.ephemeral.private, eph),
            Crypto.mlKemDecapsulate(pending.kem.private, ct),
        )
        if (!constantTimeEquals(confirm, Crypto.hmac(keys.confirm, "reply".toByteArray()).copyOf(CONFIRM))) {
            throw CryptoException("reply does not confirm")
        }
        val session = Session.handshake(
            Ratchet.forInviter(keys.root, pending.ratchet, ratchet),
            TagSender.start(keys.inviterToReplier, pending.invite.day),
            TagReceiver.start(keys.replierToInviter, pending.invite.day),
            concat(pending.invite.identity, identity),
            inviter = true,
        )
        return Established(identity, name, session)
    }

    private class Keys(
        val root: ByteArray,
        val inviterToReplier: ByteArray,
        val replierToInviter: ByteArray,
        val confirm: ByteArray,
    )

    /** The replier signs the whole invitation too, so a signed reply cannot be moved to another one. */
    private fun replySigned(invite: ByteArray, replyBody: ByteArray): ByteArray =
        concat(REPLY_CONTEXT, Crypto.sha256(invite), replyBody)

    private fun derive(invite: ByteArray, replyBody: ByteArray, dh: ByteArray, kem: ByteArray): Keys {
        val transcript = Crypto.sha256("Pyrsos v1 handshake".toByteArray(), invite, replyBody)
        val okm = Crypto.hkdf(transcript, concat(dh, kem), "Pyrsos v1 session", 32 * 4)
        return Keys(
            okm.copyOfRange(0, 32),
            okm.copyOfRange(32, 64),
            okm.copyOfRange(64, 96),
            okm.copyOfRange(96, 128),
        )
    }

    private fun readHeader(r: ByteReader, type: Int) {
        if (!r.raw(MAGIC.size).contentEquals(MAGIC)) throw MalformedException("not a Pyrsos code")
        if (r.u8() != VERSION) throw MalformedException("unsupported version")
        if (r.u8() != type) throw MalformedException("wrong code type")
    }

    private fun parseInvite(bytes: ByteArray): Invite {
        val r = ByteReader(bytes)
        readHeader(r, TYPE_INVITE)
        val invite = Invite(
            id = r.raw(INVITE_ID),
            day = r.u16(),
            identity = r.raw(32),
            ratchet = r.raw(32),
            ephemeral = r.raw(32),
            kem = r.raw(Crypto.ML_KEM_PUBLIC),
            name = parseName(r.bytes16()),
            bytes = bytes.copyOf(),
        )
        val body = bytes.copyOf(bytes.size - r.remaining)
        val signature = r.raw(SIGNATURE)
        r.end()
        if (!Crypto.verify(invite.identity, concat(INVITE_CONTEXT, body), signature)) {
            throw ForgedException("invitation is not signed by its identity key")
        }
        return invite
    }

    private fun nameBytes(name: String): ByteArray {
        val b = name.trim().toByteArray(Charsets.UTF_8)
        require(b.size in 1..MAX_NAME_BYTES) { "name must be 1..$MAX_NAME_BYTES bytes" }
        return b
    }

    private fun parseName(b: ByteArray): String {
        if (b.size !in 1..MAX_NAME_BYTES) throw MalformedException("name length")
        // Strict: malformed UTF-8 is refused, not repaired. Control and bidi-format characters
        // could reorder or hide text around the name; the app strips the same ones from names it makes.
        val name = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) {
            throw MalformedException("name is not UTF-8")
        }
        if (name.any { isHiddenChar(it) }) throw MalformedException("name has control characters")
        return name
    }

    private fun isHiddenChar(c: Char): Boolean =
        Character.getType(c) == Character.CONTROL.toInt() ||
            c == '\u061C' || c in '\u200E'..'\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' ||
            c == '\u2028' || c == '\u2029'
}
