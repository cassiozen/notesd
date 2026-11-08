package notes.core.db

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import notes.core.crypto.LockedEnvelope
import notes.core.text.Uuid7
import notes.core.text.plainText
import notes.core.text.sha256Hex
import notes.core.text.tags

// Row access shared by NoteStore (local edits) and SyncStore (the sync engine). Callers hold a write transaction.

internal const val CREATE = "create"
internal const val UPDATE = "update"
internal const val DELETE = "delete"

/** Moves a note to a space in another file (§7.9); its payload's spaceId is the destination. */
internal const val MOVE = "move"

internal const val NOTE_COLUMNS =
    "id, title, content, created_at, updated_at, deleted_at, conflict_of, server_revision, dirty, " +
        "base_title, base_content, space_id, base_space_id, updated_by"

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
    baseSpaceId = st.textOrNull(12),
    updatedBy = st.longOrNull(13)
)

internal suspend fun Sql.note(id: String): Note? =
    one("SELECT $NOTE_COLUMNS FROM notes WHERE id = ?", id, row = ::noteRow)

/** Replaces the search entry and tags for a note. */
internal suspend fun Sql.index(id: String, title: String, content: String) {
    val seq = long("SELECT seq FROM notes WHERE id = ?", id)
    unindexSeq(seq)
    insertIndex(seq, title, content)
}

internal suspend fun Sql.unindex(id: String) {
    unindexSeq(long("SELECT seq FROM notes WHERE id = ?", id))
}

private suspend fun Sql.unindexSeq(seq: Long) {
    exec("DELETE FROM notes_fts WHERE rowid = ?", seq)
    exec("DELETE FROM note_tags WHERE note_seq = ?", seq)
}

private suspend fun Sql.insertIndex(seq: Long, title: String, stored: String) {
    // A locked body is ciphertext: only the title is searchable and tagged, as on the server (§10.1).
    val content = if (LockedEnvelope.isLocked(stored)) "" else stored
    exec("INSERT INTO notes_fts (rowid, title, body_text) VALUES (?, ?, ?)", seq, title, plainText(content))
    for (tag in tags(title, content)) {
        exec("INSERT INTO note_tags (note_seq, key, name) VALUES (?, ?, ?)", seq, tag.key, tag.name)
    }
}

