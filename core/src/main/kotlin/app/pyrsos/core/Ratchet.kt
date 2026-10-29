// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

/**
 * The Double Ratchet, after the public specification by Trevor Perrin and Moxie Marlinspike
 * (placed in the public domain), with X25519, HKDF-SHA256, HMAC-SHA256 and ChaCha20-Poly1305.
 *
 * Every message gets its own key, deleted once used: a phone taken later cannot open what it
 * already read (forward secrecy). Every reply carries a fresh X25519 key, so a state stolen once
 * stops working after the next exchange (break-in recovery).
 *
 * The state is never changed in place. [encrypt] and [decrypt] return the next state, and the
 * caller stores it in the same transaction as what the message produced: a failed decryption
 * leaves nothing changed, and a crash cannot reuse a message key.
 */
class Ratchet private constructor(
    private val rootKey: ByteArray,
    private val dhs: KeyPair,
    private val dhr: ByteArray?,
    private val cks: ByteArray?,
    private val ckr: ByteArray?,
    private val ns: Long,
    private val nr: Long,
    private val pn: Long,
    /** Keys of messages skipped over, kept so they still open when they arrive late. */
    private val skipped: LinkedHashMap<String, ByteArray>,
) {
    class Header(val dh: ByteArray, val pn: Long, val n: Long) {
        fun encode(): ByteArray = ByteWriter().raw(dh).u32(pn).u32(n).bytes()

        companion object {
            const val SIZE = 32 + 4 + 4

            fun decode(b: ByteArray): Header {
                val r = ByteReader(b)
                return Header(r.raw(32), r.u32(), r.u32()).also { r.end() }
            }
        }
    }

    class Encrypted(val next: Ratchet, val header: Header, val ciphertext: ByteArray)

    /**
     * [step] is set when this message carried a new ratchet key from the other side: the tags
     * move to new seeds then too (see [Session]).
     */
    class Decrypted(val next: Ratchet, val plaintext: ByteArray, val step: TagStep? = null)

    /**
     * New tag seeds from one DH step. [send]: for what this side sends from now on; the other side
     * already knows it, having made it when it chose the key that caused this step. [receive]: for
     * what the other side will send once it sees this side's new key.
     */
    class TagStep(val send: ByteArray, val receive: ByteArray)

    /** The tag seed the other side will send on after it has seen this side's current key. */
    internal fun pendingTagSeed(): ByteArray = tagSeed(rootKey)

    fun encrypt(plaintext: ByteArray, ad: ByteArray): Encrypted {
        val chain = cks ?: throw IllegalStateException("no sending chain")
        val (mk, nextCk) = kdfChain(chain)
        val header = Header(dhs.public, pn, ns)
        val ct = sealMessage(mk, plaintext, concat(ad, header.encode()))
        val next = Ratchet(rootKey, dhs, dhr, nextCk, ckr, ns + 1, nr, pn, LinkedHashMap(skipped))
        return Encrypted(next, header, ct)
    }

    fun decrypt(header: Header, ciphertext: ByteArray, ad: ByteArray): Decrypted {
        val aad = concat(ad, header.encode())

        val skippedId = skippedKey(header.dh, header.n)
        skipped[skippedId]?.let { mk ->
            val pt = openMessage(mk, ciphertext, aad)
            val rest = LinkedHashMap(skipped).apply { remove(skippedId) }
            return Decrypted(Ratchet(rootKey, dhs, dhr, cks, ckr, ns, nr, pn, rest), pt)
        }

        var st = Work(rootKey, dhs, dhr, cks, ckr, ns, nr, pn, LinkedHashMap(skipped))
        var step: TagStep? = null
        if (st.dhr == null || !constantTimeEquals(header.dh, st.dhr!!)) {
            st.skipUntil(header.pn)
            step = st.dhStep(header.dh)
        }
        st.skipUntil(header.n)
        val (mk, nextCk) = kdfChain(st.ckr ?: throw CryptoException("no receiving chain"))
        val pt = openMessage(mk, ciphertext, aad)
        st.ckr = nextCk
        st.nr += 1
        st.trimSkipped()
        return Decrypted(st.freeze(), pt, step)
    }

    fun encode(): ByteArray {
        val w = ByteWriter().u8(VERSION).raw(rootKey).raw(dhs.private).raw(dhs.public)
        w.u8((if (dhr != null) 1 else 0) or (if (cks != null) 2 else 0) or (if (ckr != null) 4 else 0))
        dhr?.let { w.raw(it) }
        cks?.let { w.raw(it) }
        ckr?.let { w.raw(it) }
        w.u32(ns).u32(nr).u32(pn).u16(skipped.size)
        for ((id, mk) in skipped) {
            val (dh, n) = parseSkippedKey(id)
            w.raw(dh).u32(n).raw(mk)
        }
        return w.bytes()
    }

    /** Mutable working copy, used only inside [decrypt] and never visible outside it. */
    private class Work(
        var rootKey: ByteArray,
        var dhs: KeyPair,
        var dhr: ByteArray?,
        var cks: ByteArray?,
        var ckr: ByteArray?,
        var ns: Long,
        var nr: Long,
        var pn: Long,
        val skipped: LinkedHashMap<String, ByteArray>,
    ) {
        fun skipUntil(until: Long) {
            if (until - nr > MAX_SKIP) throw CryptoException("would skip ${until - nr} messages")
            val chain = ckr ?: return
            var ck = chain
            val from = dhr!!
            while (nr < until) {
                val (mk, nextCk) = kdfChain(ck)
                skipped[skippedKey(from, nr)] = mk
                ck = nextCk
                nr += 1
            }
            ckr = ck
        }

        fun dhStep(remote: ByteArray): TagStep {
            pn = ns
            ns = 0
            nr = 0
            dhr = remote
            val (rk1, ck1) = kdfRoot(rootKey, Crypto.x25519(dhs.private, remote))
            ckr = ck1
            dhs = Crypto.x25519KeyPair()
            val (rk2, ck2) = kdfRoot(rk1, Crypto.x25519(dhs.private, remote))
            rootKey = rk2
            cks = ck2
            // rk1 is the root the other side reached when it chose `remote`; rk2 it reaches on
            // seeing our new key. So both sides know each seed exactly when they need it.
            return TagStep(send = tagSeed(rk1), receive = tagSeed(rk2))
        }

        fun trimSkipped() {
            val it = skipped.entries.iterator()
            while (skipped.size > MAX_STORED_SKIPPED && it.hasNext()) {
                it.next()
                it.remove()
            }
        }

        fun freeze() = Ratchet(rootKey, dhs, dhr, cks, ckr, ns, nr, pn, skipped)
    }

    companion object {
        private const val VERSION = 1

        /** The most messages one chain may jump over in a single step. */
        const val MAX_SKIP = 1000L

        /** The most skipped keys kept per conversation; the oldest go first. */
        const val MAX_STORED_SKIPPED = 2000

        /** The side that answered the invitation: it may send at once. */
        fun forReplier(root: ByteArray, own: KeyPair, inviterRatchetKey: ByteArray): Ratchet {
            val (rk, cks) = kdfRoot(root, Crypto.x25519(own.private, inviterRatchetKey))
            return Ratchet(rk, own, inviterRatchetKey, cks, null, 0, 0, 0, LinkedHashMap())
        }

        /**
         * The side that showed the invitation. It already knows the replier's ratchet key from the
         * reply, so it takes the step a first received message would cause, and may send at once too.
         */
        fun forInviter(root: ByteArray, own: KeyPair, replierRatchetKey: ByteArray): Ratchet {
            val (rk1, ckr) = kdfRoot(root, Crypto.x25519(own.private, replierRatchetKey))
            val next = Crypto.x25519KeyPair()
            val (rk2, cks) = kdfRoot(rk1, Crypto.x25519(next.private, replierRatchetKey))
            return Ratchet(rk2, next, replierRatchetKey, cks, ckr, 0, 0, 0, LinkedHashMap())
        }

        fun decode(b: ByteArray): Ratchet {
            val r = ByteReader(b)
            if (r.u8() != VERSION) throw MalformedException("ratchet version")
            val rk = r.raw(32)
            val dhs = KeyPair(r.raw(32), r.raw(32))
            val flags = r.u8()
            val dhr = if (flags and 1 != 0) r.raw(32) else null
            val cks = if (flags and 2 != 0) r.raw(32) else null
            val ckr = if (flags and 4 != 0) r.raw(32) else null
            val ns = r.u32()
            val nr = r.u32()
            val pn = r.u32()
            val skipped = LinkedHashMap<String, ByteArray>()
            repeat(r.u16()) {
                val dh = r.raw(32)
                val n = r.u32()
                skipped[skippedKey(dh, n)] = r.raw(32)
            }
            r.end()
            return Ratchet(rk, dhs, dhr, cks, ckr, ns, nr, pn, skipped)
        }

        private fun skippedKey(dh: ByteArray, n: Long) = "${dh.toHex()}:$n"

        private fun parseSkippedKey(id: String): Pair<ByteArray, Long> {
            val (dh, n) = id.split(':')
            return dh.hexToBytes() to n.toLong()
        }

        private fun kdfRoot(rk: ByteArray, dh: ByteArray): Pair<ByteArray, ByteArray> {
            val out = Crypto.hkdf(rk, dh, "Pyrsos v1 ratchet root", 64)
            return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
        }

        private fun tagSeed(rk: ByteArray): ByteArray = Crypto.hkdf(rk, ByteArray(0), "Pyrsos v1 tag epoch", 32)

        private fun kdfChain(ck: ByteArray): Pair<ByteArray, ByteArray> =
            Crypto.hmac(ck, byteArrayOf(1)) to Crypto.hmac(ck, byteArrayOf(2))

        private fun messageKeys(mk: ByteArray): Pair<ByteArray, ByteArray> {
            val out = Crypto.hkdf(ByteArray(32), mk, "Pyrsos v1 message", Crypto.KEY + Crypto.NONCE)
            return out.copyOfRange(0, Crypto.KEY) to out.copyOfRange(Crypto.KEY, Crypto.KEY + Crypto.NONCE)
        }

        private fun sealMessage(mk: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
            val (key, nonce) = messageKeys(mk)
            return Crypto.seal(key, nonce, plaintext, aad)
        }

        private fun openMessage(mk: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
            val (key, nonce) = messageKeys(mk)
            return Crypto.open(key, nonce, ciphertext, aad)
        }
    }
}
