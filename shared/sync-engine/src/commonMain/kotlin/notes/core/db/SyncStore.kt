package notes.core.db

import androidx.sqlite.SQLiteStatement
import notes.core.api.MutationResult
import notes.core.api.RemoteNote
import notes.core.api.SyncPage
import notes.core.crypto.LockedEnvelope
import notes.core.markdown.BodyMerge
import notes.core.text.Uuid7
import notes.core.text.nowMillis

/** Something sync did that the user should hear about. */
sealed interface SyncEvent {
    /** Both sides changed the note: [copyId] holds this device's version. */
    data class ConflictedCopy(val noteId: String, val copyId: String, val title: String) : SyncEvent

    /** Edited here, deleted elsewhere: [copyId] keeps the edits. */
    data class RecoveredCopy(val copyId: String, val title: String) : SyncEvent

    /** Both sides changed the body and it merged (§6.2); [MergeUndo] can split it back. */
    data class Merged(val noteId: String, val title: String) : SyncEvent

    /** Deleted here, edited elsewhere: the edit wins and the note is back. */
    data class Restored(val noteId: String, val title: String) : SyncEvent

    /** The server refused a change for good, so it was dropped; [message] says what and why. */
    data class Rejected(val message: String) : SyncEvent
}

data class SyncState(
    val cursor: Long,
    val generation: String?,
    val fullSync: Boolean,
    val lastSyncAt: Long?,
    val lastError: String?
)

/** An outbox entry ready to go out. Once [sent], everything here is frozen so a retry is byte-for-byte the same. */
class Operation internal constructor(
    val operationId: String,
    val noteId: String,
    val type: String,
    val baseRevision: Long?,
    internal val payload: Payload,
    val sent: Boolean
) {
    val title get() = payload.title
    val content get() = payload.content
    val createdAt get() = payload.createdAt
    val updatedAt get() = payload.updatedAt
    val conflictOf get() = payload.conflictOf
    val imageIds get() = payload.imageIds
    val spaceId get() = payload.spaceId
}

/**
 * The sync engine's side of the database. Every method is one transaction,
 * so a crash leaves either the old state or the new one (§6.3): a copy, its
 * upload and the original's new state commit together.
 */
class SyncStore(private val db: Database, private val clock: () -> Long = ::nowMillis) {
    /** Tests only: runs inside reconcile after a copy is built, before commit, to simulate a crash there. */
    internal var faultAfterCopy: (() -> Unit)? = null

    suspend fun state(): SyncState = db.read { sql ->
        sql.one("SELECT cursor, generation, full_sync, last_sync_at, last_error FROM sync_state WHERE id = 1") {
            SyncState(it.getLong(0), it.textOrNull(1), it.getLong(2) == 1L, it.longOrNull(3), it.textOrNull(4))
        }!!
    }

    // --- push ----------------------------------------------------------------

    /**
     * The oldest operation to send. A note waits whole (its later operations build on the earlier ones) while
     * one of its operations is parked, or shows a parked image, or names a parked new or renamed space: sent
     * now, its image would be missing, or the server would file it in the default space.
     */
    suspend fun nextOperation(): Operation? = db.read { sql ->
        val images = sql.query("SELECT id FROM pending_images WHERE parked = 1") { it.getText(0) }.toSet()
        val spaces = sql.query("SELECT id FROM spaces WHERE parked = 1 AND dirty = 1") { it.getText(0) }.toSet()
        val queued = sql.query(
            """
            SELECT operation_id, note_id, operation_type, base_revision, payload_json, sent, parked
            FROM sync_outbox ORDER BY created_at, rowid
            """
        ) { operationRow(it) to (it.getLong(6) == 1L) }
        val held = HashSet<String>()
        for ((op, parked) in queued) {
            if (op.noteId in held) continue
            val waits = parked || op.spaceId in spaces ||
                (images.isNotEmpty() && (imageIdsIn(op.content) + op.imageIds.orEmpty()).any { it in images })
            if (!waits) return@read op
            held += op.noteId
        }
        null
    }

    /**
     * [op] again under a new operationId, for a server that has the old one with a different body
     * (operation_id_reused). Safe: the base revision makes a duplicate a conflict or a no-op.
     */
    suspend fun remint(op: Operation): Operation = db.write { sql ->
        val id = Uuid7.generate()
        sql.exec("UPDATE sync_outbox SET operation_id = ? WHERE operation_id = ?", id, op.operationId)
        Operation(id, op.noteId, op.type, op.baseRevision, op.payload, op.sent)
    }

