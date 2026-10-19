package notes.core.db

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import notes.core.crypto.LockedNotes
import notes.core.images.ImageRef
import notes.core.text.Uuid7
import notes.core.text.plainText
import notes.core.text.tags

// Row access shared by NoteStore (local edits) and SyncStore (the sync engine). Callers hold a write transaction.

internal const val CREATE = "create"
internal const val UPDATE = "update"
internal const val DELETE = "delete"

internal const val NOTE_COLUMNS =
    "id, title, content, created_at, updated_at, deleted_at, conflict_of, server_revision, dirty, " +
        "base_title, base_content, space_id, base_space_id"

internal fun noteRow(st: SQLiteStatement) = Note(
    id = st.getText(0),
    title = st.getText(1),
    content = st.getText(2),
    createdAt = st.getLong(3),
    updatedAt = st.getLong(4),
    deletedAt = st.longOrNull(5),
    conflictOf = st.textOrNull(6),
    serverRevision = st.longOrNull(7),
    dirty = st.getLong(8) == 1L,
    baseTitle = st.textOrNull(9),
    baseContent = st.textOrNull(10),
    spaceId = st.getText(11),
    baseSpaceId = st.textOrNull(12)
)

internal fun Sql.note(id: String): Note? = one("SELECT $NOTE_COLUMNS FROM notes WHERE id = ?", id, row = ::noteRow)

/** Replaces the search entry and tags for a note. */
internal fun Sql.index(id: String, title: String, content: String) {
    val seq = long("SELECT seq FROM notes WHERE id = ?", id)
    unindexSeq(seq)
    insertIndex(seq, title, content)
}

internal fun Sql.unindex(id: String) {
    unindexSeq(long("SELECT seq FROM notes WHERE id = ?", id))
}

private fun Sql.unindexSeq(seq: Long) {
    exec("DELETE FROM notes_fts WHERE rowid = ?", seq)
    exec("DELETE FROM note_tags WHERE note_seq = ?", seq)
}

private fun Sql.insertIndex(seq: Long, title: String, stored: String) {
    // A locked body is ciphertext: only the title is searchable and tagged, as on the server (§10.1).
    val content = if (LockedNotes.isLocked(stored)) "" else stored
    exec("INSERT INTO notes_fts (rowid, title, body_text) VALUES (?, ?, ?)", seq, title, plainText(content))
    for (tag in tags(title, content)) {
        exec("INSERT INTO note_tags (note_seq, key, name) VALUES (?, ?, ?)", seq, tag.key, tag.name)
    }
}

/** Rebuilds the search index and tags from the notes. Sync state is untouched. */
internal fun Sql.rebuildIndex(): Int {
    exec("DELETE FROM notes_fts")
    exec("DELETE FROM note_tags")
    exec("DELETE FROM rebuild_index")
    val live = query("SELECT seq, title, content FROM notes WHERE deleted_at IS NULL") {
        Triple(it.getLong(0), it.getText(1), it.getText(2))
    }
    for ((seq, title, content) in live) insertIndex(seq, title, content)
    return live.size
}

/** The request body an operation will send, minus what is fixed at send time (operationId, baseRevision). */
@Serializable
internal data class Payload(
    val title: String = "",
    val content: String = "",
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val conflictOf: String? = null,
    /** A locked body's images (§7.6), when this device knows them; null leaves the server's record as it is. */
    val imageIds: List<String>? = null,
    /** The note's space (§7.7); null (operations queued before spaces) keeps the server's. */
    val spaceId: String? = null
)

internal val outboxJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

internal fun Sql.enqueue(noteId: String, type: String, payload: Payload?, now: Long) {
    val sent = payload?.copy(imageIds = lockedImageIds(payload.content))
    exec(
        """
        INSERT INTO sync_outbox (operation_id, note_id, operation_type, base_revision, payload_json, created_at)
        VALUES (?, ?, ?, NULL, ?, ?)
        """,
        Uuid7.generate(),
        noteId,
        type,
        sent?.let { outboxJson.encodeToString(it) } ?: "{}",
        now
    )
}

internal class PendingOp(val operationId: String, val type: String, val payload: Payload)

