// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.nearby

import android.os.SystemClock
import app.pyrsos.Messenger
import app.pyrsos.core.ByteReader
import app.pyrsos.core.ByteWriter
import app.pyrsos.core.Envelope
import app.pyrsos.core.MalformedException
import app.pyrsos.core.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * What two phones say to each other once a channel is open. Both sides run the same steps:
 *
 *   HELLO   protocol version and the advertising nonce, so both remember they just met
 *   INV     the ids of envelopes carried: a random part of them (see [Messenger.inventory])
 *   WANT    the ids of the other's envelopes not carried yet
 *   ENV...  each envelope asked for
 *   DONE
 *   GOT     sent after reading the other's DONE: everything it sent has arrived
 *
 * Reading and writing run at the same time: two phones each sending a large inventory before
 * reading would fill both buffers and wait on each other forever.
 *
 * Closing a Bluetooth socket drops whatever has not left yet, so a side that closed right after
 * its own DONE could cut off the other's last envelopes. Each side therefore waits for the other's
 * GOT before it closes. Envelopes are counted as handed over only after that GOT; envelopes
 * received are kept as they arrive, in batches, so a channel that breaks halfway loses nothing
 * that came through.
 */
internal class Sync(input: InputStream, output: OutputStream, private val close: () -> Unit) {
    private val inp = DataInputStream(input.buffered())
    private val out = DataOutputStream(output.buffered())

    /** When a frame last came in or went out, and whether the other phone has said HELLO yet. */
    @Volatile private var lastActivity = SystemClock.elapsedRealtime()
    @Volatile private var greeted = false

    class Result(val peerNonce: Int, val received: Int, val sent: Int, val opened: Int)

    /**
     * [expectNonce]: the nonce this phone called, which the other must give in its HELLO; a phone
     * answering under someone else's record is cut off.
     */
    suspend fun run(messenger: Messenger, ownNonce: Int, expectNonce: Int?): Result = coroutineScope {
        // A phone that connects and then says nothing, or stops halfway, would hold this phone's
        // channel until the overall deadline. Closing the socket is what ends a blocking read.
        val watchdog = launch {
            while (true) {
                delay(WATCH_STEP_MS)
                val quiet = SystemClock.elapsedRealtime() - lastActivity
                if (quiet > if (greeted) FRAME_WAIT_MS else HELLO_WAIT_MS) {
                    close()
                    throw MalformedException("the other phone went quiet")
                }
            }
        }
        val inventory = messenger.inventory()
        val have = inventory.mapTo(HashSet()) { it.toHex() }
        val theirInventory = CompletableDeferred<List<ByteArray>>()
        val theirWant = CompletableDeferred<List<ByteArray>>()
        val theirsDone = CompletableDeferred<Unit>()
        val asked = CompletableDeferred<MutableSet<String>>()

        val reader = async(Dispatchers.IO) {
            val hello = ByteReader(expect(HELLO))
            if (!hello.raw(MAGIC.size).contentEquals(MAGIC) || hello.u8() != VERSION) throw MalformedException("not a Pyrsos peer")
            val peerNonce = hello.u32().toInt()
            if (expectNonce != null && peerNonce != expectNonce) throw MalformedException("answered under another record")
            greeted = true
            theirInventory.complete(ids(expect(INV)))
            theirWant.complete(ids(expect(WANT)))
            val wanted = asked.await()
            val batch = ArrayList<ByteArray>()
            var received = 0
            var opened = 0
            try {
                while (true) {
                    val (type, body) = frame()
                    when (type) {
                        ENV -> {
                            // Only what was asked for, each once: a peer cannot push anything else.
                            if (!wanted.remove(Envelope.parse(body).id.toHex())) throw MalformedException("unrequested envelope")
                            batch += body
                            received++
                            if (batch.size >= INGEST_BATCH) {
                                opened += messenger.ingest(batch.toList()).opened
                                batch.clear()
                            }
                        }
                        DONE -> break
                        else -> throw MalformedException("unexpected frame $type")
                    }
                }
            } finally {
                if (batch.isNotEmpty()) withContext(NonCancellable) { opened += messenger.ingest(batch.toList()).opened }
            }
            theirsDone.complete(Unit)
            Triple(peerNonce, received, opened)
        }

        val writer = async(Dispatchers.IO) {
            send(HELLO, ByteWriter().raw(MAGIC).u8(VERSION).u32(ownNonce.toLong() and 0xffffffffL).bytes())
            send(INV, idsBody(inventory))
            out.flush()
            val want = theirInventory.await().distinctBy { it.toHex() }.filter { it.toHex() !in have }.take(MAX_ACCEPT)
            asked.complete(want.mapTo(HashSet()) { it.toHex() })
            send(WANT, idsBody(want))
            out.flush()
            // Each envelope once, and only from what was offered: asking for one id many times
            // must not make this phone send it many times.
            val requested = theirWant.await().distinctBy { it.toHex() }.filter { it.toHex() in have }
            val sent = ArrayList<ByteArray>()
            for (chunk in requested.chunked(SEND_BATCH)) {
                for ((id, data) in messenger.envelopes(chunk)) {
                    send(ENV, data)
                    sent += id
                }
            }
            send(DONE, ByteArray(0))
            out.flush()
            theirsDone.await()
            send(GOT, ByteArray(0))
            out.flush()
            sent
        }

        val (peerNonce, received, opened) = reader.await()
        val sent = writer.await()
        // Their GOT: everything we sent was read. Without it nothing counts as handed over, and the
        // next meeting offers the same envelopes again.
        expect(GOT)
        watchdog.cancel()
        messenger.markHanded(sent)
        Result(peerNonce, received, sent.size, opened)
    }