    /** [op] got an answer that wasn't notesd's: one more attempt, parked at the limit. */
    suspend fun attempted(op: Operation) {
        db.write { sql -> sql.attempted("sync_outbox", "operation_id = ?", op.operationId) }
    }

    /** Changes set aside after failing again and again: notes (by note), spaces and images. */
    suspend fun parked(): Int = db.read { sql ->
        sql.long(
            """
            SELECT (SELECT count(DISTINCT note_id) FROM sync_outbox WHERE parked = 1)
                + (SELECT count(*) FROM spaces WHERE parked = 1)
                + (SELECT count(*) FROM pending_images WHERE parked = 1)
            """
        ).toInt()
    }

    /** Every parked change back in line, one failure from parking again. */
    suspend fun unpark() {
        db.write { sql -> sql.unparkAll() }
    }

    /**
     * Queues a change for every note changed here that has none: older versions dropped some
     * the server refused, and the note kept the edit. Returns how many.
     */
    suspend fun requeueDirty(): Int = db.write { sql ->
        val stranded = sql.query(
            "SELECT $NOTE_COLUMNS FROM notes WHERE dirty = 1 AND id NOT IN (SELECT note_id FROM sync_outbox)",
            row = ::noteRow
        )
        val now = clock()
        for (note in stranded) {
            if (note.deletedAt != null) {
                sql.enqueue(note.id, DELETE, null, now)
            } else {
                sql.queueText(note.id, note.title, note.content, note.spaceId, now)
            }
        }
        stranded.size
    }

    /**
     * Freezes [op] for sending: fixes its base revision to what this device
     * last had from the server and marks it sent, before any request goes out.
     * An update for a note the server never got becomes a create; a delete of
     * one is simply finished locally. Null when nothing needs sending.
     */
    suspend fun prepare(op: Operation): Operation? = db.write { sql ->
        val current = sql.one(
            "SELECT operation_id, note_id, operation_type, base_revision, payload_json, sent FROM sync_outbox WHERE operation_id = ?",
            op.operationId,
            row = ::operationRow
        ) ?: return@write null
        if (current.sent) return@write current
        val note = sql.note(current.noteId)
        if (note == null) {
            sql.exec("DELETE FROM sync_outbox WHERE operation_id = ?", current.operationId)
            return@write null
        }
        when {
            current.type == CREATE -> sql.exec(
                "UPDATE sync_outbox SET sent = 1 WHERE operation_id = ?",
                current.operationId
            )

            note.serverRevision == null && current.type == DELETE -> {
                sql.exec("DELETE FROM sync_outbox WHERE note_id = ?", note.id)
                sql.exec("DELETE FROM notes WHERE id = ?", note.id)
                sql.forgetImages(note.id)
                return@write null
            }

            note.serverRevision == null -> {
                val payload = current.payload.copy(createdAt = note.createdAt, conflictOf = note.conflictOf)
                sql.exec(
                    "UPDATE sync_outbox SET operation_type = ?, payload_json = ?, sent = 1 WHERE operation_id = ?",
                    CREATE,
                    outboxJson.encodeToString(payload),
                    current.operationId
                )
            }

            else -> sql.exec(
                "UPDATE sync_outbox SET base_revision = ?, sent = 1 WHERE operation_id = ?",
                note.serverRevision,
                current.operationId
            )
        }
        sql.one(
            "SELECT operation_id, note_id, operation_type, base_revision, payload_json, sent FROM sync_outbox WHERE operation_id = ?",
            op.operationId,
            row = ::operationRow
        )
    }

