// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.data

import android.content.Context
import android.database.Cursor
import app.pyrsos.BuildConfig
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * An encrypted SQLCipher database. Pyrsos has two:
 *
 * - [outer], always open: the envelopes this phone carries and small settings. Without a PIN it
 *   also holds identity, contacts and messages. Its key is wrapped by a hardware key that works
 *   whether or not the phone is locked, so relaying goes on in the background.
 * - [vault], only with a PIN and only while Pyrsos is unlocked: identity, contacts, sessions and
 *   messages. Its key is wrapped by a key made from the PIN and a hardware secret that opens only
 *   while the phone itself is unlocked ([VaultKey]).
 *
 * secure_delete makes SQLite overwrite what it deletes, so a used message key is gone from the
 * file and not just unlinked: forward secrecy depends on it.
 */
class Store private constructor(file: File, key: ByteArray, migrations: List<List<String>>) {
    val db: SQLiteDatabase

    init {
        System.loadLibrary("sqlcipher")
        val raw = rawKey(key)
        try {
            db = SQLiteDatabase.openDatabase(
                file.path,
                raw,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
                null,
                null,
            )
        } finally {
            raw.fill(0)
            key.fill(0)
        }
        // SQLCipher wipes the memory it frees, so keys and decrypted pages do not linger in it. It
        // also tries to lock that memory against swapping, which some phones refuse with a warning
        // per allocation; only errors are logged, and nothing in release builds.
        db.rawQuery("PRAGMA cipher_log_level = ${if (BuildConfig.DEBUG) "ERROR" else "NONE"}").use { it.moveToFirst() }
        db.rawQuery("PRAGMA cipher_memory_security = ON").use { it.moveToFirst() }
        db.rawQuery("PRAGMA secure_delete = ON").use { it.moveToFirst() }
        migrate(migrations)
    }

    fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    fun <T> query(sql: String, vararg args: Any?, row: (Cursor) -> T): List<T> =
        db.rawQuery(sql, *args).use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out += row(c)
            out
        }

    fun exec(sql: String, vararg args: Any?) = db.execSQL(sql, args)

    fun close() = db.close()

    /**
     * The key as SQLCipher's raw-key literal, x'hex', built straight into bytes that are zeroed
     * after use: a String could not be wiped and would stay in memory until collected.
     */
    private fun rawKey(key: ByteArray): ByteArray {
        val out = ByteArray(key.size * 2 + 3)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        for (i in key.indices) {
            val b = key[i].toInt() and 0xff
            out[2 + 2 * i] = HEX[b ushr 4]
            out[3 + 2 * i] = HEX[b and 0x0f]
        }
        out[out.size - 1] = '\''.code.toByte()
        return out
    }

    /** Brings the schema up to date: step i turns version i into version i + 1. */
    private fun migrate(migrations: List<List<String>>) {
        val version = db.rawQuery("PRAGMA user_version").use { it.moveToFirst(); it.getInt(0) }
        if (version > migrations.size) throw IllegalStateException("database is from a newer Pyrsos ($version)")
        for (v in version until migrations.size) tx {
            migrations[v].forEach { db.execSQL(it) }
            db.execSQL("PRAGMA user_version = ${v + 1}")
        }
    }

    /** Copies every row of [table] from [from] into this database, same columns. Inside a transaction. */
    fun copyTable(table: String, from: Store) {
        from.db.rawQuery("SELECT * FROM $table").use { c ->
            val cols = c.columnNames
            val sql = "INSERT INTO $table (${cols.joinToString()}) VALUES (${cols.joinToString { "?" }})"
            while (c.moveToNext()) {
                val args = Array<Any?>(cols.size) { i ->
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                }
                db.execSQL(sql, args)
            }
        }
    }

    companion object {
        private val HEX = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        private const val FILE = "pyrsos.db"
        private const val VAULT_FILE = "vault.db"

        /** What the vault holds; the outer database has the same tables, used when there is no PIN. */
        val VAULT_TABLES = listOf("identity", "contacts", "pending_invites", "messages", "pending_acks")

        /** Throws [DbKey.LostException] if the key that opens the database is gone for good. */
        fun outer(context: Context): Store {
            val file = File(context.noBackupFilesDir, FILE)
            return Store(file, DbKey.load(context, fresh = file.length() == 0L), OUTER)
        }

        /** Opens (or creates) the vault with [key], which is zeroed after. */
        fun vault(context: Context, key: ByteArray) = Store(File(context.noBackupFilesDir, VAULT_FILE), key, VAULT)

        fun deleteVaultFile(context: Context) = deleteFiles(context, VAULT_FILE)

        private fun deleteFiles(context: Context, name: String) {
            val dir = context.noBackupFilesDir
            listOf(name, "$name-journal", "$name-wal", "$name-shm").forEach { File(dir, it).delete() }
        }

        /**
         * Destroys everything Pyrsos keeps on this phone: both database files and the hardware keys
         * that open them. Without those keys, any copy of the files that flash memory keeps behind
         * is unreadable. Close both databases first.
         */
        fun destroy(context: Context) {
            deleteFiles(context, FILE)
            deleteFiles(context, VAULT_FILE)
            DbKey.destroy(context)
            VaultKey.destroy()
        }

        private val SCHEMA_V1 = listOf(
            """CREATE TABLE identity (
                k INTEGER PRIMARY KEY CHECK (k = 1),
                private BLOB NOT NULL,
                public BLOB NOT NULL,
                name TEXT NOT NULL
            )""",
            """CREATE TABLE contacts (
                id TEXT PRIMARY KEY,
                identity BLOB NOT NULL,
                name TEXT NOT NULL,
                session BLOB NOT NULL,
                confirmed INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                last_at INTEGER NOT NULL
            )""",
            """CREATE TABLE pending_invites (
                id TEXT PRIMARY KEY,
                data BLOB NOT NULL,
                created_at INTEGER NOT NULL
            )""",
            """CREATE TABLE messages (
                id BLOB PRIMARY KEY,
                contact_id TEXT NOT NULL,
                outgoing INTEGER NOT NULL,
                text TEXT NOT NULL,
                sent_at INTEGER NOT NULL,
                at INTEGER NOT NULL,
                delivered INTEGER NOT NULL DEFAULT 0,
                read INTEGER NOT NULL DEFAULT 0,
                envelope_id BLOB
            )""",
            "CREATE INDEX messages_by_contact ON messages(contact_id, at)",
            // own: sealed on this phone. Never evicted before it expires: it may be the only copy.
            // handed: how many phones this phone gave it to; shown only for own envelopes.
            """CREATE TABLE envelopes (
                id BLOB PRIMARY KEY,
                data BLOB NOT NULL,
                expiry_day INTEGER NOT NULL,
                own INTEGER NOT NULL,
                handed INTEGER NOT NULL DEFAULT 0,
                received_at INTEGER NOT NULL
            )""",
            "CREATE INDEX envelopes_by_expiry ON envelopes(own, expiry_day)",
        )

        private val DATA_V2 get() = listOf(
            // Receipts for messages that arrived, each sent after its own random delay.
            """CREATE TABLE pending_acks (
                contact_id TEXT NOT NULL,
                message_id BLOB NOT NULL,
                due_at INTEGER NOT NULL,
                PRIMARY KEY (contact_id, message_id)
            )""",
            // handed: how many phones this phone gave the message's envelope to.
            "ALTER TABLE messages ADD COLUMN handed INTEGER NOT NULL DEFAULT 0",
        )

        private val OUTER = listOf(
            SCHEMA_V1,
            // Small settings: the vault's wrapped key, PIN counters.
            listOf("CREATE TABLE settings (k TEXT PRIMARY KEY, v BLOB NOT NULL)"),
            // checked = 0: arrived while the vault was locked, still to be tried against the contacts.
            listOf("ALTER TABLE envelopes ADD COLUMN checked INTEGER NOT NULL DEFAULT 1"),
            // Nothing in the outer database may tell this phone's own envelopes from the ones it
            // carries for others, nor when to the minute each arrived: it opens without the PIN.
            DATA_V2 + listOf(
                "UPDATE messages SET handed = COALESCE((SELECT e.handed FROM envelopes e WHERE e.id = messages.envelope_id), 0)",
                "UPDATE envelopes SET received_at = received_at / 86400000 * 86400000",
                "DROP INDEX envelopes_by_expiry",
                "ALTER TABLE envelopes DROP COLUMN own",
                "ALTER TABLE envelopes DROP COLUMN handed",
                "CREATE INDEX envelopes_by_expiry ON envelopes(expiry_day)",
            ),
        )

        private val VAULT = listOf(SCHEMA_V1.filterNot { "envelopes" in it }, DATA_V2)
    }
}
