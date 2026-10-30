// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.pyrsos.core.Base45
import app.pyrsos.core.Crypto
import app.pyrsos.core.CryptoException
import app.pyrsos.core.Envelope
import app.pyrsos.core.ForgedException
import app.pyrsos.core.Handshake
import app.pyrsos.core.IdentityCode
import app.pyrsos.core.KeyPair
import app.pyrsos.core.MalformedException
import app.pyrsos.core.Payload
import app.pyrsos.core.Session
import app.pyrsos.core.StaleInviteException
import app.pyrsos.core.TagReceiver
import app.pyrsos.core.TagSlot
import app.pyrsos.core.constantTimeEquals
import app.pyrsos.core.toHex
import app.pyrsos.data.Store
import app.pyrsos.data.VaultKey
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executors

/**
 * Everything the app does with contacts, messages and envelopes. All of it runs on one thread,
 * so the databases and the tag index never see two changes at once.
 *
 * Envelopes and settings live in [outer], always open. Identity, contacts, sessions and messages
 * live in [data]: the outer database when there is no PIN, the [vault] when there is one. While
 * the vault is locked, envelopes are still taken and carried; those that may be for this phone are
 * opened when the PIN is entered ([unlock]).
 */
class Messenger(private val context: Context, private val outer: Store) {
    /** Open only between [unlock] and [lock], and only when a PIN is set. */
    @Volatile private var vault: Store? = null

    /** The vault's database key while it is open: needed to change the PIN. */
    private var vaultKey: ByteArray? = null

    /** A PIN is set, so identity, contacts and messages are in the vault. */
    @Volatile private var vaultMode = false

    /** The database holding identity, contacts and messages; throws while the vault is locked. */
    private val data: Store
        get() = vault ?: if (vaultMode) throw LockedException() else outer