    /** The server accepted [op]. */
    suspend fun sent(op: Operation, result: MutationResult) {
        db.write { sql ->
            sql.exec("DELETE FROM sync_outbox WHERE operation_id = ?", op.operationId)
            val note = sql.note(op.noteId) ?: return@write
            sql.exec(
                "UPDATE notes SET server_revision = ?, server_change_seq = ? WHERE id = ?",
                maxOf(result.revision, note.serverRevision ?: 0),
                result.changeSeq,
                note.id
            )
            val more = sql.long("SELECT count(*) FROM sync_outbox WHERE note_id = ?", note.id) > 0
            if (op.type == DELETE) {
                if (!more) markTombstone(sql, note.id, note.deletedAt ?: result.updatedAt, result.updatedAt)
                return@write
            }
            // The space the server filed it in: the default instead, if the one sent was deleted elsewhere (§7.7).
            val stored = result.spaceId ?: op.spaceId ?: note.spaceId
            val space = if (note.spaceId == (op.spaceId ?: note.spaceId)) stored else note.spaceId
            if (space != note.spaceId) sql.exec("UPDATE notes SET space_id = ? WHERE id = ?", space, note.id)
            if (!more && note.deletedAt == null && note.title == op.title && note.content == op.content &&
                space == stored
            ) {
                sql.exec(
                    """
                    UPDATE notes SET dirty = 0, base_title = NULL, base_content = NULL, base_deleted_at = NULL,
                        base_space_id = NULL
                    WHERE id = ?
                    """,
                    note.id
                )
            } else {
                // Edited while the request was out: still dirty, and the server now has what was sent.
                sql.exec(
                    "UPDATE notes SET base_title = ?, base_content = ?, base_deleted_at = NULL, base_space_id = ? WHERE id = ?",
                    op.title,
                    op.content,
                    stored,
                    note.id
                )
            }
        }
    }

    /**
     * The server refused [op] for good (invalid data): drop it rather than retry forever. The note keeps
     * the edit, and stays dirty; the engine tells the user.
     */
    suspend fun rejected(op: Operation) {
        db.write { sql -> sql.exec("DELETE FROM sync_outbox WHERE operation_id = ?", op.operationId) }
    }

    /** A delete the server answered not_found: it has no such note, which is what was asked. */
    suspend fun deleteDone(op: Operation) {
        db.write { sql ->
            sql.exec("DELETE FROM sync_outbox WHERE operation_id = ?", op.operationId)
            val note = sql.note(op.noteId) ?: return@write
            val more = sql.long("SELECT count(*) FROM sync_outbox WHERE note_id = ?", note.id) > 0
            if (!more) markTombstone(sql, note.id, note.deletedAt ?: clock(), note.updatedAt)
        }
    }

    /** [op] lost to a newer server version: the request recorded nothing, so drop it and reconcile with [current]. */
    suspend fun conflict(op: Operation, current: RemoteNote): List<SyncEvent> = db.write { sql ->
        sql.exec("DELETE FROM sync_outbox WHERE operation_id = ?", op.operationId)
        sql.rememberRemoteImages(current)
        buildList { reconcile(sql, current, this, fullSync = false) }
    }

    // --- pull ----------------------------------------------------------------

    /** Applies one page of the feed and stores the cursor it leads to, together. */
    suspend fun applyPage(page: SyncPage): List<SyncEvent> = db.write { sql ->
        val events = ArrayList<SyncEvent>()
        val fullSync = sql.long("SELECT full_sync FROM sync_state WHERE id = 1") == 1L
        for (remote in page.changes) {
            sql.rememberRemoteImages(remote)
            reconcile(sql, remote, events, fullSync)
            if (fullSync) sql.exec("UPDATE notes SET full_sync_seen = 1 WHERE id = ?", remote.id)
        }
        sql.exec("UPDATE sync_state SET cursor = ?, generation = ? WHERE id = 1", page.nextCursor, page.generation)
        if (fullSync && !page.hasMore) finishFullSync(sql)
        events
    }

    /**
     * The server was restored from a backup: pull everything again from the
     * start. What the full pull doesn't see is re-created on the server when
     * it ends; missing after a restore never means deleted.
     */
    suspend fun startFullSync() {
        db.write { sql ->
            sql.exec("UPDATE sync_state SET cursor = 0, generation = NULL, full_sync = 1 WHERE id = 1")
            sql.exec("UPDATE notes SET full_sync_seen = 0")
        }
    }