    private fun send(type: Int, body: ByteArray) {
        out.writeByte(type)
        out.writeInt(body.size)
        out.write(body)
        lastActivity = SystemClock.elapsedRealtime()
    }

    private fun frame(): Pair<Int, ByteArray> {
        val type = inp.readUnsignedByte()
        val len = inp.readInt()
        if (len < 0 || len > MAX_FRAME) throw MalformedException("frame length $len")
        return type to ByteArray(len).also {
            inp.readFully(it)
            lastActivity = SystemClock.elapsedRealtime()
        }
    }

    private fun expect(type: Int): ByteArray {
        val (t, body) = frame()
        if (t != type) throw MalformedException("expected frame $type, got $t")
        return body
    }

    private fun idsBody(ids: List<ByteArray>): ByteArray {
        val w = ByteWriter().u32(ids.size.toLong())
        ids.forEach { w.raw(it) }
        return w.bytes()
    }

    private fun ids(body: ByteArray): List<ByteArray> {
        val r = ByteReader(body)
        val n = r.u32()
        if (n > MAX_IDS) throw MalformedException("$n ids")
        return List(n.toInt()) { r.raw(Envelope.ID_SIZE) }.also { r.end() }
    }

    private companion object {
        val MAGIC = "PYRSOS".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1
        const val HELLO = 1
        const val INV = 2
        const val WANT = 3
        const val ENV = 4
        const val DONE = 5
        const val GOT = 6

        /** An inventory, or what is asked of it, is never longer than what one phone offers. */
        const val MAX_IDS = Messenger.MAX_OFFER.toLong()
        const val MAX_FRAME = (MAX_IDS * Envelope.ID_SIZE + 4).toInt()

        /** The most envelopes taken from one phone in one meeting. */
        const val MAX_ACCEPT = 1_000

        /** Envelopes stored per transaction as they arrive. */
        const val INGEST_BATCH = 100

        /** Envelopes read from storage at a time while sending. */
        const val SEND_BATCH = 100

        const val HELLO_WAIT_MS = 5_000L
        const val FRAME_WAIT_MS = 10_000L
        const val WATCH_STEP_MS = 1_000L
    }
}
