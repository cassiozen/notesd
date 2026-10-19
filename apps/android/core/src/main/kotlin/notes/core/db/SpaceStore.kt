package notes.core.db

import notes.core.api.RemoteSpace
import notes.core.text.Uuid7

object Spaces {
    /** The space every install has (§7.7): renamed maybe, never deleted. */
    const val DEFAULT = "default"
    const val DEFAULT_NAME = "Notes"
    const val MAX_NAME = 64

    /** A name as the server stores it: trimmed, inner whitespace collapsed; null when nothing is left. */
    fun normalize(name: String): String? = name.trim().split(Regex("\\s+")).joinToString(" ").takeIf { it.isNotEmpty() }
}

/** A space as this device has it, with how many live notes are in it here. */
data class Space(val id: String, val name: String, val noteCount: Int)

/** A space change waiting to go to the server. */
class SpaceChange internal constructor(val id: String, val name: String, val delete: Boolean)

/**
 * This device's copy of the spaces (§7.7). Changes made here wait in the
 * rows themselves (dirty, pending_delete) until sync sends them; the
 * server's list replaces every row that has nothing waiting.
 */
class SpaceStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** The default space first, then by name; deleted ones already gone. */
    suspend fun list(): List<Space> = db.read { sql ->
        sql.query(
            """
            SELECT s.id, s.name,
                (SELECT count(*) FROM notes n WHERE n.space_id = s.id AND n.deleted_at IS NULL)
            FROM spaces s WHERE s.pending_delete = 0
            ORDER BY s.id != ?, lower(s.name), s.id
            """,
            Spaces.DEFAULT
        ) { Space(it.getText(0), it.getText(1), it.getLong(2).toInt()) }
    }

    /** Creates a space here, to be sent on the next sync. Returns its id. */
    suspend fun create(name: String): String {
        val stored = requireNotNull(Spaces.normalize(name)) { "A space needs a name" }
        val id = Uuid7.generate()
        db.write { sql ->
            sql.exec("INSERT INTO spaces (id, name, created_at, dirty) VALUES (?, ?, ?, 1)", id, stored, clock())
        }
        return id
    }

    suspend fun rename(id: String, name: String) {
        val stored = requireNotNull(Spaces.normalize(name)) { "A space needs a name" }
        db.write { sql -> sql.exec("UPDATE spaces SET name = ?, dirty = 1 WHERE id = ?", stored, id) }
    }

    /** Deletes an empty space; false (and nothing changes) for the default one or one with notes here. */
    suspend fun delete(id: String): Boolean = db.write { sql ->
        if (id == Spaces.DEFAULT) return@write false
        if (sql.long("SELECT count(*) FROM notes WHERE space_id = ? AND deleted_at IS NULL", id) > 0) return@write false
        sql.exec("UPDATE spaces SET pending_delete = 1 WHERE id = ?", id)
        true
    }

    /** Whether [name] is taken by another space, ignoring case, as the server compares. */
    suspend fun nameTaken(name: String, except: String? = null): Boolean {
        val key = Spaces.normalize(name)?.lowercase() ?: return false
        return list().any { it.id != except && it.name.lowercase() == key }
    }

    // --- for the sync engine ----------------------------------------------------

    internal suspend fun pending(): List<SpaceChange> = db.read { sql ->
        sql.query("SELECT id, name, pending_delete FROM spaces WHERE dirty = 1 OR pending_delete = 1 ORDER BY rowid") {
            SpaceChange(it.getText(0), it.getText(1), it.getLong(2) == 1L)
        }
    }

    /** The server stored [change] as [stored] (maybe renamed to settle a clash). Renamed again since: still waiting. */
    internal suspend fun sent(change: SpaceChange, stored: RemoteSpace) {
        db.write { sql ->
            sql.exec(
                "UPDATE spaces SET name = ?, dirty = 0 WHERE id = ? AND name = ?",
                stored.name,
                change.id,
                change.name
            )
        }
    }

    /** The server refused [change] for good (an invalid name): the list it sends next decides. */
    internal suspend fun dropped(change: SpaceChange) {
        db.write { sql -> sql.exec("UPDATE spaces SET dirty = 0, pending_delete = 0 WHERE id = ?", change.id) }
    }

    /** Deleted on the server. */
    internal suspend fun deleted(change: SpaceChange) {
        db.write { sql -> sql.exec("DELETE FROM spaces WHERE id = ? AND pending_delete = 1", change.id) }
    }

    /** The server's list replaces every row with nothing waiting to go. */
    internal suspend fun replace(remote: List<RemoteSpace>) {
        db.write { sql ->
            sql.exec(
                "DELETE FROM spaces WHERE dirty = 0 AND pending_delete = 0 AND id != ?",
                Spaces.DEFAULT
            )
            for (s in remote) {
                sql.exec(
                    """
                    INSERT INTO spaces (id, name, created_at) VALUES (?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET name = excluded.name, created_at = excluded.created_at
                    WHERE dirty = 0 AND pending_delete = 0
                    """,
                    s.id,
                    s.name,
                    s.createdAt
                )
            }
        }
    }
}