    private suspend fun finishFullSync(sql: Sql) {
        val now = clock()
        val missing = sql.query(
            "SELECT $NOTE_COLUMNS FROM notes WHERE full_sync_seen = 0 AND server_revision IS NOT NULL",
            row = ::noteRow
        )
        for (note in missing) {
            sql.exec("DELETE FROM sync_outbox WHERE note_id = ?", note.id)
            if (note.deletedAt != null) {
                // Deleted here, and the server doesn't have it either: nothing left to do.
                sql.unindex(note.id)
                sql.exec("DELETE FROM notes WHERE id = ?", note.id)
                sql.forgetImages(note.id)
                continue
            }
            sql.exec(
                """
                UPDATE notes SET server_revision = NULL, server_change_seq = NULL, dirty = 1,
                    base_title = NULL, base_content = NULL, base_deleted_at = NULL, base_space_id = NULL
                WHERE id = ?
                """,
                note.id
            )
            sql.enqueue(
                note.id,
                CREATE,
                Payload(
                    note.title,
                    note.content,
                    note.createdAt,
                    note.updatedAt,
                    note.conflictOf,
                    spaceId = note.spaceId
                ),
                now
            )
        }
        sql.exec("UPDATE sync_state SET full_sync = 0 WHERE id = 1")
    }

    suspend fun finished(error: String?) {
        db.write { sql ->
            sql.exec("UPDATE sync_state SET last_sync_at = ?, last_error = ? WHERE id = 1", clock(), error)
            sql.forgetOldMerges(clock())
        }
    }

    // --- reconcile (§4.6): the one routine for pushes that lost and pulled changes ---

    private suspend fun reconcile(sql: Sql, remote: RemoteNote, events: MutableList<SyncEvent>, fullSync: Boolean) {
        val local = sql.note(remote.id)
        if (local == null) {
            if (remote.deletedAt == null) overwrite(sql, null, remote)
            return
        }
        val known = local.serverRevision
        if (fullSync && known != null) {
            // After a restore, revisions from before it aren't comparable with the server's: text decides.
            if (!local.dirty && sameState(local, remote)) {
                // Only the space differs: a move holds no text, so no copy. Take the server's, unless it lost ours.
                // Both deleted, the space doesn't matter: overwriting would bring the note back.
                if (local.spaceId == remote.spaceId || remote.deletedAt != null) return adoptRevision(sql, remote)
                if (remote.revision >= known) return overwrite(sql, local, remote)
            }
            if (remote.revision < known) return serverLost(sql, local, remote, events)
            if (!local.dirty) {
                // Changed on the server since the restore, and this device holds a version it may have lost.
                if (local.deletedAt == null) {
                    val title = copyTitle(local.title, CONFLICTED)
                    events +=
                        SyncEvent.ConflictedCopy(
                            local.id,
                            sql.insertCopy(title, local.content, local.id, local.spaceId, clock()),
                            title
                        )
                }
                if (remote.deletedAt != null) tombstone(sql, remote) else overwrite(sql, local, remote)
                return
            }
        } else if (known != null && remote.revision <= known) {
            // Already have this version, or a newer one (our own change echoed back).
            return
        }
        val ops = sql.long("SELECT count(*) FROM sync_outbox WHERE note_id = ?", local.id)
        when {
            !local.dirty && ops == 0L -> if (remote.deletedAt !=
                null
            ) {
                tombstone(sql, remote)
            } else {
                overwrite(sql, local, remote)
            }

            local.deletedAt != null -> {
                dropOps(sql, local.id)
                if (remote.deletedAt != null) {
                    tombstone(sql, remote)
                } else {
                    overwrite(sql, local, remote)
                    events += SyncEvent.Restored(local.id, remote.title)
                }
            }

            remote.deletedAt != null -> {
                val title = copyTitle(local.title, RECOVERED)
                val copy = sql.insertCopy(title, local.content, null, local.spaceId, clock())
                faultAfterCopy?.invoke()
                dropOps(sql, local.id)
                tombstone(sql, remote)
                events += SyncEvent.RecoveredCopy(copy, title)
            }

            else -> merge(sql, local, remote, events)
        }
    }