/** Rebuilds the search index and tags from the notes. Sync state is untouched. */
internal suspend fun Sql.rebuildIndex(): Int {
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

/**
 * Queues [type] for [noteId], to [feed]: by default the feed of the space the payload names, or of the
 * note's (§7.9).
 */
internal suspend fun Sql.enqueue(noteId: String, type: String, payload: Payload?, now: Long, feed: String? = null) {
    val sent = payload?.copy(imageIds = lockedImageIds(payload.content))
    val to = feed ?: feedOf(payload?.spaceId ?: note(noteId)?.spaceId)
    exec(
        """
        INSERT INTO sync_outbox (operation_id, note_id, operation_type, base_revision, payload_json, created_at, feed)
        VALUES (?, ?, ?, NULL, ?, ?, ?)
        """,
        Uuid7.generate(),
        noteId,
        type,
        sent?.let { outboxJson.encodeToString(it) } ?: "{}",
        now,
        to
    )
}

internal class PendingOp(val operationId: String, val type: String, val payload: Payload, val feed: String)

/** The newest operation for [noteId] that hasn't been sent: the one later edits coalesce into. */
internal suspend fun Sql.unsentOp(noteId: String): PendingOp? = one(
    """
    SELECT operation_id, operation_type, payload_json, feed FROM sync_outbox
    WHERE note_id = ? AND sent = 0 ORDER BY created_at DESC, rowid DESC LIMIT 1
    """,
    noteId
) { PendingOp(it.getText(0), it.getText(1), outboxJson.decodeFromString(it.getText(2)), it.getText(3)) }

/** The feed a space's notes sync in (§7.9): a shared space's own, else the account's (''). */
internal suspend fun Sql.feedOf(spaceId: String?): String {
    if (spaceId == null) return ""
    val shared = one("SELECT shared IS NOT NULL FROM spaces WHERE id = ?", spaceId) { it.getLong(0) == 1L }
    return if (shared == true) spaceId else ""
}

/** Whether this device has the space, so its notes can follow a note moved there. */
internal suspend fun Sql.reaches(spaceId: String): Boolean =
    long("SELECT count(*) FROM spaces WHERE id = ? AND pending_delete = 0", spaceId) > 0

/**
 * The space the server holds [note] in once its queued changes have gone: its last queued move's destination,
 * else the space it last synced in, else what a create that went out named. Null if the server never had it.
 */
internal suspend fun Sql.serverSpace(note: Note): String? {
    val moved = one(
        """
        SELECT payload_json FROM sync_outbox WHERE note_id = ? AND operation_type = ?
        ORDER BY created_at DESC, rowid DESC LIMIT 1
        """,
        note.id,
        MOVE
    ) { outboxJson.decodeFromString<Payload>(it.getText(0)).spaceId }
    if (moved != null) return moved
    if (note.serverRevision != null) return note.baseSpaceId ?: note.spaceId
    return one(
        "SELECT payload_json FROM sync_outbox WHERE note_id = ? AND sent = 1 AND operation_type = ? LIMIT 1",
        note.id,
        CREATE
    ) { outboxJson.decodeFromString<Payload>(it.getText(0)).spaceId ?: Spaces.DEFAULT }
}

/**
 * Makes sure the server will receive [title]/[content] in [spaceId] for [noteId]: rewrites the unsent create
 * or update, or queues a new update (a sent payload is never changed). A space in another feed than the
 * server's copy (§7.9) queues the text where the server has it, then a move.
 */
internal suspend fun Sql.queueText(noteId: String, title: String, content: String, spaceId: String, now: Long) {
    val target = feedOf(spaceId)
    var pending = unsentOp(noteId)
    // Moved back before the move went: no move at all.
    if (pending != null && pending.type == MOVE && pending.feed == target) {
        exec("DELETE FROM sync_outbox WHERE operation_id = ?", pending.operationId)
        pending = unsentOp(noteId)
    }
    val server = note(noteId)?.let { serverSpace(it) }
    val open = pending?.takeIf { it.type != DELETE && it.type != MOVE }
    when {
        // The server never had it: its unsent create goes wherever it's filed now.
        server == null && open != null && open.type == CREATE -> rewrite(open, title, content, spaceId, now, target)

        open != null && open.feed == target && (server == null || feedOf(server) == target) ->
            rewrite(open, title, content, spaceId, now, target)

        server == null || feedOf(server) == target ->
            enqueue(noteId, UPDATE, Payload(title, content, updatedAt = now, spaceId = spaceId), now, target)

        else -> {
            val from = feedOf(server)
            if (open != null && open.type == UPDATE && open.feed == from) {
                rewrite(open, title, content, server, now, from)
            } else {
                enqueue(noteId, UPDATE, Payload(title, content, updatedAt = now, spaceId = server), now, from)
            }
            enqueue(noteId, MOVE, Payload(spaceId = spaceId), now, from)
        }
    }
}

private suspend fun Sql.rewrite(
    pending: PendingOp,
    title: String,
    content: String,
    spaceId: String,
    now: Long,
    feed: String
) {
    val payload = pending.payload.copy(
        title = title,
        content = content,
        updatedAt = now,
        imageIds = lockedImageIds(content),
        spaceId = spaceId
    )
    exec(
        "UPDATE sync_outbox SET payload_json = ?, feed = ? WHERE operation_id = ?",
        outboxJson.encodeToString(payload),
        feed,
        pending.operationId
    )
}

/**
 * A new note holding [title]/[content] in [spaceId], queued for upload. Conflicted and
 * recovered copies are made this way, so no text is ever dropped.
 */
internal suspend fun Sql.insertCopy(
    title: String,
    content: String,
    conflictOf: String?,
    spaceId: String,
    now: Long,
    imageIds: List<String>? = null,
    id: String = Uuid7.generate()
): String {
    rememberImages(id, content, imageIds ?: lockedImageIds(content))
    exec(
        """
        INSERT INTO notes (id, title, content, created_at, updated_at, conflict_of, space_id, dirty, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, 1, (SELECT user_id FROM sync_state WHERE id = 1))
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
internal suspend fun Sql.rememberImages(noteId: String, content: String, imageIds: List<String>?) {
    if (imageIds == null || !LockedEnvelope.isLocked(content)) return
    exec(
        "INSERT OR REPLACE INTO locked_images (note_id, content_sha, image_ids) VALUES (?, ?, ?)",
        noteId,
        sha256Hex(content.encodeToByteArray()),
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

/** An image reference's destination in Markdown (`](notesd:image/<id>?w=…&h=…)`): the id is group 1. */
internal val IMAGE_REFERENCE = Regex("\\]\\(notesd:image/([0-9a-f]{64})\\?w=[1-9]\\d{0,5}&h=[1-9]\\d{0,5}\\)")

/** The images a plain body shows, by id; none for a locked one (its saves list them). */
internal fun imageIdsIn(markdown: String): List<String> =
    IMAGE_REFERENCE.findAll(markdown).map { it.groupValues[1] }.distinct().toList()

/** A note gone from this device: its bodies' images aren't needed. */
internal suspend fun Sql.forgetImages(noteId: String) = exec("DELETE FROM locked_images WHERE note_id = ?", noteId)

/** The images in a locked body this device encrypted or synced; null for a plain body or an unknown one. */
internal suspend fun Sql.lockedImageIds(content: String): List<String>? {
    if (!LockedEnvelope.isLocked(content)) return null
    return one(
        "SELECT image_ids FROM locked_images WHERE content_sha = ? ORDER BY rowid DESC LIMIT 1",
        sha256Hex(content.encodeToByteArray())
    ) { outboxJson.decodeFromString<List<String>>(it.getText(0)) }
}
