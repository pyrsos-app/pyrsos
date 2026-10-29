// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import java.io.ByteArrayOutputStream

/** Thrown when bytes from outside (a QR code, a peer, the store) do not parse. */
class MalformedException(message: String) : Exception(message)

fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var at = 0
    for (p in parts) {
        p.copyInto(out, at)
        at += p.size
    }
    return out
}

/** Comparison whose running time does not depend on where the arrays differ. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    if (length % 2 != 0) throw MalformedException("odd hex length")
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

class ByteWriter {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = apply { out.write(v and 0xff) }

    fun u16(v: Int) = apply {
        require(v in 0..0xffff)
        out.write(v ushr 8)
        out.write(v and 0xff)
    }

    fun u32(v: Long) = apply {
        require(v in 0..0xffffffffL)
        for (s in 24 downTo 0 step 8) out.write(((v ushr s) and 0xff).toInt())
    }

    fun u64(v: Long) = apply {
        for (s in 56 downTo 0 step 8) out.write(((v ushr s) and 0xff).toInt())
    }

    fun raw(b: ByteArray) = apply { out.write(b) }

    /** A byte string of up to 65535 bytes, prefixed with its length. */
    fun bytes16(b: ByteArray) = apply {
        u16(b.size)
        out.write(b)
    }

    fun bytes(): ByteArray = out.toByteArray()
}

class ByteReader(private val buf: ByteArray) {
    private var at = 0

    val remaining: Int get() = buf.size - at

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw MalformedException("truncated: need $n, have $remaining")
    }

    fun u8(): Int {
        need(1)
        return buf[at++].toInt() and 0xff
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Long {
        var v = 0L
        repeat(4) { v = (v shl 8) or u8().toLong() }
        return v
    }

    fun u64(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or u8().toLong() }
        return v
    }

    fun raw(n: Int): ByteArray {
        need(n)
        return buf.copyOfRange(at, at + n).also { at += n }
    }

    fun bytes16(): ByteArray = raw(u16())

    fun end() {
        if (remaining != 0) throw MalformedException("$remaining trailing bytes")
    }
}