    private suspend fun merge(sql: Sql, local: Note, remote: RemoteNote, events: MutableList<SyncEvent>) {
        // Undo splits the last merge only: this one may be a conflict that records none.
        sql.forgetMerge(local.id)
        // A note this device created has no synced version yet. If its create went out (and the answer was
        // lost), what it sent is what the server may have: merge against that.
        val sentCreate = if (local.baseTitle == null) {
            sql.one(
                "SELECT payload_json FROM sync_outbox WHERE note_id = ? AND sent = 1 AND operation_type = ? LIMIT 1",
                local.id,
                CREATE
            ) { outboxJson.decodeFromString<Payload>(it.getText(0)) }
        } else {
            null
        }
        val baseTitle = local.baseTitle ?: sentCreate?.title
        val baseContent = local.baseContent ?: sentCreate?.content
        val baseSpace = local.baseSpaceId ?: sentCreate?.spaceId
        val title = Merge.field(baseTitle, local.title, remote.title, Merge.sameText)
        val content = Merge.field(baseContent, local.content, remote.content, Merge.sameMarkdown)
        // Moved differently on both sides: the server's space wins; a move holds no text to keep (§6.1).
        val space = Merge.field(baseSpace, local.spaceId, remote.spaceId, Merge.sameText) ?: remote.spaceId
        val body = content?.let { BodyMerge.Clean(it) } ?: Merge.body(baseContent, local.content, remote.content)
        if (body is BodyMerge.Conflict) {
            val copyTitle = copyTitle(local.title, CONFLICTED)
            val copy = sql.insertCopy(copyTitle, local.content, local.id, local.spaceId, clock())
            faultAfterCopy?.invoke()
            dropOps(sql, local.id)
            overwrite(sql, local, remote)
            events += SyncEvent.ConflictedCopy(local.id, copy, copyTitle)
            return
        }
        if (title == null || body is BodyMerge.Partial) {
            // Both get every change that merged, so the two differ only where both sides changed the same thing.
            val (theirs, mine) = when (body) {
                is BodyMerge.Partial -> body.theirs to body.mine
                is BodyMerge.Clean -> body.markdown to body.markdown
            }
            val copyTitle = copyTitle(title ?: local.title, CONFLICTED)
            val copy = sql.insertCopy(copyTitle, mine, local.id, local.spaceId, clock())
            faultAfterCopy?.invoke()
            storeMerged(sql, local, remote, title ?: remote.title, theirs, space)
            events += SyncEvent.ConflictedCopy(local.id, copy, copyTitle)
            return
        }
        val merged = (body as BodyMerge.Clean).markdown
        storeMerged(sql, local, remote, title, merged, space)
        if (content == null) {
            sql.recordMerge(
                MergeUndo(local.id, remote.title, remote.content, local.title, local.content, title, merged, clock())
            )
            events += SyncEvent.Merged(local.id, title)
        }
    }

    /** The note becomes [title]/[content] in [space], based on [remote] and queued, unless that's just [remote]. */
    private suspend fun storeMerged(
        sql: Sql,
        local: Note,
        remote: RemoteNote,
        title: String,
        content: String,
        space: String
    ) {
        if (title == remote.title && Merge.sameMarkdown(content, remote.content) && space == remote.spaceId) {
            // Nothing of ours left to send.
            dropOps(sql, local.id)
            overwrite(sql, local, remote)
            return
        }
        sql.exec(
            """
            UPDATE notes SET title = ?, content = ?, space_id = ?, updated_at = ?, conflict_of = ?, server_revision = ?,
                server_change_seq = ?, dirty = 1, base_title = ?, base_content = ?, base_deleted_at = NULL,
                base_space_id = ?
            WHERE id = ?
            """,
            title,
            content,
            space,
            maxOf(local.updatedAt, remote.updatedAt),
            remote.conflictOf,
            remote.revision,
            remote.changeSeq,
            remote.title,
            remote.content,
            remote.spaceId,
            local.id
        )
        sql.index(local.id, title, content)
        sql.queueText(local.id, title, content, space, clock())
    }

    /** The same text (or both deleted); the space may still differ. */
    private fun sameState(local: Note, remote: RemoteNote): Boolean = when {
        local.deletedAt != null || remote.deletedAt != null -> (local.deletedAt != null) == (remote.deletedAt != null)
        else -> local.title == remote.title && Merge.sameMarkdown(local.content, remote.content)
    }

    private suspend fun adoptRevision(sql: Sql, remote: RemoteNote) {
        sql.exec(
            "UPDATE notes SET server_revision = ?, server_change_seq = ? WHERE id = ?",
            remote.revision,
            remote.changeSeq,
            remote.id
        )
    }

