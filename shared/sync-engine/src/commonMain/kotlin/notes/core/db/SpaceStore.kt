package notes.core.db

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import notes.core.api.RemoteInvite
import notes.core.api.RemoteSpace
import notes.core.api.SharedSpace
import notes.core.api.SpaceTrait
import notes.core.text.Uuid7
import notes.core.text.codePointLength
import notes.core.text.isIsoControl
import notes.core.text.isServerId
import notes.core.text.nowMillis

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
        require(stored.codePointLength() <= MAX_NAME) { "At most $MAX_NAME characters" }
        require(stored.none { isIsoControl(it) }) { "No control characters" }
        return stored
    }

    internal fun checkedId(id: String): String {
        require(isServerId(id)) { "A space id is 1-64 characters of [A-Za-z0-9_-]" }
        return id
    }
}

/**
 * A space as this device has it, with how many live notes are in it here.
 * [icon] is a key into the app's set; "" for the default one. [traits] are as the server last sent them (§7.8).
 */
data class Space(
    val id: String,
    val name: String,
    val noteCount: Int,
    val icon: String = "",
    val createdAt: Long = 0,
    val traits: List<SpaceTrait> = emptyList(),
    /** Who's in it, when it's shared (§7.9); null for the account's own. */
    val shared: SharedSpace? = null,
    /**
     * The app's alone, never stored: traits being turned off on this device, gone from [traits] before the server
     * confirms but still exposing the space to the lock rules (§7.8) until it has.
     */
    val leaving: List<SpaceTrait> = emptyList()
)

/**
 * What other members changed in a shared space since this device last showed it (§7.9): [count] notes,
 * the [names] of who changed them (latest first), the latest at [latestAt].
 */
data class SpaceNews(val count: Int, val names: List<String>, val latestAt: Long)

private val traitsJson = Json { ignoreUnknownKeys = true }
private val traitsSerializer = ListSerializer(SpaceTrait.serializer())

// A row this release can't read (a later one wrote it) shows no traits rather than failing the list.
private fun decodeTraits(text: String): List<SpaceTrait> =
    runCatching { traitsJson.decodeFromString(traitsSerializer, text) }.getOrDefault(emptyList())

private fun encodeTraits(traits: List<SpaceTrait>): String = traitsJson.encodeToString(traitsSerializer, traits)

private fun decodeShared(text: String?): SharedSpace? =
    text?.let { runCatching { traitsJson.decodeFromString(SharedSpace.serializer(), it) }.getOrNull() }

/** A space change waiting to go to the server. */
class SpaceChange internal constructor(val id: String, val name: String, val icon: String, val delete: Boolean)

/**
 * This device's copy of the spaces (§7.7). Changes made here wait in the
 * rows themselves (dirty, pending_delete) until sync sends them; the
 * server's list replaces every row that has nothing waiting.
 */
class SpaceStore(private val db: Database, private val clock: () -> Long = ::nowMillis) {
    /** The default space first, then by name; deleted ones already gone. */
    suspend fun list(): List<Space> = db.read { sql ->
        sql.query(
            """
            SELECT s.id, s.name,
                (SELECT count(*) FROM notes n WHERE n.space_id = s.id AND n.deleted_at IS NULL), s.icon, s.created_at,
                s.traits, s.shared
            FROM spaces s WHERE s.pending_delete = 0
            ORDER BY s.id != ?, lower(s.name), s.id
            """,
            Spaces.DEFAULT
        ) {
            Space(
                it.getText(0),
                it.getText(1),
                it.getLong(2).toInt(),
                it.getText(3),
                it.getLong(4),
                decodeTraits(it.getText(5)),
                decodeShared(it.textOrNull(6))
            )
        }
    }

    /** The invitations waiting for this account, as last synced (§7.9). */
    suspend fun invites(): List<RemoteInvite> = db.read { sql ->
        sql.query(
            """
            SELECT id, space_id, space_name, space_icon, from_id, from_username, created_at, from_display_name, from_avatar
            FROM invites ORDER BY created_at, id
            """
        ) {
            RemoteInvite(
                it.getText(0),
                it.getText(1),
                it.getText(2),
                it.getText(3),
                notes.core.api.Member(it.getLong(4), it.getText(5), it.getText(7), it.getText(8)),
                it.getLong(6)
            )
        }
    }

    /**
     * The space was shown until [at]: what changed by then is no longer news. Pass the moment it was left when
     * the write may land later, or a change made in between would never count.
     */
    suspend fun visit(id: String, at: Long = clock()) {
        db.write { sql ->
            // Never back: a visit timed earlier can land after a later one.
            sql.exec(
                "UPDATE spaces SET visited_at = max(coalesce(visited_at, 0), ?) WHERE id = ? AND shared IS NOT NULL",
                at,
                id
            )
        }
    }

