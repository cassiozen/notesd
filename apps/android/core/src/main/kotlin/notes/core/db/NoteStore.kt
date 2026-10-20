package notes.core.db

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import notes.core.crypto.LockedNotes
import notes.core.text.previewOf

data class Note(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val conflictOf: String?,
    val serverRevision: Long?,
    val dirty: Boolean,
    /** The last synced version, kept while [dirty]: what a merge compares both sides against. */
    val baseTitle: String? = null,
    val baseContent: String? = null,
    /** The space the note is in (§7.7), and the synced one while [dirty]. */
    val spaceId: String = Spaces.DEFAULT,
    val baseSpaceId: String? = null
)

/** [locked]: the body is encrypted (§10.1), so there's no preview. */
data class NoteSummary(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,
    val locked: Boolean = false
)

/** Narrows the list and search; null shows every note. */
sealed interface NoteFilter {
    /** Notes with the tag [key] or one nested under it. [name] is how to show it. */
    data class Tag(val key: String, val name: String) : NoteFilter

    data object Untagged : NoteFilter

    data object Locked : NoteFilter
}

/** A tag in live notes, with the number of notes carrying it or one nested under it. */
data class TagCount(val key: String, val name: String, val count: Int)

/** Narrows a query to one space (§7.7), or to every space but one; null leaves it open. */
sealed interface SpaceScope {
    data class In(val id: String) : SpaceScope

    data class Except(val id: String) : SpaceScope
}

/** A condition on the notes row aliased n, with its arguments. */
private fun SpaceScope?.sql(): Pair<String, List<Any>> = when (this) {
    null -> "1" to emptyList()
    is SpaceScope.In -> "n.space_id = ?" to listOf(id)
    is SpaceScope.Except -> "n.space_id != ?" to listOf(id)
}

/** Both conditions, ANDed. */
private infix fun Pair<String, List<Any>>.and(other: Pair<String, List<Any>>): Pair<String, List<Any>> =
    "($first) AND (${other.first})" to (second + other.second)

/** A condition on the notes row aliased n, with its arguments. */
private fun NoteFilter?.sql(): Pair<String, List<Any>> = when (this) {
    null -> "1" to emptyList()

    NoteFilter.Untagged -> "NOT EXISTS (SELECT 1 FROM note_tags t WHERE t.note_seq = n.seq)" to emptyList()

    NoteFilter.Locked -> "substr(n.content, 1, ?) = ?" to listOf(LockedNotes.PREFIX.length, LockedNotes.PREFIX)

    // '0' sorts right after '/', so the range holds exactly the nested keys.
    is NoteFilter.Tag ->
        "EXISTS (SELECT 1 FROM note_tags t WHERE t.note_seq = n.seq AND (t.key = ? OR (t.key > ? AND t.key < ?)))" to
            listOf(key, "$key/", "${key}0")
}

/** A search result. [excerpt] marks matches with [Search.MARK_OPEN] / [Search.MARK_CLOSE]; a [locked] note matched on its title only. */
data class SearchHit(
    val id: String,
    val title: String,
    val excerpt: String,
    val updatedAt: Long,
    val locked: Boolean = false,
    val spaceId: String = Spaces.DEFAULT
)

/**
 * Notes on this device. Every local change is one transaction that updates the
 * note, its search entry and the sync outbox together, so the three can't
 * disagree after a crash. Outbox operations coalesce until sent: an unsent
 * create or update takes the latest text; once sent, a later change queues a
 * new operation (a sent payload must be resent as is).
 */
class NoteStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** Every committed write, local or from sync. */
    val changes: SharedFlow<Unit> get() = db.changes

    private val _localEdits =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Only edits made on this device: what should trigger an upload (sync's own writes don't). */
    val localEdits: SharedFlow<Unit> = _localEdits

    suspend fun get(id: String): Note? = db.read { it.note(id) }

    /** Live notes matching [filter] in [space] (null: every space), most recently edited first. */
    suspend fun list(filter: NoteFilter? = null, space: String? = null): List<NoteSummary> = db.read { sql ->
        val (cond, args) = filter.sql() and space?.let { SpaceScope.In(it) }.sql()
        sql.query(
            """
            SELECT n.id, n.title, n.updated_at, f.body_text, substr(n.content, 1, ?) = ?
            FROM notes n LEFT JOIN notes_fts f ON f.rowid = n.seq
            WHERE n.deleted_at IS NULL AND $cond
            ORDER BY n.updated_at DESC, n.id DESC
            """,
            LockedNotes.PREFIX.length,
            LockedNotes.PREFIX,
            *args.toTypedArray()
        ) {
            NoteSummary(
                it.getText(0),
                it.getText(1),
                previewOf(it.textOrNull(3).orEmpty()),
                it.getLong(2),
                it.getLong(4) == 1L
            )
        }
    }

    /** Live locked notes, in [space] or everywhere. */
    suspend fun lockedCount(space: String? = null): Int = db.read { sql ->
        val (cond, args) = NoteFilter.Locked.sql() and space?.let { SpaceScope.In(it) }.sql()
        sql.query("SELECT count(*) FROM notes n WHERE n.deleted_at IS NULL AND $cond", *args.toTypedArray()) {
            it.getLong(0).toInt()
        }.single()
    }

    /** Ranked as on the server: title matches weigh 10×, then the most recently edited. */
    suspend fun search(
        input: String,
        filter: NoteFilter? = null,
        limit: Int = 50,
        scope: SpaceScope? = null
    ): List<SearchHit> {
        val match = Search.buildQuery(input)
        if (match.isEmpty()) return emptyList()
        val (cond, args) = filter.sql() and scope.sql()
        return db.read { sql ->
            sql.query(
                """
                SELECT n.id, n.title, n.updated_at, snippet(notes_fts, 1, ?, ?, '…', 20), substr(n.content, 1, ?) = ?,
                    n.space_id
                FROM notes_fts JOIN notes n ON n.seq = notes_fts.rowid
                WHERE notes_fts MATCH ? AND n.deleted_at IS NULL AND $cond
                ORDER BY bm25(notes_fts, ?, ?), n.updated_at DESC
                LIMIT ?
                """,
                Search.MARK_OPEN,
                Search.MARK_CLOSE,
                LockedNotes.PREFIX.length,
                LockedNotes.PREFIX,
                match,
                *args.toTypedArray(),
                Search.TITLE_WEIGHT,
                Search.CONTENT_WEIGHT,
                limit
            ) {
                SearchHit(
                    it.getText(0),
                    it.getText(1),
                    it.textOrNull(3).orEmpty(),
                    it.getLong(2),
                    it.getLong(4) == 1L,
                    it.getText(5)
                )
            }
        }
    }

    /** How many live notes outside [space] a search finds: the "more in other spaces" row (§7.7). */
    suspend fun countElsewhere(input: String, space: String): Int {
        val match = Search.buildQuery(input)
        if (match.isEmpty()) return 0
        return db.read { sql ->
            sql.long(
                """
                SELECT count(*) FROM notes_fts JOIN notes n ON n.seq = notes_fts.rowid
                WHERE notes_fts MATCH ? AND n.deleted_at IS NULL AND n.space_id != ?
                """,
                match,
                space
            ).toInt()
        }
    }

    /** The space a note is in, or null if there's no such live note. */
    suspend fun spaceOf(id: String): String? = db.read { sql -> sql.note(id)?.takeIf { it.deletedAt == null }?.spaceId }

    /**
     * Every tag in live notes in [space] (null: every space), sorted by key, parents of nested tags
     * included. A tag is named as in the most recently edited note using it (as on the server).
     */
    suspend fun tags(space: String? = null): List<TagCount> = db.read { sql ->
        val names = HashMap<String, String>()
        val notes = HashMap<String, HashSet<Long>>()
        val (cond, args) = space?.let { SpaceScope.In(it) }.sql()
        sql.query(
            """
            SELECT t.key, t.name, t.note_seq FROM note_tags t JOIN notes n ON n.seq = t.note_seq
            WHERE n.deleted_at IS NULL AND $cond ORDER BY n.updated_at DESC, n.id DESC
            """,
            *args.toTypedArray()
        ) { Triple(it.getText(0), it.getText(1), it.getLong(2)) }
            .forEach { (key, name, seq) ->
                val keys = key.split('/')
                val levels = name.split('/')
                for (i in keys.indices) {
                    val k = keys.subList(0, i + 1).joinToString("/")
                    names.getOrPut(k) { levels.subList(0, i + 1).joinToString("/") }
                    notes.getOrPut(k) { HashSet() } += seq
                }
            }
        names.map { (k, name) -> TagCount(k, name, notes.getValue(k).size) }.sortedBy { it.key }
    }

    /**
     * Saves the editor's text for [id], creating the note on first save. A note
     * that doesn't exist yet isn't created while blank, so opening "New note"
     * and leaving leaves nothing behind.
     *
     * [basedOn] is the stored title and content the editor's text grew from.
     * If sync changed the note since, the two are merged per field; where both
     * changed the same field the editor's text wins and the other version
     * becomes a conflicted copy, so nothing is lost either way.
     *
     * Returns the stored note, or null when nothing was stored (blank draft,
     * deleted note).
     *
     * [imageIds] are the images a locked [content] shows (§7.6), read from
     * the text before it was encrypted; the server can't read them itself.
     *
     * A new note is created in [spaceId]; an existing one stays where it is ([move] moves it).
     */
    suspend fun save(
        id: String,
        title: String,
        content: String,
        basedOn: Pair<String, String>? = null,
        imageIds: List<String>? = null,
        spaceId: String = Spaces.DEFAULT
    ): Note? {
        val saved = db.write { sql ->
            sql.rememberImages(id, content, imageIds)
            val current = sql.note(id)
            when {
                current == null -> if (title.isBlank() && content.isBlank()) {
                    null
                } else {
                    create(sql, id, title, content, spaceId)
                }

                current.deletedAt != null -> null

                basedOn != null && (current.title to current.content) != basedOn ->
                    mergeWithSync(sql, current, title, content, basedOn)

                current.title == title && current.content == content -> current

                else -> update(sql, current, title, content)
            }
        }
        if (saved != null) _localEdits.tryEmit(Unit)
        return saved
    }

    private fun create(sql: Sql, id: String, title: String, content: String, spaceId: String): Note {
        val now = clock()
        sql.exec(
            "INSERT INTO notes (id, title, content, created_at, updated_at, space_id, dirty) VALUES (?, ?, ?, ?, ?, ?, 1)",
            id,
            title,
            content,
            now,
            now,
            spaceId
        )
        sql.index(id, title, content)
        sql.enqueue(id, CREATE, Payload(title, content, createdAt = now, updatedAt = now, spaceId = spaceId), now)
        return sql.note(id)!!
    }

    private fun update(
        sql: Sql,
        current: Note,
        title: String,
        content: String,
        spaceId: String = current.spaceId
    ): Note {
        val now = maxOf(clock(), current.updatedAt)
        // Right-hand sides see the row before the update: base_* capture the synced text when a clean note first changes.
        sql.exec(
            """
            UPDATE notes SET title = ?, content = ?, space_id = ?, updated_at = ?, dirty = 1,
                base_title = CASE WHEN dirty = 0 THEN title ELSE base_title END,
                base_content = CASE WHEN dirty = 0 THEN content ELSE base_content END,
                base_deleted_at = CASE WHEN dirty = 0 THEN deleted_at ELSE base_deleted_at END,
                base_space_id = CASE WHEN dirty = 0 THEN space_id ELSE base_space_id END
            WHERE id = ?
            """,
            title,
            content,
            spaceId,
            now,
            current.id
        )
        sql.index(current.id, title, content)
        sql.queueText(current.id, title, content, spaceId, now)
        return sql.note(current.id)!!
    }

    /** Moves a note to another space (§7.7), saved like an edit. False if there's no such live note. */
    suspend fun move(id: String, spaceId: String): Boolean {
        val moved = db.write { sql ->
            val current = sql.note(id)
            when {
                current == null || current.deletedAt != null -> false

                current.spaceId == spaceId -> true

                else -> {
                    update(sql, current, current.title, current.content, spaceId)
                    true
                }
            }
        }
        if (moved) _localEdits.tryEmit(Unit)
        return moved
    }

    private fun mergeWithSync(
        sql: Sql,
        current: Note,
        title: String,
        content: String,
        basedOn: Pair<String, String>
    ): Note {
        val (baseTitle, baseContent) = basedOn
        val mergedTitle = Merge.field(baseTitle, title, current.title, Merge.sameText)
        val mergedContent = Merge.field(baseContent, content, current.content, Merge.sameMarkdown)
        if (mergedTitle == null || mergedContent == null) {
            sql.insertCopy(copyTitle(current.title, CONFLICTED), current.content, current.id, current.spaceId, clock())
        }
        val t = mergedTitle ?: title
        val c = mergedContent ?: content
        return if (t == current.title && c == current.content) current else update(sql, current, t, c)
    }

    /**
     * Keeps text whose note was deleted elsewhere while it was being edited:
     * a new "(Recovered copy)" note in [spaceId], as sync makes for edits it finds. Returns its id.
     */
    suspend fun recover(
        title: String,
        content: String,
        imageIds: List<String>? = null,
        spaceId: String = Spaces.DEFAULT
    ): String {
        val id = db.write { it.insertCopy(copyTitle(title, RECOVERED), content, null, spaceId, clock(), imageIds) }
        _localEdits.tryEmit(Unit)
        return id
    }

    /**
     * Deletes [id]: a note the server never received is simply removed;
     * otherwise it becomes a local tombstone (hidden, out of the index) with a
     * delete queued, replacing unsent edits.
     */
    suspend fun delete(id: String) {
        db.write { sql ->
            val current = sql.note(id)
            if (current == null || current.deletedAt != null) return@write
            val sent = sql.long("SELECT count(*) FROM sync_outbox WHERE note_id = ? AND sent = 1", id) > 0
            sql.unindex(id)
            sql.forgetImages(id)
            if (current.serverRevision == null && !sent) {
                sql.exec("DELETE FROM sync_outbox WHERE note_id = ?", id)
                sql.exec("DELETE FROM notes WHERE id = ?", id)
                return@write
            }
            sql.exec("DELETE FROM sync_outbox WHERE note_id = ? AND sent = 0", id)
            sql.exec(
                """
                UPDATE notes SET deleted_at = ?, dirty = 1,
                    base_title = CASE WHEN dirty = 0 THEN title ELSE base_title END,
                    base_content = CASE WHEN dirty = 0 THEN content ELSE base_content END,
                    base_deleted_at = CASE WHEN dirty = 0 THEN deleted_at ELSE base_deleted_at END,
                    base_space_id = CASE WHEN dirty = 0 THEN space_id ELSE base_space_id END
                WHERE id = ?
                """,
                clock(),
                id
            )
            sql.enqueue(id, DELETE, null, clock())
        }
        _localEdits.tryEmit(Unit)
    }

    /** Rebuilds the search index and tags from the notes. Sync state is untouched. */
    suspend fun reindex(): Int = db.write { it.rebuildIndex() }

    /** Removes every note and all sync state from this device. */
    suspend fun wipe() {
        db.write { sql ->
            sql.exec("DELETE FROM notes_fts")
            sql.exec("DELETE FROM note_tags")
            sql.exec("DELETE FROM notes")
            sql.exec("DELETE FROM sync_outbox")
            sql.exec("DELETE FROM lock_keys")
            sql.exec("DELETE FROM pending_images")
            sql.exec("DELETE FROM locked_images")
            sql.exec("DELETE FROM spaces")
            sql.exec("INSERT INTO spaces (id, name, created_at) VALUES (?, ?, 0)", Spaces.DEFAULT, Spaces.DEFAULT_NAME)
            sql.exec(
                """
                UPDATE sync_state SET cursor = 0, generation = NULL, last_sync_at = NULL, last_error = NULL,
                    full_sync = 0
                """
            )
        }
    }

    /** How many notes have changes the server hasn't confirmed. */
    suspend fun pendingNotes(): Int = db.read { it.long("SELECT count(DISTINCT note_id) FROM sync_outbox").toInt() }

    /** For tests of coalescing without a server: pretend [operationId] went out. */
    internal suspend fun markSent(operationId: String) {
        db.write { it.exec("UPDATE sync_outbox SET sent = 1 WHERE operation_id = ?", operationId) }
    }

    /** Unsent and in-flight operations, oldest first. */
    suspend fun outbox(): List<OutboxEntry> = db.read { sql ->
        sql.query(
            """
            SELECT operation_id, note_id, operation_type, base_revision, payload_json, sent
            FROM sync_outbox ORDER BY created_at, rowid
            """
        ) {
            OutboxEntry(
                it.getText(0),
                it.getText(1),
                it.getText(2),
                it.longOrNull(3),
                it.getText(4),
                it.getLong(5) == 1L
            )
        }
    }
}

data class OutboxEntry(
    val operationId: String,
    val noteId: String,
    val type: String,
    val baseRevision: Long?,
    val payloadJson: String,
    val sent: Boolean
)
