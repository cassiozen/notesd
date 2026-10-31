package notes.core.db

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import notes.core.api.LockKey

/**
 * This device's copy of the locked-notes keyring, so locked notes open offline (§10.1).
 * Every write moves [generation] on, so a fetch that started before a local
 * change (setup, a new password, a reset) can tell it's stale.
 */
class LockKeyStore(private val db: Database) {
    private val writes = AtomicLong(0)

    /** Taken before fetching the server's keyring, for [replaceIfUnchanged]. */
    fun generation(): Long = writes.load()

    suspend fun keys(): List<LockKey> = db.read { sql ->
        sql.query(
            """
            SELECT id, kind, credential_id, salt, iterations, wrapped, created_at FROM lock_keys
            ORDER BY kind != 'password', created_at, id
            """
        ) {
            LockKey(
                it.getText(0),
                it.getText(1),
                it.textOrNull(2),
                it.getText(3),
                it.getLong(4).toInt(),
                it.getText(5),
                it.getLong(6)
            )
        }
    }

    /** Replaces the copy with the server's keyring. */
    suspend fun replace(keys: List<LockKey>) = db.write { sql -> sql.store(keys) }

    /**
     * Replaces the copy with a keyring fetched since [generation], unless this
     * device wrote one in the meantime (it's newer than the fetch). False when skipped.
     */
    suspend fun replaceIfUnchanged(keys: List<LockKey>, generation: Long): Boolean = db.write { sql ->
        if (writes.load() != generation) return@write false
        sql.store(keys)
        true
    }

    // Writes are serialised by the database, so the check above and this bump don't interleave.
    private suspend fun Sql.store(keys: List<LockKey>) {
        writes.incrementAndFetch()
        exec("DELETE FROM lock_keys")
        for (k in keys) {
            exec(
                "INSERT INTO lock_keys (id, kind, credential_id, salt, iterations, wrapped, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                k.id,
                k.kind,
                k.credentialId,
                k.salt,
                k.iterations,
                k.wrapped,
                k.createdAt
            )
        }
    }
}
