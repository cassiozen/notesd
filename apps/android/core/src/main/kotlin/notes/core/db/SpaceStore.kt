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

    /** [name] as the server will store it; throws for one it would refuse, which would otherwise be lost at sync. */
    internal fun checked(name: String): String {
        val stored = requireNotNull(normalize(name)) { "A space needs a name" }
        require(stored.codePointCount(0, stored.length) <= MAX_NAME) { "At most $MAX_NAME characters" }
        require(stored.none { Character.isISOControl(it) }) { "No control characters" }
        return stored
    }
}

/**
 * A space as this device has it, with how many live notes are in it here.
 * [icon] is a key into the app's set; "" for the default one.
 */
data class Space(val id: String, val name: String, val noteCount: Int, val icon: String = "")

/** A space change waiting to go to the server. */
class SpaceChange internal constructor(val id: String, val name: String, val icon: String, val delete: Boolean)

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
                (SELECT count(*) FROM notes n WHERE n.space_id = s.id AND n.deleted_at IS NULL), s.icon
            FROM spaces s WHERE s.pending_delete = 0
            ORDER BY s.id != ?, lower(s.name), s.id
            """,
            Spaces.DEFAULT
        ) { Space(it.getText(0), it.getText(1), it.getLong(2).toInt(), it.getText(3)) }
    }

    /** Creates a space here, to be sent on the next sync. Returns its id. */
    suspend fun create(name: String, icon: String = ""): String {
        val stored = Spaces.checked(name)
        val id = Uuid7.generate()
        db.write { sql ->
            sql.exec(
                "INSERT INTO spaces (id, name, icon, created_at, dirty) VALUES (?, ?, ?, ?, 1)",
                id,
                stored,
                icon,
                clock()
            )
        }
        return id
    }

    /** Renames the space, and changes its icon unless [icon] is null. */
    suspend fun update(id: String, name: String, icon: String? = null) {
        val stored = Spaces.checked(name)
        db.write { sql ->
            sql.exec("UPDATE spaces SET name = ?, icon = coalesce(?, icon), dirty = 1 WHERE id = ?", stored, icon, id)
        }
    }

    /** Deletes an empty space; false (and nothing changes) for the default one or one with notes here. */
    suspend fun delete(id: String): Boolean = db.write { sql ->
        if (id == Spaces.DEFAULT) return@write false
        if (sql.long("SELECT count(*) FROM notes WHERE space_id = ? AND deleted_at IS NULL", id) > 0) return@write false
        sql.exec("UPDATE spaces SET pending_delete = 1 WHERE id = ?", id)
        true
    }

    // --- for the sync engine ----------------------------------------------------

    internal suspend fun pending(): List<SpaceChange> = db.read { sql ->
        sql.query(
            "SELECT id, name, icon, pending_delete FROM spaces WHERE dirty = 1 OR pending_delete = 1 ORDER BY rowid"
        ) { SpaceChange(it.getText(0), it.getText(1), it.getText(2), it.getLong(3) == 1L) }
    }

    /** The server stored [change] as [stored] (maybe renamed to settle a clash). Changed again since: still waiting. */
    internal suspend fun sent(change: SpaceChange, stored: RemoteSpace) {
        db.write { sql ->
            sql.exec(
                "UPDATE spaces SET name = ?, icon = ?, dirty = 0 WHERE id = ? AND name = ? AND icon = ?",
                stored.name,
                stored.icon,
                change.id,
                change.name,
                change.icon
            )
        }
    }

    /**
     * The server refused [change] for good (an invalid name, a space that isn't empty): the list it sends next
     * decides. Changed again since it went: the new version is still waiting.
     */
    internal suspend fun dropped(change: SpaceChange) {
        db.write { sql ->
            if (change.delete) {
                sql.exec("UPDATE spaces SET pending_delete = 0 WHERE id = ?", change.id)
            } else {
                sql.exec(
                    "UPDATE spaces SET dirty = 0 WHERE id = ? AND name = ? AND icon = ?",
                    change.id,
                    change.name,
                    change.icon
                )
            }
        }
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
                    INSERT INTO spaces (id, name, icon, created_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET name = excluded.name, icon = excluded.icon,
                        created_at = excluded.created_at
                    WHERE dirty = 0 AND pending_delete = 0
                    """,
                    s.id,
                    s.name,
                    s.icon,
                    s.createdAt
                )
            }
        }
    }
}