    /**
     * The restored server is behind this device: it lost this device's latest
     * version. Send it again, based on what the server has now.
     */
    private suspend fun serverLost(sql: Sql, local: Note, remote: RemoteNote, events: MutableList<SyncEvent>) {
        adoptRevision(sql, remote)
        when {
            local.deletedAt != null && remote.deletedAt != null -> {
                dropOps(sql, local.id)
                markTombstone(sql, local.id, local.deletedAt, local.updatedAt)
            }

            local.deletedAt != null -> {
                dropOps(sql, local.id)
                sql.exec("UPDATE notes SET dirty = 1 WHERE id = ?", local.id)
                sql.enqueue(local.id, DELETE, null, clock())
            }

            remote.deletedAt != null -> {
                // Can't happen for one note's history; still, keep the text rather than guess.
                val title = copyTitle(local.title, RECOVERED)
                val copy = sql.insertCopy(title, local.content, null, local.spaceId, clock())
                dropOps(sql, local.id)
                tombstone(sql, remote)
                events += SyncEvent.RecoveredCopy(copy, title)
            }

            else -> {
                sql.exec(
                    """
                    UPDATE notes SET dirty = 1, base_title = ?, base_content = ?, base_deleted_at = NULL,
                        base_space_id = ?
                    WHERE id = ?
                    """,
                    remote.title,
                    remote.content,
                    remote.spaceId,
                    local.id
                )
                sql.queueText(local.id, local.title, local.content, local.spaceId, clock())
            }
        }
    }

    /** Local becomes exactly the server's note, clean. */
    private suspend fun overwrite(sql: Sql, local: Note?, remote: RemoteNote) {
        if (local == null) {
            sql.exec(
                """
                INSERT INTO notes (id, title, content, created_at, updated_at, conflict_of, server_revision,
                    server_change_seq, space_id, dirty)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                remote.id,
                remote.title,
                remote.content,
                remote.createdAt,
                remote.updatedAt,
                remote.conflictOf,
                remote.revision,
                remote.changeSeq,
                remote.spaceId
            )
        } else {
            sql.exec(
                """
                UPDATE notes SET title = ?, content = ?, created_at = ?, updated_at = ?, deleted_at = NULL,
                    conflict_of = ?, server_revision = ?, server_change_seq = ?, space_id = ?, dirty = 0,
                    base_title = NULL, base_content = NULL, base_deleted_at = NULL, base_space_id = NULL
                WHERE id = ?
                """,
                remote.title,
                remote.content,
                remote.createdAt,
                remote.updatedAt,
                remote.conflictOf,
                remote.revision,
                remote.changeSeq,
                remote.spaceId,
                remote.id
            )
        }
        sql.index(remote.id, remote.title, remote.content)
        // Undo would keep the text from before the lock in plain text here.
        if (LockedEnvelope.isLocked(remote.content)) sql.forgetMerge(remote.id)
    }

    private suspend fun tombstone(sql: Sql, remote: RemoteNote) {
        sql.exec(
            "UPDATE notes SET server_revision = ?, server_change_seq = ? WHERE id = ?",
            remote.revision,
            remote.changeSeq,
            remote.id
        )
        markTombstone(sql, remote.id, remote.deletedAt ?: remote.updatedAt, remote.updatedAt)
    }

    /** Matches the server's tombstone: no text, out of the index, nothing pending. */
    private suspend fun markTombstone(sql: Sql, id: String, deletedAt: Long, updatedAt: Long) {
        sql.unindex(id)
        sql.forgetMerge(id)
        sql.exec(
            """
            UPDATE notes SET title = '', content = '', deleted_at = ?, updated_at = ?, dirty = 0,
                base_title = NULL, base_content = NULL, base_deleted_at = NULL, base_space_id = NULL
            WHERE id = ?
            """,
            deletedAt,
            updatedAt,
            id
        )
    }

    private suspend fun dropOps(sql: Sql, noteId: String) =
        sql.exec("DELETE FROM sync_outbox WHERE note_id = ?", noteId)

    private fun operationRow(st: SQLiteStatement) = Operation(
        st.getText(0),
        st.getText(1),
        st.getText(2),
        st.longOrNull(3),
        outboxJson.decodeFromString(st.getText(4)),
        st.getLong(5) == 1L
    )
}

/** A locked body from the server comes with its images: kept, so a copy this device makes of it lists them. */
private suspend fun Sql.rememberRemoteImages(remote: RemoteNote) {
    if (remote.deletedAt == null) rememberImages(remote.id, remote.content, remote.imageIds ?: emptyList())
}