/** The newest operation for [noteId] that hasn't been sent: the one later edits coalesce into. */
internal fun Sql.unsentOp(noteId: String): PendingOp? = one(
    """
    SELECT operation_id, operation_type, payload_json FROM sync_outbox
    WHERE note_id = ? AND sent = 0 ORDER BY created_at DESC, rowid DESC LIMIT 1
    """,
    noteId
) { PendingOp(it.getText(0), it.getText(1), outboxJson.decodeFromString(it.getText(2))) }

/**
 * Makes sure the server will receive [title]/[content] in [spaceId] for [noteId]: rewrites the
 * unsent create or update, or queues a new update (a sent payload is never changed).
 */
internal fun Sql.queueText(noteId: String, title: String, content: String, spaceId: String, now: Long) {
    val pending = unsentOp(noteId)
    if (pending != null && pending.type != DELETE) {
        val payload = pending.payload.copy(
            title = title,
            content = content,
            updatedAt = now,
            imageIds = lockedImageIds(content),
            spaceId = spaceId
        )
        exec(
            "UPDATE sync_outbox SET payload_json = ? WHERE operation_id = ?",
            outboxJson.encodeToString(payload),
            pending.operationId
        )
    } else {
        enqueue(noteId, UPDATE, Payload(title, content, updatedAt = now, spaceId = spaceId), now)
    }
}

/**
 * A new note holding [title]/[content] in [spaceId], queued for upload. Conflicted and
 * recovered copies are made this way, so no text is ever dropped.
 */
internal fun Sql.insertCopy(
    title: String,
    content: String,
    conflictOf: String?,
    spaceId: String,
    now: Long,
    imageIds: List<String>? = null
): String {
    val id = Uuid7.generate()
    rememberImages(id, content, imageIds ?: lockedImageIds(content))
    exec(
        """
        INSERT INTO notes (id, title, content, created_at, updated_at, conflict_of, space_id, dirty)
        VALUES (?, ?, ?, ?, ?, ?, ?, 1)
        """,
        id,
        title,
        content,
        now,
        now,
        conflictOf,
        spaceId
    )
    index(id, title, content)
    enqueue(
        id,
        CREATE,
        Payload(title, content, createdAt = now, updatedAt = now, conflictOf = conflictOf, spaceId = spaceId),
        now
    )
    return id
}

internal fun copyTitle(title: String, suffix: String) = title.trim().ifEmpty { "Untitled" } + suffix

internal const val CONFLICTED = " (Conflicted copy)"
internal const val RECOVERED = " (Recovered copy)"

/** How many recent locked bodies per note keep their images: saved, stored, and one a conflict copies. */
private const val BODIES_KEPT = 3

/**
 * Records that the locked body [content] of [noteId] shows [imageIds], so
 * whatever later sends that body (a save, a merge, a copy) lists them. The
 * few newest bodies per note are kept; older ones are never sent again.
 */
internal fun Sql.rememberImages(noteId: String, content: String, imageIds: List<String>?) {
    if (imageIds == null || !LockedNotes.isLocked(content)) return
    exec(
        "INSERT OR REPLACE INTO locked_images (note_id, content_sha, image_ids) VALUES (?, ?, ?)",
        noteId,
        ImageRef.sha256(content.toByteArray()),
        outboxJson.encodeToString(imageIds)
    )
    exec(
        """
        DELETE FROM locked_images WHERE note_id = ? AND rowid NOT IN
            (SELECT rowid FROM locked_images WHERE note_id = ? ORDER BY rowid DESC LIMIT ?)
        """,
        noteId,
        noteId,
        BODIES_KEPT.toLong()
    )
}

/** A note gone from this device: its bodies' images aren't needed. */
internal fun Sql.forgetImages(noteId: String) = exec("DELETE FROM locked_images WHERE note_id = ?", noteId)

/** The images in a locked body this device encrypted or synced; null for a plain body or an unknown one. */
internal fun Sql.lockedImageIds(content: String): List<String>? {
    if (!LockedNotes.isLocked(content)) return null
    return one(
        "SELECT image_ids FROM locked_images WHERE content_sha = ? ORDER BY rowid DESC LIMIT 1",
        ImageRef.sha256(content.toByteArray())
    ) { outboxJson.decodeFromString<List<String>>(it.getText(0)) }
}
