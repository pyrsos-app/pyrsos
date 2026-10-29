// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityCodeTest {
    @Test
    fun theSameKeyAlwaysGivesTheSameCode() {
        val key = Crypto.ed25519KeyPair().public
        assertEquals(IdentityCode.of(key), IdentityCode.of(key.copyOf()))
    }

    @Test
    fun codesAreTwelveReadableCharactersInThreeGroups() {
        repeat(200) {
            val code = IdentityCode.of(Crypto.ed25519KeyPair().public)
            assertTrue(code, Regex("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}").matches(code))
        }
    }

    @Test
    fun aKnownKeyGivesAFixedCode() {
        // Pins the derivation: changing it would change every code people have written down.
        assertEquals("ZA0N-0J45-TEMX", IdentityCode.of(ByteArray(32)))
    }

    @Test
    fun oneBitChangesTheCode() {
        val key = Crypto.ed25519KeyPair().public
        val other = key.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        assertNotEquals(IdentityCode.of(key), IdentityCode.of(other))
    }
}