    class LockedException : IllegalStateException("Pyrsos is locked")
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "pyrsos-store") }.asCoroutineDispatcher()

    private val _version = MutableStateFlow(0L)

    /** Changes whenever something the screens show has changed. */
    val version: StateFlow<Long> = _version

    /** Fires when a message is written, so nearby phones get it at the next sighting. */
    private val _outgoing = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val outgoing: SharedFlow<Unit> = _outgoing

    /** Tag (hex) to the contact and slot it opens. Holds only what is expected today. */
    private val random = SecureRandom()
    private val tagIndex = HashMap<String, Pair<String, TagSlot>>()
    private val tagsByContact = HashMap<String, List<String>>()
    private var indexDay = -1

    class Me(val name: String, val code: String)

    class ContactRow(
        val id: String,
        val name: String,
        /** [IdentityCode] of their key: names repeat, this does not. */
        val code: String,
        val confirmed: Boolean,
        val lastText: String?,
        val unread: Int,
    )

    class MessageRow(
        val id: String,
        val outgoing: Boolean,
        val text: String,
        /** When it was written here (own) or arrived here (received): the order the chat shows. */
        val at: Long,
        val delivered: Boolean,
        val handed: Int,
        /** When the writer wrote it, by the writer's clock. */
        val sentAt: Long,
        /** Own messages: when the receipt came back to this phone; null if not yet, or not known. */
        val deliveredAt: Long?,
    )

    sealed class ScanResult {
        /** You scanned an invitation: show this answer so they can finish. */
        class ShowReply(val code: String, val contactId: String, val name: String, val identityCode: String) : ScanResult()

        /** You scanned the answer to your invitation: they are a contact. */
        class Completed(val contactId: String, val name: String) : ScanResult()

        class Refused(val reason: Reason) : ScanResult()

        enum class Reason { NOT_PYRSOS, OTHER_VERSION, OWN_CODE, FORGED, REPLY_WITHOUT_INVITE, REPLY_DOES_NOT_MATCH, STALE_INVITE, ALREADY_CONTACT, BOTH_SHOWING }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(worker) { block() }

    private fun changed() {
        _version.value += 1
    }

    fun today(): Int = LocalDate.now(ZoneOffset.UTC).toEpochDay().toInt()

    // Identity -----------------------------------------------------------------------------------

    // The reads the screens make answer "nothing" while locked: a screen may still be asking in the
    // moment the vault closes behind it.

    suspend fun me(): Me? = io {
        if (isLocked) return@io null
        data.query("SELECT name, public FROM identity WHERE k = 1") { Me(it.getString(0), IdentityCode.of(it.getBlob(1))) }.firstOrNull()
    }

    suspend fun createIdentity(name: String) = io {
        val kp = Crypto.ed25519KeyPair()
        data.exec("INSERT INTO identity (k, private, public, name) VALUES (1, ?, ?, ?)", kp.private, kp.public, fitName(name))
        changed()
    }

    /** The name goes out only in new invites; contacts made before keep the one they saw. */
    suspend fun rename(name: String) = io {
        data.exec("UPDATE identity SET name = ? WHERE k = 1", fitName(name))
        changed()
    }

    private fun identity(): Pair<KeyPair, String> =
        data.query("SELECT private, public, name FROM identity WHERE k = 1") {
            KeyPair(it.getBlob(0), it.getBlob(1)) to it.getString(2)
        }.single()

    // Contacts and messages ----------------------------------------------------------------------

    suspend fun contacts(): List<ContactRow> = io {
        if (isLocked) return@io emptyList()
        data.query(
            """SELECT c.id, c.name, c.identity, c.confirmed,
                (SELECT text FROM messages m WHERE m.contact_id = c.id ORDER BY at DESC LIMIT 1),
                (SELECT COUNT(*) FROM messages m WHERE m.contact_id = c.id AND m.outgoing = 0 AND m.read = 0)
               FROM contacts c ORDER BY c.last_at DESC""",
        ) { ContactRow(it.getString(0), it.getString(1), IdentityCode.of(it.getBlob(2)), it.getInt(3) == 1, it.getString(4), it.getInt(5)) }
    }

    suspend fun contact(id: String): ContactRow? = io {
        if (isLocked) return@io null
        data.query("SELECT id, name, identity, confirmed FROM contacts WHERE id = ?", id) {
            ContactRow(it.getString(0), it.getString(1), IdentityCode.of(it.getBlob(2)), it.getInt(3) == 1, null, 0)
        }.firstOrNull()
    }

    suspend fun messages(contactId: String): List<MessageRow> = io {
        if (isLocked) return@io emptyList()
        val rows = data.query(
            "SELECT id, outgoing, text, at, delivered, handed, sent_at, delivered_at FROM messages WHERE contact_id = ? ORDER BY at",
            contactId,
        ) { c ->
            MessageRow(
                c.getBlob(0).toHex(), c.getInt(1) == 1, c.getString(2), c.getLong(3), c.getInt(4) == 1, c.getInt(5), c.getLong(6),
                if (c.isNull(7)) null else c.getLong(7),
            )
        }
        rows
    }

    suspend fun markRead(contactId: String) = io {
        if (isLocked) return@io
        data.exec("UPDATE messages SET read = 1 WHERE contact_id = ? AND outgoing = 0 AND read = 0", contactId)
        changed()
    }

    // Forgetting ----------------------------------------------------------------------------------
    // Only this phone forgets. Envelopes already handed to other phones stay sealed there until
    // they expire, and the contact's phone keeps its own copy of the conversation.

    /** Deletes the conversation on this phone; the contact stays. */
    suspend fun clearChat(contactId: String) = io {
        if (isLocked) throw LockedException()
        data.exec("DELETE FROM messages WHERE contact_id = ?", contactId)
        changed()
    }

    /** Deletes the contact, its keys and the conversation on this phone. */
    suspend fun removeContact(contactId: String) = io {
        if (isLocked) throw LockedException()
        data.tx {
            data.exec("DELETE FROM messages WHERE contact_id = ?", contactId)
            data.exec("DELETE FROM pending_acks WHERE contact_id = ?", contactId)
            data.exec("DELETE FROM contacts WHERE id = ?", contactId)
        }
        tagsByContact.remove(contactId)?.forEach { tagIndex.remove(it) }
        changed()
    }

    /** Closes and destroys both databases and their keys ([Store.destroy]). The app must exit after. */
    suspend fun wipe() = io {
        // Every step runs even if one fails; the first failure is thrown at the end.
        var failure: Throwable? = null
        fun step(block: () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
                if (failure == null) failure = e
            }
        }
        step { vault?.close() }
        vault = null
        step { vaultKey?.fill(0) }
        step { outer.close() }
        step { Store.destroy(context) }
        failure?.let { throw it }
    }

    // Test data (debug builds only) ---------------------------------------------------------------

    /**
     * Adds [count] made-up contacts with long conversations, to try scrolling. Each comes from a real
     * handshake with a throwaway key made on this phone, so it behaves like any contact; nothing
     * leaves the phone. Refused outside debug builds.
     */
    suspend fun addTestConversations(count: Int = 25, messagesEach: Int = 40) = io {
        check(BuildConfig.DEBUG) { "test data only in debug builds" }
        val (me, myName) = identity()
        val lines = listOf(
            "Είσαι καλά;", "Ναι, όλα καλά εδώ.", "Πού είσαι τώρα;", "Στην πλατεία, κοντά στο σχολείο.",
            "Έχει νερό στο δημαρχείο από τις 5.", "Το φαρμακείο στη γωνία είναι ανοιχτό.",
            "Θα έρθω σε μισή ώρα.", "Πες στη μητέρα σου ότι είμαστε καλά.", "Χρειάζεστε κάτι;",
            "Μπαταρίες και ψωμί, αν βρεις.", "Ο δρόμος προς το λιμάνι είναι κλειστός.",
            "Εντάξει, πάω από την άλλη μεριά.", "Σε περιμένω στο σπίτι.", "Φτάσαμε!",
            "Are you safe?", "Yes, all fine here. See you at 8.",
        )
        val now = System.currentTimeMillis()
        repeat(count) { i ->
            val other = Crypto.ed25519KeyPair()
            val invite = Handshake.invite(other, "Δοκιμή ${i + 1}", today())
            val (_, est) = Handshake.reply(me, myName, invite.invite.bytes, today())
            val id = contactId(est.peerIdentity)
            data.tx {
                saveContact(id, est, confirmed = true)
                // Spread over the last few days, so the chat shows its day lines (today, yesterday...).
                val step = 9_000_000L
                var at = now - messagesEach * (step + 7 * 60_000L) - i * 600_000L
                repeat(messagesEach) { j ->
                    at += step + 60_000L * (1 + j % 7)
                    val outgoing = j % 3 == 1
                    // Some received messages took long to come: hours, or since an earlier day.
                    val late = when {
                        outgoing -> 0L
                        j % 9 == 0 -> 30 * 3_600_000L
                        j % 6 == 0 -> 3 * 3_600_000L
                        j % 4 == 0 -> 20 * 60_000L
                        else -> 0L
                    }
                    // And some receipts for own messages came back late.
                    val deliveredAt = when {
                        !outgoing -> null
                        j % 5 == 1 -> at + 26 * 3_600_000L
                        j % 2 == 1 -> at + 2 * 3_600_000L
                        else -> at + 30_000L
                    }?.coerceAtMost(now)
                    data.exec(
                        "INSERT INTO messages (id, contact_id, outgoing, text, sent_at, at, delivered, read, delivered_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)",
                        Crypto.random(Payload.ID_SIZE), id, if (outgoing) 1 else 0, lines[(i + j) % lines.size], at - late, at, if (outgoing) 1 else 0, deliveredAt,
                    )
                }
                data.exec("UPDATE contacts SET last_at = ? WHERE id = ?", at, id)
            }
            reindex(id)
        }
        changed()
    }

    // PIN and vault -------------------------------------------------------------------------------
    // With a PIN, identity, contacts, sessions and messages move into the vault. Its database key is
    // stored only encrypted by a key made from the PIN (scrypt) and a hardware secret that opens
    // only while the phone is unlocked ([VaultKey]). A wrong PIN does not open it: there is nothing
    // else to compare against.

    sealed class PinCheck {
        object Ok : PinCheck()
        object Wrong : PinCheck()
        /** Too many wrong tries: wait this long before the next one. */
        class Wait(val millis: Long) : PinCheck()
        /** The duress PIN, or the tenth wrong try with wiping on: delete everything now. */
        object Wipe : PinCheck()
        /** The phone's own screen is locked, so the hardware secret cannot be used. */
        object PhoneLocked : PinCheck()
        /** The hardware key is gone for good (reset, new screen lock, restored files): the vault cannot open again. */
        object KeyLost : PinCheck()
    }

    init {
        vaultMode = setting(VAULT_KEY) != null || setting(OLD_PIN) != null
    }

    /** True while a PIN is set and has not been entered: identity and messages are out of reach. */
    val isLocked: Boolean get() = vaultMode && vault == null

    suspend fun hasPin(): Boolean = io { vaultMode }

    /**
     * Sets the PIN, changes it, or removes it with null. Setting the first PIN moves identity,
     * contacts and messages into a new vault; removing it moves them back. Needs the vault open.
     * Refused (false) if [pin] is the duress PIN, which would then open nothing but delete all.
     */
    suspend fun setPin(pin: String?): Boolean = io {
        if (isLocked) throw LockedException()
        when {
            pin != null && vault == null && !vaultMode -> createVault(pin)
            pin != null -> {
                if (isDuressPin(pin)) return@io false
                rewrapVault(pin)
            }
            vault != null -> removeVault()
            // No PIN and none to remove: nothing changed, so the wrong-PIN counters stay.
            else -> return@io true
        }
        outer.exec("DELETE FROM settings WHERE k IN (?, ?, ?)", PIN_FAILURES, PIN_WAIT, OLD_PIN_WAIT)
        changed()
        true
    }

    private fun createVault(pin: String) {
        val (hw, hwWrapped) = VaultKey.create()
        val salt = Crypto.random(16)
        val key = Crypto.random(32)
        Store.deleteVaultFile(context)
        val v = Store.vault(context, key.copyOf())
        v.tx { Store.VAULT_TABLES.forEach { v.copyTable(it, outer) } }
        // One transaction: the wrapped key appears exactly when the plain rows are gone.
        outer.tx {
            putSetting(VAULT_SALT, salt)
            putSetting(VAULT_HW, hwWrapped)
            putSetting(VAULT_KEY, wrapVaultKey(key, pin, salt, hw))
            // Present from the start, the same size whether or not they are ever used: a copy of
            // the phone does not show whether there is a duress PIN, or what the traps are set to.
            putSetting(DURESS, Crypto.random(DURESS_SIZE))
            putSetting(TRAPS, sealTraps(0, salt, hw))
            outer.exec("DELETE FROM settings WHERE k IN (?, ?)", OLD_PIN, WIPE_AFTER)
            Store.VAULT_TABLES.forEach { outer.exec("DELETE FROM $it") }
        }
        hw.fill(0)
        vault = v
        vaultKey = key
        vaultMode = true
    }

    /** The salt stays for the vault's life, so the duress marker made with it stays valid. */
    private fun rewrapVault(pin: String) {
        val key = vaultKey ?: throw LockedException()
        val hw = VaultKey.unwrap(setting(VAULT_HW)!!)
        putSetting(VAULT_KEY, wrapVaultKey(key, pin, setting(VAULT_SALT)!!, hw))
        hw.fill(0)
    }

    private fun removeVault() {
        val v = vault ?: throw LockedException()
        outer.tx {
            Store.VAULT_TABLES.forEach { outer.exec("DELETE FROM $it"); outer.copyTable(it, v) }
            outer.exec(
                "DELETE FROM settings WHERE k IN (?, ?, ?, ?, ?, ?)",
                VAULT_SALT, VAULT_HW, VAULT_KEY, DURESS, TRAPS, WIPE_AFTER,
            )
        }
        v.close()
        vault = null
        vaultKey?.fill(0)
        vaultKey = null
        vaultMode = false
        Store.deleteVaultFile(context)
        VaultKey.destroy()
    }

    /** The PIN stretched once, with the hardware secret: the vault key's key and the duress key. */
    private class PinKeys(val vault: ByteArray, val duress: ByteArray) {
        fun clear() {
            vault.fill(0)
            duress.fill(0)
        }
    }

    private fun pinKeys(pin: String, salt: ByteArray, hw: ByteArray): PinKeys {
        val pinBytes = pin.toByteArray()
        val stretched = Crypto.scrypt(pinBytes, salt)
        val material = stretched + hw
        return PinKeys(
            Crypto.hkdf(salt, material, "Pyrsos vault key", 32),
            Crypto.hkdf(salt, material, "Pyrsos duress key", 32),
        ).also {
            pinBytes.fill(0)
            stretched.fill(0)
            material.fill(0)
        }
    }

    private fun wrapVaultKey(key: ByteArray, pin: String, salt: ByteArray, hw: ByteArray): ByteArray {
        val keys = pinKeys(pin, salt, hw)
        val nonce = Crypto.random(Crypto.NONCE)
        return (nonce + Crypto.seal(keys.vault, nonce, key, VAULT_AAD)).also { keys.clear() }
    }

    /** The vault's key if [keys] open it, else null. */
    private fun openVaultKey(keys: PinKeys): ByteArray? {
        val wrapped = setting(VAULT_KEY) ?: return null
        return try {
            Crypto.open(keys.vault, wrapped.copyOf(Crypto.NONCE), wrapped.copyOfRange(Crypto.NONCE, wrapped.size), VAULT_AAD)
        } catch (_: CryptoException) {
            null
        }
    }

    /**
     * Whether [keys] open the duress marker. The marker is sealed with a key from the PIN and the
     * hardware secret, so a copy of the phone cannot be searched for the duress PIN, nor even
     * tell a marker from the random bytes that stand in for one when there is none.
     */
    private fun isDuress(pin: String, keys: PinKeys): Boolean {
        val stored = setting(DURESS) ?: return false
        if (stored.size == OLD_DURESS_SIZE) {
            // Made before the marker: salt and scrypt hash. Replaced when a duress PIN is set again.
            return constantTimeEquals(Crypto.scrypt(pin.toByteArray(), stored.copyOf(16)), stored.copyOfRange(16, stored.size))
        }
        return try {
            Crypto.open(keys.duress, stored.copyOf(Crypto.NONCE), stored.copyOfRange(Crypto.NONCE, stored.size), DURESS_AAD)
            true
        } catch (_: CryptoException) {
            false
        }
    }

    private fun isDuressPin(pin: String): Boolean {
        val hw = VaultKey.unwrap(setting(VAULT_HW)!!)
        val keys = pinKeys(pin, setting(VAULT_SALT)!!, hw)
        hw.fill(0)
        return isDuress(pin, keys).also { keys.clear() }
    }

    // The traps (wipe after ten wrong PINs, whether a duress PIN is set) are sealed with a key from
    // the hardware secret: one who copies the phone cannot read them and plan around them.

    private fun sealTraps(flags: Int, salt: ByteArray, hw: ByteArray): ByteArray {
        val key = Crypto.hkdf(salt, hw, "Pyrsos traps", 32)
        val nonce = Crypto.random(Crypto.NONCE)
        return (nonce + Crypto.seal(key, nonce, byteArrayOf(flags.toByte()), TRAPS_AAD)).also { key.fill(0) }
    }

    /** The trap flags, or 0 if there are none to read (no PIN, or the phone's screen is locked). */
    private fun traps(hw: ByteArray? = null): Int {
        val sealed = setting(TRAPS) ?: return if (setting(WIPE_AFTER) != null) TRAP_WIPE else 0
        val secret = hw ?: try {
            VaultKey.unwrap(setting(VAULT_HW)!!)
        } catch (_: Exception) {
            return 0
        }
        val key = Crypto.hkdf(setting(VAULT_SALT)!!, secret, "Pyrsos traps", 32)
        if (hw == null) secret.fill(0)
        return try {
            Crypto.open(key, sealed.copyOf(Crypto.NONCE), sealed.copyOfRange(Crypto.NONCE, sealed.size), TRAPS_AAD)[0].toInt()
        } catch (_: CryptoException) {
            0
        } finally {
            key.fill(0)
        }
    }

    private fun setTrap(flag: Int, on: Boolean) {
        val hw = VaultKey.unwrap(setting(VAULT_HW)!!)
        val flags = if (on) traps(hw) or flag else traps(hw) and flag.inv()
        outer.tx {
            putSetting(TRAPS, sealTraps(flags, setting(VAULT_SALT)!!, hw))
            outer.exec("DELETE FROM settings WHERE k = ?", WIPE_AFTER)
        }
        hw.fill(0)
    }

    /** Opens the vault with [pin], then opens whatever arrived while it was locked. */
    suspend fun unlock(pin: String): PinCheck = io {
        setting(OLD_PIN)?.let { return@io unlockOld(pin, it) }
        val hw = try {
            VaultKey.unwrap(setting(VAULT_HW)!!)
        } catch (e: VaultKey.LostException) {
            Log.w(TAG, "vault hardware key lost: ${e.message}")
            return@io PinCheck.KeyLost
        } catch (e: Exception) {
            Log.w(TAG, "vault hardware secret unavailable: ${e.javaClass.simpleName}")
            return@io PinCheck.PhoneLocked
        }
        val keys = pinKeys(pin, setting(VAULT_SALT)!!, hw)
        try {
            if (isDuress(pin, keys)) return@io PinCheck.Wipe
            val wait = waitLeft()
            if (wait > 0) return@io PinCheck.Wait(wait)
            val opened = openVaultKey(keys) ?: return@io wrongPin(traps(hw))
            // Vaults made before the traps were sealed: seal them now.
            if (setting(TRAPS) == null) {
                outer.tx {
                    putSetting(TRAPS, sealTraps(traps(hw), setting(VAULT_SALT)!!, hw))
                    if (setting(DURESS) == null) putSetting(DURESS, Crypto.random(DURESS_SIZE))
                    outer.exec("DELETE FROM settings WHERE k = ?", WIPE_AFTER)
                }
            }
            opened(opened)
        } finally {
            keys.clear()
            hw.fill(0)
        }
    }

    /** A PIN from before the vault existed: checked the old way, then the vault is built with it. */
    private fun unlockOld(pin: String, old: ByteArray): PinCheck {
        val wait = waitLeft()
        if (wait > 0) return PinCheck.Wait(wait)
        if (!constantTimeEquals(Crypto.scrypt(pin.toByteArray(), old.copyOf(16)), old.copyOfRange(16, old.size))) return wrongPin(traps())
        vaultMode = false
        createVault(pin)
        return opened(vaultKey!!)
    }

    private fun opened(key: ByteArray): PinCheck {
        outer.exec("DELETE FROM settings WHERE k IN (?, ?, ?)", PIN_FAILURES, PIN_WAIT, OLD_PIN_WAIT)
        if (vault == null) {
            vault = Store.vault(context, key.copyOf())
            vaultKey = key
        }
        indexDay = -1
        openWhatArrived()
        deleteOldMessages()
        changed()
        return PinCheck.Ok
    }

    private fun wrongPin(traps: Int): PinCheck {
        val failures = (setting(PIN_FAILURES)?.decodeToString()?.toIntOrNull() ?: 0) + 1
        putSetting(PIN_FAILURES, failures.toString().encodeToByteArray())
        if (traps and TRAP_WIPE != 0 && failures >= WIPE_AFTER_TRIES) return PinCheck.Wipe
        if (failures >= PIN_FREE_TRIES) {
            // 30 s after the fifth wrong try, doubling each time, at most an hour.
            val wait = minOf(PIN_FIRST_WAIT_MS shl minOf(failures - PIN_FREE_TRIES, 7), PIN_MAX_WAIT_MS)
            putSetting(PIN_WAIT, "${bootCount()}:${SystemClock.elapsedRealtime() + wait}:$wait".encodeToByteArray())
            return PinCheck.Wait(wait)
        }
        return PinCheck.Wrong
    }

    /**
     * What is left of the wait after wrong tries. Counted on the clock since the phone started,
     * which setting the date forward does not move; after a restart the whole wait begins again.
     */
    private fun waitLeft(): Long {
        val parts = setting(PIN_WAIT)?.decodeToString()?.split(':') ?: return 0
        val nums = parts.mapNotNull { it.toLongOrNull() }
        val now = SystemClock.elapsedRealtime()
        if (nums.size != 3 || parts.size != 3) {
            // Unreadable: start a fresh wait rather than none.
            putSetting(PIN_WAIT, "${bootCount()}:${now + PIN_FIRST_WAIT_MS}:$PIN_FIRST_WAIT_MS".encodeToByteArray())
            return PIN_FIRST_WAIT_MS
        }
        val (boot, until, length) = nums
        if (boot != bootCount().toLong()) {
            putSetting(PIN_WAIT, "${bootCount()}:${now + length}:$length".encodeToByteArray())
            return length
        }
        return maxOf(0L, until - now)
    }

    private fun bootCount(): Int = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)

    /** Closes the vault (when Pyrsos leaves the screen). Envelopes keep being carried meanwhile. */
    suspend fun lock() = io {
        if (!vaultMode || vault == null) return@io
        vault?.close()
        vault = null
        vaultKey?.fill(0)
        vaultKey = null
        tagIndex.clear()
        tagsByContact.clear()
        indexDay = -1
    }

    /**
     * A second PIN that deletes everything when entered on the lock screen; null removes it.
     * Refused (false) if it is the real PIN, which would make the real PIN delete everything.
     */
    suspend fun setDuressPin(pin: String?): Boolean = io {
        if (pin == null) {
            putSetting(DURESS, Crypto.random(DURESS_SIZE))
            setTrap(TRAP_DURESS, false)
            return@io true
        }
        val hw = VaultKey.unwrap(setting(VAULT_HW)!!)
        val keys = pinKeys(pin, setting(VAULT_SALT)!!, hw)
        hw.fill(0)
        try {
            openVaultKey(keys)?.let {
                it.fill(0)
                return@io false
            }
            val nonce = Crypto.random(Crypto.NONCE)
            putSetting(DURESS, nonce + Crypto.seal(keys.duress, nonce, ByteArray(DURESS_MARKER), DURESS_AAD))
        } finally {
            keys.clear()
        }
        setTrap(TRAP_DURESS, true)
        true
    }

    /** Needs the phone's screen unlocked, as the settings screen is. */
    suspend fun hasDuressPin(): Boolean = io {
        traps() and TRAP_DURESS != 0 || setting(DURESS)?.size == OLD_DURESS_SIZE
    }

    /** Whether the tenth wrong PIN in a row deletes everything. */
    suspend fun setWipeAfterTen(on: Boolean) = io { setTrap(TRAP_WIPE, on) }

    suspend fun wipeAfterTen(): Boolean = io { traps() and TRAP_WIPE != 0 }

    /** Messages older than this many days are deleted from this phone; 0 keeps them. */
    suspend fun setAutoDeleteDays(days: Int) = io {
        if (days > 0) putSetting(AUTO_DELETE, days.toString().encodeToByteArray()) else outer.exec("DELETE FROM settings WHERE k = ?", AUTO_DELETE)
        if (!isLocked) deleteOldMessages()
        changed()
    }

    suspend fun autoDeleteDays(): Int = io { setting(AUTO_DELETE)?.decodeToString()?.toIntOrNull() ?: 0 }

    /** Deletes this phone's messages older than the chosen age. Only while the data is open. */
    private fun deleteOldMessages(): Boolean {
        val days = setting(AUTO_DELETE)?.decodeToString()?.toIntOrNull() ?: return false
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        if (data.query("SELECT 1 FROM messages WHERE at < ? LIMIT 1", cutoff) { 1 }.isEmpty()) return false
        data.exec("DELETE FROM messages WHERE at < ?", cutoff)
        return true
    }

    private fun setting(k: String): ByteArray? =
        outer.query("SELECT v FROM settings WHERE k = ?", k) { it.getBlob(0) }.firstOrNull()

    private fun putSetting(k: String, v: ByteArray) = outer.exec("INSERT OR REPLACE INTO settings (k, v) VALUES (?, ?)", k, v)

    // Adding a contact ---------------------------------------------------------------------------

    /** A fresh invitation, as the text of a QR code. Replaces any earlier one. */
    suspend fun newInvite(): String = io {
        val (identity, name) = identity()
        val pending = Handshake.invite(identity, name, today())
        data.tx {
            data.exec("DELETE FROM pending_invites")
            data.exec(
                "INSERT INTO pending_invites (id, data, created_at) VALUES (?, ?, ?)",
                pending.invite.id.toHex(), pending.encode(), System.currentTimeMillis(),
            )
        }
        Base45.encode(pending.invite.bytes)
    }

    suspend fun cancelInvite() = io { data.exec("DELETE FROM pending_invites") }

    /** [showing]: this phone is showing its own invitation while it scans. */
    suspend fun scanned(code: String, showing: Boolean = false): ScanResult = io {
        val bytes = try {
            Base45.decode(code.trim())
        } catch (_: MalformedException) {
            return@io ScanResult.Refused(ScanResult.Reason.NOT_PYRSOS)
        }
        when (Handshake.kindOf(bytes)) {
            Handshake.Kind.NOT_PYRSOS -> ScanResult.Refused(ScanResult.Reason.NOT_PYRSOS)
            Handshake.Kind.UNSUPPORTED_VERSION -> ScanResult.Refused(ScanResult.Reason.OTHER_VERSION)
            Handshake.Kind.INVITE -> answerInvite(bytes, showing)
            Handshake.Kind.REPLY -> completeInvite(bytes)
        }
    }

    private fun answerInvite(invite: ByteArray, showing: Boolean): ScanResult {
        val (identity, name) = identity()
        val (reply, est) = try {
            Handshake.reply(identity, name, invite, today())
        } catch (_: ForgedException) {
            return ScanResult.Refused(ScanResult.Reason.FORGED)
        } catch (_: StaleInviteException) {
            return ScanResult.Refused(ScanResult.Reason.STALE_INVITE)
        } catch (_: CryptoException) {
            return ScanResult.Refused(ScanResult.Reason.OWN_CODE)
        } catch (_: MalformedException) {
            return ScanResult.Refused(ScanResult.Reason.NOT_PYRSOS)
        }
        val id = contactId(est.peerIdentity)
        if (isConfirmedContact(id)) return ScanResult.Refused(ScanResult.Reason.ALREADY_CONTACT)
        // Both phones showing an invitation and scanning each other's would each answer, drop their
        // own invitation and be left with two halves of different handshakes that never finish.
        // The same rule on both phones picks one to answer; the other keeps its invitation.
        if (showing && hasPendingInvite() && compareKeys(identity.public, est.peerIdentity) > 0) {
            return ScanResult.Refused(ScanResult.Reason.BOTH_SHOWING)
        }
        data.tx {
            // They showed their code, so this phone answers instead of inviting.
            data.exec("DELETE FROM pending_invites")
            saveContact(id, est, confirmed = false)
        }
        reindex(id)
        changed()
        return ScanResult.ShowReply(Base45.encode(reply), id, cleanName(est.peerName), IdentityCode.of(est.peerIdentity))
    }

    private fun completeInvite(reply: ByteArray): ScanResult {
        val pending = data.query("SELECT data, created_at FROM pending_invites") {
            Handshake.PendingInvite.decode(it.getBlob(0)) to it.getLong(1)
        }.firstOrNull() ?: return ScanResult.Refused(ScanResult.Reason.REPLY_WITHOUT_INVITE)
        if (System.currentTimeMillis() - pending.second > INVITE_LIFETIME_MS) {
            data.exec("DELETE FROM pending_invites")
            return ScanResult.Refused(ScanResult.Reason.REPLY_WITHOUT_INVITE)
        }
        val est = try {
            Handshake.complete(pending.first, reply)
        } catch (_: ForgedException) {
            return ScanResult.Refused(ScanResult.Reason.FORGED)
        } catch (_: CryptoException) {
            return ScanResult.Refused(ScanResult.Reason.REPLY_DOES_NOT_MATCH)
        } catch (_: MalformedException) {
            return ScanResult.Refused(ScanResult.Reason.NOT_PYRSOS)
        }
        val id = contactId(est.peerIdentity)
        if (isConfirmedContact(id)) return ScanResult.Refused(ScanResult.Reason.ALREADY_CONTACT)
        sealing { out ->
            data.exec("DELETE FROM pending_invites")
            saveContact(id, est, confirmed = true)
            // Tell them the handshake finished; until this arrives their side shows it unfinished.
            seal(id, Payload.Hello, out)
        }
        reindex(id)
        changed()
        // They are standing next to this phone: meet them again now, not after the pause.
        _outgoing.tryEmit(Unit)
        return ScanResult.Completed(id, cleanName(est.peerName))
    }

    private fun hasPendingInvite(): Boolean =
        data.query("SELECT 1 FROM pending_invites WHERE created_at >= ?", System.currentTimeMillis() - INVITE_LIFETIME_MS) { true }.isNotEmpty()

    private fun compareKeys(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /**
     * A working conversation is never replaced by scanning again: an old code shown later would
     * otherwise reset it. An unfinished one may be, so a handshake that went wrong can be redone.
     */
    private fun isConfirmedContact(id: String): Boolean =
        data.query("SELECT 1 FROM contacts WHERE id = ? AND confirmed = 1", id) { true }.isNotEmpty()

    private fun saveContact(id: String, est: Handshake.Established, confirmed: Boolean) {
        val now = System.currentTimeMillis()
        data.exec(
            """INSERT INTO contacts (id, identity, name, session, confirmed, created_at, last_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET name = excluded.name, session = excluded.session,
                 confirmed = excluded.confirmed, last_at = excluded.last_at""",
            id, est.peerIdentity, cleanName(est.peerName), est.session.encode(), if (confirmed) 1 else 0, now, now,
        )
    }

    private fun contactId(identity: ByteArray) = Crypto.sha256(identity).copyOf(12).toHex()

    // Sending ------------------------------------------------------------------------------------

    suspend fun send(contactId: String, text: String) = io {
        if (isLocked) throw LockedException()
        val clean = text.trim()
        require(clean.isNotEmpty() && clean.length <= Payload.MAX_TEXT_CHARS)
        require(clean.toByteArray(Charsets.UTF_8).size <= Payload.MAX_TEXT_BYTES)
        val id = Crypto.random(Payload.ID_SIZE)
        val now = System.currentTimeMillis()
        sealing { out ->
            val envelopeId = seal(contactId, Payload.Text(id, now, clean), out)
            data.exec(
                "INSERT INTO messages (id, contact_id, outgoing, text, sent_at, at, envelope_id) VALUES (?, ?, 1, ?, ?, ?, ?)",
                id, contactId, clean, now, now, envelopeId,
            )
            data.exec("UPDATE contacts SET last_at = ? WHERE id = ?", now, contactId)
        }
        changed()
        _outgoing.tryEmit(Unit)
    }

    /**
     * Runs [block] in a transaction of the contacts' database. The envelopes it seals are stored
     * for carrying only after that commits: the session moves on first, so if storing them then
     * failed the message would be lost (and resent), never sealed twice with the same key. With a
     * PIN the envelopes live in another database, which could not be rolled back together.
     */
    private fun <T> sealing(block: (MutableList<Envelope>) -> T): T {
        val sealed = ArrayList<Envelope>()
        val result = data.tx { block(sealed) }
        if (sealed.isNotEmpty()) {
            outer.tx {
                for (e in sealed) {
                    outer.exec(
                        "INSERT OR IGNORE INTO envelopes (id, data, expiry_day, received_at) VALUES (?, ?, ?, ?)",
                        e.id, e.bytes, e.expiryDay, receivedAt(),
                    )
                }
            }
        }
        return result
    }

    /** Seals [payload] for the contact inside [sealing]; adds the envelope to [out]. */
    private fun seal(contactId: String, payload: Payload, out: MutableList<Envelope>): ByteArray {
        val sealed = loadSession(contactId).seal(payload, today())
        data.exec("UPDATE contacts SET session = ? WHERE id = ?", sealed.next.encode(), contactId)
        out += sealed.envelope
        return sealed.envelope.id
    }

    /** Only the day an envelope arrived is kept: the minute would tell when two phones met. */
    private fun receivedAt(): Long = today() * DAY_MS

    private fun loadSession(contactId: String): Session =
        Session.decode(data.query("SELECT session FROM contacts WHERE id = ?", contactId) { it.getBlob(0) }.single())

    // Carrying envelopes -------------------------------------------------------------------------

    /** Ids of every envelope this phone carries and that has not expired. */
    /**
     * What this phone offers at a meeting: at most [MAX_OFFER] envelopes in random order, a
     * different part each time, so the list cannot be used to recognise the phone from one meeting
     * to the next. Its own undelivered messages are always among them.
     */
    suspend fun inventory(): List<ByteArray> = io {
        val all = outer.query("SELECT id FROM envelopes WHERE expiry_day >= ?", today()) { it.getBlob(0) }
        val own = undeliveredEnvelopes()
        val (mine, others) = all.partition { it.toHex() in own }
        (mine + others.shuffled(random)).take(MAX_OFFER).shuffled(random)
    }

    suspend fun envelopes(ids: List<ByteArray>): List<Pair<ByteArray, ByteArray>> = io {
        ids.mapNotNull { id ->
            outer.query("SELECT data FROM envelopes WHERE id = ? AND expiry_day >= ?", id, today()) { id to it.getBlob(0) }
                .firstOrNull()
        }
    }

    suspend fun markHanded(ids: List<ByteArray>) = io {
        if (ids.isEmpty()) return@io
        // Kept with the message, not the envelope. While locked the count is lost: it is only shown.
        if (isLocked) return@io
        data.tx { ids.forEach { data.exec("UPDATE messages SET handed = handed + 1 WHERE envelope_id = ? AND outgoing = 1", it) } }
        changed()
    }

    class Ingested(val stored: Int, val opened: Int, val refused: Int)

    /**
     * Takes envelopes from another phone. Every envelope is stored and carried on the same way,
     * including the ones this phone opens: keeping only other people's would show anyone who
     * compares inventories which envelopes were for this phone.
     */
    suspend fun ingest(raw: List<ByteArray>): Ingested = io {
        val today = today()
        val locked = isLocked
        if (!locked) ensureIndex(today)
        var stored = 0
        var opened = 0
        var refused = 0
        // One transaction for storing the batch: a write per envelope would keep the disk busy for long.
        val fresh = ArrayList<Envelope>()
        outer.tx {
            for (bytes in raw) {
                val env = try {
                    Envelope.parse(bytes)
                } catch (_: MalformedException) {
                    refused++
                    continue
                }
                if (env.expiryDay < today || env.expiryDay > today + MAX_EXPIRY_AHEAD) {
                    refused++
                    continue
                }
                val known = outer.query("SELECT 1 FROM envelopes WHERE id = ?", env.id) { 1 }.isNotEmpty()
                if (known) continue
                // Locked: carry it now, try it against the contacts when the PIN is entered.
                outer.exec(
                    "INSERT INTO envelopes (id, data, expiry_day, received_at, checked) VALUES (?, ?, ?, ?, ?)",
                    env.id, env.bytes, env.expiryDay, receivedAt(), if (locked) 0 else 1,
                )
                stored++
                fresh += env
            }
        }
        // Each is opened on its own: one that fails for an unexpected reason is tried again at the
        // next unlock and does not undo the others.
        if (!locked) {
            for (env in fresh) {
                try {
                    if (tryOpen(env, today)) opened++
                } catch (e: Exception) {
                    Log.w(TAG, "could not open an envelope: ${e.javaClass.simpleName}")
                    outer.exec("UPDATE envelopes SET checked = 0 WHERE id = ?", env.id)
                }
            }
        }
        orderArrivals()
        evictOverCapacity()
        if (stored > 0) changed()
        Ingested(stored, opened, refused)
    }

    /** Messages opened since the last [orderArrivals]. Used only on the store's own thread. */
    private val arrivals = ArrayList<ByteArray>()

    /**
     * Puts messages that arrived together in the order they were written: one exchange hands its
     * envelopes over in random order. They keep the arrival times they had between them, so a
     * message that took days to come still shows where it arrived, not far up the conversation.
     */
    private fun orderArrivals() {
        if (arrivals.size >= 2) {
            val rows = arrivals.mapNotNull { id ->
                data.query("SELECT contact_id, sent_at, at FROM messages WHERE id = ?", id) {
                    Arrival(id, it.getString(0), it.getLong(1), it.getLong(2))
                }.firstOrNull()
            }
            data.tx {
                for (group in rows.groupBy { it.contactId }.values) {
                    val times = group.map { it.at }.sorted()
                    var last = Long.MIN_VALUE
                    group.sortedBy { it.sentAt }.forEachIndexed { i, a ->
                        last = maxOf(times[i], last + 1)
                        data.exec("UPDATE messages SET at = ? WHERE id = ?", last, a.id)
                    }
                }
            }
        }
        arrivals.clear()
    }

    private class Arrival(val id: ByteArray, val contactId: String, val sentAt: Long, val at: Long)

    /** Opens [env] if its tag is one a contact uses today. */
    private fun tryOpen(env: Envelope, today: Int): Boolean {
        val match = tagIndex[env.tag.toHex()] ?: return false
        val ok = data.tx { open(match.first, match.second, env, today) }
        reindex(match.first)
        return ok
    }

    /**
     * Seals the receipts whose time has come. Each received message is acknowledged after its own
     * random delay ([ACK_DELAY_MIN_MS] to [ACK_DELAY_MAX_MS]): a receipt sent at once would show
     * a phone nearby that it just handed over a message to this phone's owner.
     */
    suspend fun sealDueAcks() = io {
        if (isLocked) return@io
        val now = System.currentTimeMillis()
        val due = data.query(
            "SELECT a.contact_id, a.message_id FROM pending_acks a JOIN contacts c ON c.id = a.contact_id WHERE a.due_at <= ?",
            now,
        ) { it.getString(0) to it.getBlob(1) }
        sealing { out ->
            for ((contactId, ids) in due.groupBy({ it.first }, { it.second })) {
                ids.chunked(Payload.MAX_ACKS).forEach { seal(contactId, Payload.Ack(it), out) }
            }
            data.exec("DELETE FROM pending_acks WHERE due_at <= ?", now)
        }
        if (due.isNotEmpty()) _outgoing.tryEmit(Unit)
    }

    private fun ackDelay(): Long = ACK_DELAY_MIN_MS + (random.nextDouble() * (ACK_DELAY_MAX_MS - ACK_DELAY_MIN_MS)).toLong()

    /** After unlocking: tries every envelope that came in while the vault was locked. */
    private fun openWhatArrived() {
        val today = today()
        ensureIndex(today)
        val pending = outer.query("SELECT id, data FROM envelopes WHERE checked = 0 AND expiry_day >= ?", today) { it.getBlob(0) to it.getBlob(1) }
        for ((id, bytes) in pending) {
            try {
                tryOpen(Envelope.parse(bytes), today)
            } catch (_: MalformedException) {
            } catch (e: CryptoException) {
                Log.w(TAG, "could not open an envelope that arrived while locked: ${e.message}")
            }
            outer.exec("UPDATE envelopes SET checked = 1 WHERE id = ?", id)
        }
        orderArrivals()
    }

    /** Opens an envelope whose tag matched. Inside the transaction that stored it. */
    private fun open(contactId: String, slot: TagSlot, env: Envelope, today: Int): Boolean {
        val result = try {
            loadSession(contactId).open(env, slot, today)
        } catch (e: CryptoException) {
            // The tag matched but the envelope did not open: altered on the way, or a forgery. It is
            // still carried like any other; nothing in the session changed.
            Log.w(TAG, "envelope with a known tag did not open: ${e.message}")
            return false
        } catch (e: MalformedException) {
            Log.w(TAG, "envelope opened but its content did not parse: ${e.message}")
            return false
        }
        data.exec("UPDATE contacts SET session = ?, confirmed = 1 WHERE id = ?", result.next.encode(), contactId)
        when (val p = result.payload) {
            is Payload.Text -> {
                val now = System.currentTimeMillis()
                // A renewed copy of a message already here changes nothing but gets its receipt.
                if (data.query("SELECT 1 FROM messages WHERE id = ?", p.id) { 1 }.isEmpty()) {
                    data.exec(
                        "INSERT INTO messages (id, contact_id, outgoing, text, sent_at, at) VALUES (?, ?, 0, ?, ?, ?)",
                        p.id, contactId, p.text, p.sentAt, now,
                    )
                    arrivals += p.id
                }
                data.exec("UPDATE contacts SET last_at = ? WHERE id = ?", now, contactId)
                // Every copy is acknowledged, so a lost receipt is made good by the next copy.
                data.exec(
                    "INSERT OR IGNORE INTO pending_acks (contact_id, message_id, due_at) VALUES (?, ?, ?)",
                    contactId, p.id, now + ackDelay(),
                )
            }
            is Payload.Ack -> p.ids.forEach {
                // The first receipt counts: later copies of it change nothing.
                data.exec(
                    "UPDATE messages SET delivered = 1, delivered_at = COALESCE(delivered_at, ?) WHERE id = ? AND contact_id = ? AND outgoing = 1",
                    System.currentTimeMillis(), it, contactId,
                )
            }
            Payload.Hello -> Unit
        }
        return true
    }

    /**
     * Over capacity, envelopes go at random: a phone flooding junk then pushes out only its share,
     * not the envelopes that expire soonest. Envelopes claiming to last longer than any honest one
     * go first. This phone's undelivered messages stay while it is unlocked; when they go anyway,
     * [resendUndelivered] seals them again.
     */
    private fun evictOverCapacity() {
        val count = outer.query("SELECT COUNT(*) FROM envelopes") { it.getInt(0) }.single()
        if (count <= MAX_ENVELOPES) return
        val keep = undeliveredEnvelopes()
        val candidates = outer.query(
            "SELECT id FROM envelopes ORDER BY expiry_day > ? DESC, RANDOM()",
            today() + Envelope.LIFETIME_DAYS,
        ) { it.getBlob(0) }.filter { it.toHex() !in keep }
        outer.tx { candidates.take(count - MAX_ENVELOPES).forEach { outer.exec("DELETE FROM envelopes WHERE id = ?", it) } }
    }

    /** The envelopes of this phone's own undelivered messages; none while locked. */
    private fun undeliveredEnvelopes(): Set<String> = if (isLocked) emptySet() else data.query(
        "SELECT envelope_id FROM messages WHERE outgoing = 1 AND delivered = 0 AND envelope_id IS NOT NULL",
    ) { it.getBlob(0).toHex() }.toSet()

    /**
     * Gives every own message that is still not delivered a fresh envelope shortly before its
     * current one stops being carried, so it keeps travelling for as long as it takes. Other phones
     * still carry each envelope for [Envelope.LIFETIME_DAYS] only; only the writer's phone renews.
     * The new envelope carries the same message id and send time, so the recipient keeps one copy
     * and acknowledges every copy that reaches it: a lost receipt is made good by the next one.
     * [now] renews all undelivered messages at once (for tests). Returns how many were renewed.
     */
    suspend fun resendUndelivered(now: Boolean = false): Int = io {
        if (isLocked) return@io 0
        val renewBy = if (now) Int.MAX_VALUE else today() + RESEND_DAYS_AHEAD
        val undelivered = data.query(
            """SELECT m.id, m.contact_id, m.text, m.sent_at, m.envelope_id FROM messages m
               JOIN contacts c ON c.id = m.contact_id
               WHERE m.outgoing = 1 AND m.delivered = 0""",
        ) { Triple(Payload.Text(it.getBlob(0), it.getLong(3), it.getString(2)), it.getString(1), it.getBlob(4)) }
        val due = undelivered.filter { (_, _, envelope) ->
            val expiry = envelope?.let { outer.query("SELECT expiry_day FROM envelopes WHERE id = ?", it) { c -> c.getInt(0) }.firstOrNull() }
            expiry == null || expiry <= renewBy
        }
        if (due.isEmpty()) return@io 0
        sealing { out ->
            for ((text, contactId, _) in due) {
                data.exec("UPDATE messages SET envelope_id = ? WHERE id = ?", seal(contactId, text, out), text.id)
            }
        }
        changed()
        _outgoing.tryEmit(Unit)
        due.size
    }

    /** Housekeeping: expired envelopes always; old invitations and messages while the data is open. */
    suspend fun dropExpired() = io {
        outer.exec("DELETE FROM envelopes WHERE expiry_day < ?", today())
        if (isLocked) return@io
        data.exec("DELETE FROM pending_invites WHERE created_at < ?", System.currentTimeMillis() - INVITE_LIFETIME_MS)
        if (deleteOldMessages()) changed()
    }

    // The tag index ------------------------------------------------------------------------------

    private fun ensureIndex(today: Int) {
        if (indexDay == today) return
        tagIndex.clear()
        tagsByContact.clear()
        data.query("SELECT id FROM contacts") { it.getString(0) }.forEach {
            ageSession(it, today)
            indexContact(it, today)
        }
        // Only once every contact is in: an index left half built would miss envelopes all day.
        indexDay = today
    }

    /** Once a day, even with nothing arriving: day keys that left the window are deleted. */
    private fun ageSession(contactId: String, today: Int) {
        try {
            data.exec("UPDATE contacts SET session = ? WHERE id = ?", loadSession(contactId).aged(today).encode(), contactId)
        } catch (e: CryptoException) {
            Log.w(TAG, "could not age a session: ${e.message}")
        } catch (e: MalformedException) {
            Log.w(TAG, "could not age a session: ${e.message}")
        }
    }

    private fun reindex(contactId: String) {
        if (indexDay != today()) return ensureIndex(today())
        tagsByContact.remove(contactId)?.forEach { tagIndex.remove(it) }
        indexContact(contactId, indexDay)
    }

    /** One contact whose session cannot be read must not stop the others from being found. */
    private fun indexContact(contactId: String, today: Int) {
        val slots = try {
            loadSession(contactId).expected(today)
        } catch (e: CryptoException) {
            Log.w(TAG, "could not index a contact: ${e.message}")
            emptyList()
        } catch (e: MalformedException) {
            Log.w(TAG, "could not index a contact: ${e.message}")
            emptyList()
        }
        tagsByContact[contactId] = slots.map { slot -> slot.tag.toHex().also { tagIndex[it] = contactId to slot } }
    }

    companion object {
        /** Renew an undelivered message's envelope this many days before it would stop being carried. */
        private const val RESEND_DAYS_AHEAD = 1
        /** The PIN hash of the version before the vault: converted on the first unlock. */
        private const val OLD_PIN = "pin"
        private const val VAULT_SALT = "vault_salt"
        private const val VAULT_HW = "vault_hw"
        private const val VAULT_KEY = "vault_key"
        private val VAULT_AAD = "Pyrsos vault".toByteArray()
        private const val DURESS = "duress_pin"
        private val DURESS_AAD = "Pyrsos duress".toByteArray()
        private const val DURESS_MARKER = 16
        private const val DURESS_SIZE = Crypto.NONCE + DURESS_MARKER + 16
        private const val OLD_DURESS_SIZE = 48
        private const val TRAPS = "traps"
        private val TRAPS_AAD = "Pyrsos traps".toByteArray()
        private const val TRAP_WIPE = 1
        private const val TRAP_DURESS = 2
        /** Before the traps were sealed: the wipe flag in the clear. Read once, then sealed. */
        private const val WIPE_AFTER = "wipe_after_ten"
        private const val WIPE_AFTER_TRIES = 10
        private const val AUTO_DELETE = "auto_delete_days"
        private const val PIN_FAILURES = "pin_failures"
        private const val PIN_WAIT = "pin_wait"
        private const val OLD_PIN_WAIT = "pin_wait_until"
        private const val PIN_FREE_TRIES = 5
        // Debug builds wait a tenth as long, so the ten-tries wipe can be tested in minutes.
        private val PIN_FIRST_WAIT_MS = if (BuildConfig.DEBUG) 3_000L else 30_000L
        private val PIN_MAX_WAIT_MS = if (BuildConfig.DEBUG) 6 * 60 * 1000L else 60 * 60 * 1000L

        private const val TAG = "Pyrsos"
        const val INVITE_LIFETIME_MS = 30 * 60 * 1000L

        /** About 27 MB of envelopes. */
        const val MAX_ENVELOPES = 20_000

        private const val DAY_MS = 86_400_000L

        /** A receipt waits this long at least, and at most [ACK_DELAY_MAX_MS]. */
        private const val ACK_DELAY_MIN_MS = 6_000L
        private const val ACK_DELAY_MAX_MS = 60_000L

        /** The most envelope ids offered at one meeting. */
        const val MAX_OFFER = 2_000

        /** An envelope expiring further ahead than a sender's clock could explain is not carried. */
        const val MAX_EXPIRY_AHEAD = Envelope.LIFETIME_DAYS + TagReceiver.FUTURE_DAYS + 1

        /** The own name, cleaned and cut to what an invitation can carry, never mid-character. */
        fun fitName(name: String): String {
            val sb = StringBuilder()
            var bytes = 0
            var i = 0
            val clean = cleanName(name)
            while (i < clean.length) {
                val cp = clean.codePointAt(i)
                val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (bytes + n > Handshake.MAX_NAME_BYTES) break
                sb.appendCodePoint(cp)
                bytes += n
                i += Character.charCount(cp)
            }
            return sb.toString()
        }

        /** Names come from other phones: drop control and direction-override characters. */
        fun cleanName(name: String): String =
            name.filter { !Handshake.isHiddenNameChar(it) }
                .trim()
                .ifEmpty { "?" }
    }
}