    /** By shared space, what other members changed since this device last showed it; spaces with none left out. */
    suspend fun news(): Map<String, SpaceNews> = db.read { sql ->
        val me = sql.one("SELECT user_id FROM sync_state WHERE id = 1") { it.longOrNull(0) } ?: return@read emptyMap()
        val spaces = sql.query("SELECT id, coalesce(visited_at, 0), shared FROM spaces WHERE shared IS NOT NULL") {
            Triple(it.getText(0), it.getLong(1), decodeShared(it.textOrNull(2)))
        }
        buildMap {
            for ((id, since, shared) in spaces) {
                val changed = sql.query(
                    """
                    SELECT updated_by, updated_at FROM notes
                    WHERE space_id = ? AND deleted_at IS NULL AND updated_by IS NOT NULL AND updated_by != ? AND updated_at > ?
                    ORDER BY updated_at DESC
                    """,
                    id,
                    me,
                    since
                ) { it.getLong(0) to it.getLong(1) }
                if (changed.isEmpty()) continue
                val names = shared?.members.orEmpty().associate { it.id to it.name }
                val who = changed.map { it.first }.distinct().mapNotNull { names[it] }
                put(id, SpaceNews(changed.size, who, changed.first().second))
            }
        }
    }

    /**
     * Creates a space here, to be sent on the next sync. Returns its id. Creating [id] again
     * changes nothing, so a caller that can't tell whether a create went through can resend it.
     */
    suspend fun create(name: String, icon: String = "", id: String = Uuid7.generate()): String {
        val stored = Spaces.checked(name)
        Spaces.checkedId(id)
        db.write { sql ->
            sql.exec(
                "INSERT INTO spaces (id, name, icon, created_at, dirty) VALUES (?, ?, ?, ?, 1) ON CONFLICT (id) DO NOTHING",
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
            """
            SELECT id, name, icon, pending_delete FROM spaces
            WHERE (dirty = 1 OR pending_delete = 1) AND parked = 0 ORDER BY rowid
            """
        ) { SpaceChange(it.getText(0), it.getText(1), it.getText(2), it.getLong(3) == 1L) }
    }

    /** [change] got an answer that wasn't notesd's: one more attempt, parked at the limit. */
    internal suspend fun attempted(change: SpaceChange) {
        db.write { sql -> sql.attempted("spaces", "id = ?", change.id) }
    }

    /** The server stored [change] as [stored] (maybe renamed to settle a clash). Changed again since: still waiting. */
    internal suspend fun sent(change: SpaceChange, stored: RemoteSpace) {
        db.write { sql ->
            sql.exec(
                "UPDATE spaces SET name = ?, icon = ?, dirty = 0, attempts = 0 WHERE id = ? AND name = ? AND icon = ?",
                stored.name,
                stored.icon,
                change.id,
                change.name,
                change.icon
            )
            sql.exec(
                "UPDATE spaces SET traits = ?, shared = ? WHERE id = ?",
                encodeTraits(stored.traits),
                stored.shared?.let { traitsJson.encodeToString(SharedSpace.serializer(), it) },
                change.id
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
            // Rows the list still has are updated in place: what this device keeps on them (visited_at) stays.
            val listed = remote.map { it.id }.toSet()
            val gone = sql.query(
                "SELECT id FROM spaces WHERE dirty = 0 AND pending_delete = 0 AND id != ?",
                Spaces.DEFAULT
            ) {
                it.getText(0)
            }.filter { it !in listed }
            for (id in gone) sql.exec("DELETE FROM spaces WHERE id = ?", id)
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
                // Even on a row with a change waiting: traits and members are the server's, not this device's.
                // A space no longer shared forgets when it was shown: shared again, only what's new from then is news.
                sql.exec(
                    """
                    UPDATE spaces SET traits = ?, shared = ?, visited_at = CASE WHEN ? IS NULL THEN NULL ELSE visited_at END
                    WHERE id = ?
                    """,
                    encodeTraits(s.traits),
                    s.shared?.let { traitsJson.encodeToString(SharedSpace.serializer(), it) },
                    s.shared?.role,
                    s.id
                )
            }
        }
    }

    /** The server's invitations replace this device's copy. */
    internal suspend fun replaceInvites(remote: List<RemoteInvite>) {
        db.write { sql ->
            sql.exec("DELETE FROM invites")
            for (i in remote) {
                sql.exec(
                    """
                    INSERT INTO invites (id, space_id, space_name, space_icon, from_id, from_username, created_at,
                        from_display_name, from_avatar)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    i.id,
                    i.spaceId,
                    i.spaceName,
                    i.spaceIcon,
                    i.from.id,
                    i.from.username,
                    i.createdAt,
                    i.from.displayName,
                    i.from.avatar
                )
            }
        }
    }
}
