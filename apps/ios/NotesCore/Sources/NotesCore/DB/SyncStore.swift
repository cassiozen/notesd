import Foundation
import Synchronization

/// Something sync did that the user should hear about.
public enum SyncEvent: Hashable, Sendable {
    /// Both sides changed the note: `copyId` holds this device's version.
    case conflictedCopy(noteId: String, copyId: String, title: String)
    /// Edited here, deleted elsewhere: `copyId` keeps the edits.
    case recoveredCopy(copyId: String, title: String)
    /// Deleted here, edited elsewhere: the edit wins and the note is back.
    case restored(noteId: String, title: String)
}

public struct SyncState: Hashable, Sendable {
    public var cursor: Int64
    public var generation: String?
    public var fullSync: Bool
    public var lastSyncAt: Int64?
    public var lastError: String?
}

/// An outbox entry ready to go out. Once `sent`, everything here is frozen so a retry is byte-for-byte the same.
public struct Operation: Hashable, Sendable {
    public var operationId: String
    public var noteId: String
    public var type: String
    public var baseRevision: Int64?
    var payload: Payload
    public var sent: Bool

    public var title: String { payload.title }
    public var content: String { payload.content }
    public var spaceId: String? { payload.spaceId }
}

private let operationColumns = "operation_id, note_id, operation_type, base_revision, payload_json, sent"

private func operationRow(_ r: Row) throws -> Operation {
    Operation(
        operationId: r.text(0), noteId: r.text(1), type: r.text(2), baseRevision: r.int64OrNil(3),
        payload: try Payload.decode(r.text(4)), sent: r.int64(5) == 1)
}

/// The sync engine's side of the database. Every method is one transaction,
/// so a crash leaves either the old state or the new one (§6.3): a copy, its
/// upload and the original's new state commit together.
public final class SyncStore: Sendable {
    private let db: Database
    private let clock: Clock
    private let fault = Mutex<(@Sendable () throws -> Void)?>(nil)

    public init(db: Database, clock: @escaping Clock = systemClock) {
        self.db = db
        self.clock = clock
    }

    /// Tests only: runs inside reconcile after a copy is built, before commit, to simulate a crash there.
    var faultAfterCopy: (@Sendable () throws -> Void)? {
        get { fault.withLock { $0 } }
        set { fault.withLock { $0 = newValue } }
    }

    public func state() async throws -> SyncState {
        try await db.read { sql in
            try sql.one("SELECT cursor, generation, full_sync, last_sync_at, last_error FROM sync_state WHERE id = 1") {
                SyncState(
                    cursor: $0.int64(0), generation: $0.textOrNil(1), fullSync: $0.int64(2) == 1,
                    lastSyncAt: $0.int64OrNil(3), lastError: $0.textOrNil(4))
            }!
        }
    }

    // MARK: push

    public func nextOperation() async throws -> Operation? {
        try await db.read {
            try $0.one(
                "SELECT \(operationColumns) FROM sync_outbox ORDER BY created_at, rowid LIMIT 1", row: operationRow)
        }
    }

    /// Freezes `op` for sending: fixes its base revision to what this device
    /// last had from the server and marks it sent, before any request goes out.
    /// An update for a note the server never got becomes a create; a delete of
    /// one is simply finished locally. Nil when nothing needs sending.
    public func prepare(_ op: Operation) async throws -> Operation? {
        try await db.write { sql in
            let select = "SELECT \(operationColumns) FROM sync_outbox WHERE operation_id = ?"
            guard let current = try sql.one(select, op.operationId, row: operationRow) else { return nil }
            if current.sent { return current }
            guard let note = try sql.note(current.noteId) else {
                try sql.exec("DELETE FROM sync_outbox WHERE operation_id = ?", current.operationId)
                return nil
            }
            if current.type == createOp {
                try sql.exec("UPDATE sync_outbox SET sent = 1 WHERE operation_id = ?", current.operationId)
            } else if note.serverRevision == nil && current.type == deleteOp {
                try sql.exec("DELETE FROM sync_outbox WHERE note_id = ?", note.id)
                try sql.exec("DELETE FROM notes WHERE id = ?", note.id)
                try sql.forgetImages(note.id)
                return nil
            } else if note.serverRevision == nil {
                var payload = current.payload
                payload.createdAt = note.createdAt
                payload.conflictOf = note.conflictOf
                try sql.exec(
                    "UPDATE sync_outbox SET operation_type = ?, payload_json = ?, sent = 1 WHERE operation_id = ?",
                    createOp, payload.json, current.operationId)
            } else {
                try sql.exec(
                    "UPDATE sync_outbox SET base_revision = ?, sent = 1 WHERE operation_id = ?", note.serverRevision,
                    current.operationId)
            }
            return try sql.one(select, op.operationId, row: operationRow)
        }
    }

    /// The server accepted `op`.
    public func sent(_ op: Operation, _ result: MutationResult) async throws {
        try await db.write { sql in
            try sql.exec("DELETE FROM sync_outbox WHERE operation_id = ?", op.operationId)
            guard let note = try sql.note(op.noteId) else { return }
            try sql.exec(
                "UPDATE notes SET server_revision = ?, server_change_seq = ? WHERE id = ?",
                max(result.revision, note.serverRevision ?? 0), result.changeSeq, note.id)
            let more = try sql.int64("SELECT count(*) FROM sync_outbox WHERE note_id = ?", note.id) > 0
            if op.type == deleteOp {
                if !more {
                    try markTombstone(
                        sql, note.id, deletedAt: note.deletedAt ?? result.updatedAt, updatedAt: result.updatedAt)
                }
                return
            }
            // The space the server filed it in: the default instead, if the one sent was deleted elsewhere (§7.7).
            let stored = result.spaceId ?? op.spaceId ?? note.spaceId
            let space = note.spaceId == (op.spaceId ?? note.spaceId) ? stored : note.spaceId
            if space != note.spaceId { try sql.exec("UPDATE notes SET space_id = ? WHERE id = ?", space, note.id) }
            if !more && note.deletedAt == nil && note.title.same(as: op.title) && note.content.same(as: op.content)
                && space == stored
            {
                try sql.exec(
                    """
                    UPDATE notes SET dirty = 0, base_title = NULL, base_content = NULL, base_deleted_at = NULL,
                        base_space_id = NULL
                    WHERE id = ?
                    """,
                    note.id)
            } else {
                // Edited while the request was out: still dirty, and the server now has what was sent.
                try sql.exec(
                    "UPDATE notes SET base_title = ?, base_content = ?, base_deleted_at = NULL, base_space_id = ? WHERE id = ?",
                    op.title, op.content, stored, note.id)
            }
        }
    }

    /// The server refused `op` for good (invalid data): drop it rather than retry forever.
    public func rejected(_ op: Operation, message: String) async throws {
        try await db.write { sql in
            try sql.exec("DELETE FROM sync_outbox WHERE operation_id = ?", op.operationId)
            try sql.exec("UPDATE sync_state SET last_error = ? WHERE id = 1", message)
        }
    }

    /// `op` lost to a newer server version: the request recorded nothing, so drop it and reconcile with `current`.
    public func conflict(_ op: Operation, current: RemoteNote) async throws -> [SyncEvent] {
        try await db.write { sql in
            try sql.exec("DELETE FROM sync_outbox WHERE operation_id = ?", op.operationId)
            try sql.rememberRemoteImages(current)
            var events: [SyncEvent] = []
            try self.reconcile(sql, current, &events, fullSync: false)
            return events
        }
    }

    // MARK: pull

    /// Applies one page of the feed and stores the cursor it leads to, together.
    public func applyPage(_ page: SyncPage) async throws -> [SyncEvent] {
        try await db.write { sql in
            var events: [SyncEvent] = []
            let fullSync = try sql.int64("SELECT full_sync FROM sync_state WHERE id = 1") == 1
            for remote in page.changes {
                try sql.rememberRemoteImages(remote)
                try self.reconcile(sql, remote, &events, fullSync: fullSync)
                if fullSync { try sql.exec("UPDATE notes SET full_sync_seen = 1 WHERE id = ?", remote.id) }
            }
            try sql.exec(
                "UPDATE sync_state SET cursor = ?, generation = ? WHERE id = 1", page.nextCursor, page.generation)
            if fullSync && !page.hasMore { try self.finishFullSync(sql) }
            return events
        }
    }

    /// The server was restored from a backup: pull everything again from the
    /// start. What the full pull doesn't see is re-created on the server when
    /// it ends; missing after a restore never means deleted.
    public func startFullSync() async throws {
        try await db.write { sql in
            try sql.exec("UPDATE sync_state SET cursor = 0, generation = NULL, full_sync = 1 WHERE id = 1")
            try sql.exec("UPDATE notes SET full_sync_seen = 0")
        }
    }

    private func finishFullSync(_ sql: Sql) throws {
        let now = clock()
        let missing = try sql.query(
            "SELECT \(noteColumns) FROM notes WHERE full_sync_seen = 0 AND server_revision IS NOT NULL", row: noteRow)
        for note in missing {
            try sql.exec("DELETE FROM sync_outbox WHERE note_id = ?", note.id)
            if note.deletedAt != nil {
                // Deleted here, and the server doesn't have it either: nothing left to do.
                try sql.unindex(note.id)
                try sql.exec("DELETE FROM notes WHERE id = ?", note.id)
                try sql.forgetImages(note.id)
                continue
            }
            try sql.exec(
                """
                UPDATE notes SET server_revision = NULL, server_change_seq = NULL, dirty = 1,
                    base_title = NULL, base_content = NULL, base_deleted_at = NULL, base_space_id = NULL
                WHERE id = ?
                """,
                note.id)
            let payload = Payload(
                title: note.title, content: note.content, createdAt: note.createdAt, updatedAt: note.updatedAt,
                conflictOf: note.conflictOf, spaceId: note.spaceId)
            try sql.enqueue(note.id, type: createOp, payload: payload, now: now)
        }
        try sql.exec("UPDATE sync_state SET full_sync = 0 WHERE id = 1")
    }

    public func finished(error: String?) async throws {
        let now = clock()
        try await db.write { sql in
            try sql.exec("UPDATE sync_state SET last_sync_at = ?, last_error = ? WHERE id = 1", now, error)
        }
    }

    // MARK: reconcile (§4.6): the one routine for pushes that lost and pulled changes

    private func reconcile(_ sql: Sql, _ remote: RemoteNote, _ events: inout [SyncEvent], fullSync: Bool) throws {
        guard let local = try sql.note(remote.id) else {
            if remote.deletedAt == nil { try overwrite(sql, nil, remote) }
            return
        }
        let known = local.serverRevision
        if fullSync, let known {
            // After a restore, revisions from before it aren't comparable with the server's: text decides.
            if !local.dirty && sameState(local, remote) {
                // Only the space differs: a move holds no text, so no copy. Take the server's, unless it lost ours.
                // Both deleted, the space doesn't matter: overwriting would bring the note back.
                if local.spaceId == remote.spaceId || remote.deletedAt != nil { return try adoptRevision(sql, remote) }
                if remote.revision >= known { return try overwrite(sql, local, remote) }
            }
            if remote.revision < known { return try serverLost(sql, local, remote, &events) }
            if !local.dirty {
                // Changed on the server since the restore, and this device holds a version it may have lost.
                if local.deletedAt == nil {
                    let title = copyTitle(local.title, conflictedSuffix)
                    let copy = try sql.insertCopy(
                        title: title, content: local.content, conflictOf: local.id, spaceId: local.spaceId, now: clock()
                    )
                    events.append(.conflictedCopy(noteId: local.id, copyId: copy, title: title))
                }
                if remote.deletedAt != nil { try tombstone(sql, remote) } else { try overwrite(sql, local, remote) }
                return
            }
        } else if let known, remote.revision <= known {
            // Already have this version, or a newer one (our own change echoed back).
            return
        }
        let ops = try sql.int64("SELECT count(*) FROM sync_outbox WHERE note_id = ?", local.id)
        if !local.dirty && ops == 0 {
            if remote.deletedAt != nil { try tombstone(sql, remote) } else { try overwrite(sql, local, remote) }
        } else if local.deletedAt != nil {
            try dropOps(sql, local.id)
            if remote.deletedAt != nil {
                try tombstone(sql, remote)
            } else {
                try overwrite(sql, local, remote)
                events.append(.restored(noteId: local.id, title: remote.title))
            }
        } else if remote.deletedAt != nil {
            let title = copyTitle(local.title, recoveredSuffix)
            let copy = try sql.insertCopy(
                title: title, content: local.content, conflictOf: nil, spaceId: local.spaceId, now: clock())
            try faultAfterCopy?()
            try dropOps(sql, local.id)
            try tombstone(sql, remote)
            events.append(.recoveredCopy(copyId: copy, title: title))
        } else {
            try merge(sql, local, remote, &events)
        }
    }

    private func merge(_ sql: Sql, _ local: Note, _ remote: RemoteNote, _ events: inout [SyncEvent]) throws {
        // A note this device created has no synced version yet. If its create went out (and the answer was
        // lost), what it sent is what the server may have: merge against that.
        var sentCreate: Payload?
        if local.baseTitle == nil {
            sentCreate = try sql.one(
                "SELECT payload_json FROM sync_outbox WHERE note_id = ? AND sent = 1 AND operation_type = ? LIMIT 1",
                local.id, createOp
            ) { try Payload.decode($0.text(0)) }
        }
        let baseTitle = local.baseTitle ?? sentCreate?.title
        let baseContent = local.baseContent ?? sentCreate?.content
        let baseSpace = local.baseSpaceId ?? sentCreate?.spaceId
        let title = Merge.field(base: baseTitle, mine: local.title, theirs: remote.title, same: Merge.sameText)
        let content = Merge.field(
            base: baseContent, mine: local.content, theirs: remote.content, same: Merge.sameMarkdown)
        // Moved differently on both sides: the server's space wins; a move holds no text to keep (§6.1).
        let space =
            Merge.field(base: baseSpace, mine: local.spaceId, theirs: remote.spaceId, same: Merge.sameText)
            ?? remote.spaceId
        guard let title, let content else {
            let copyTitle = copyTitle(local.title, conflictedSuffix)
            let copy = try sql.insertCopy(
                title: copyTitle, content: local.content, conflictOf: local.id, spaceId: local.spaceId, now: clock())
            try faultAfterCopy?()
            try dropOps(sql, local.id)
            try overwrite(sql, local, remote)
            events.append(.conflictedCopy(noteId: local.id, copyId: copy, title: copyTitle))
            return
        }
        if title.same(as: remote.title) && Merge.sameMarkdown(content, remote.content) && space == remote.spaceId {
            // Nothing of ours left to send.
            try dropOps(sql, local.id)
            try overwrite(sql, local, remote)
            return
        }
        try sql.exec(
            """
            UPDATE notes SET title = ?, content = ?, space_id = ?, updated_at = ?, conflict_of = ?, server_revision = ?,
                server_change_seq = ?, dirty = 1, base_title = ?, base_content = ?, base_deleted_at = NULL,
                base_space_id = ?
            WHERE id = ?
            """,
            title, content, space, max(local.updatedAt, remote.updatedAt), remote.conflictOf, remote.revision,
            remote.changeSeq, remote.title, remote.content, remote.spaceId, local.id)
        try sql.index(local.id, title: title, content: content)
        try sql.queueText(local.id, title: title, content: content, spaceId: space, now: clock())
    }

    /// The same text (or both deleted); the space may still differ.
    private func sameState(_ local: Note, _ remote: RemoteNote) -> Bool {
        if local.deletedAt != nil || remote.deletedAt != nil {
            return (local.deletedAt != nil) == (remote.deletedAt != nil)
        }
        return local.title.same(as: remote.title) && Merge.sameMarkdown(local.content, remote.content)
    }

    private func adoptRevision(_ sql: Sql, _ remote: RemoteNote) throws {
        try sql.exec(
            "UPDATE notes SET server_revision = ?, server_change_seq = ? WHERE id = ?", remote.revision,
            remote.changeSeq, remote.id)
    }

    /// The restored server is behind this device: it lost this device's latest
    /// version. Send it again, based on what the server has now.
    private func serverLost(_ sql: Sql, _ local: Note, _ remote: RemoteNote, _ events: inout [SyncEvent]) throws {
        try adoptRevision(sql, remote)
        if let deletedAt = local.deletedAt, remote.deletedAt != nil {
            try dropOps(sql, local.id)
            try markTombstone(sql, local.id, deletedAt: deletedAt, updatedAt: local.updatedAt)
        } else if local.deletedAt != nil {
            try dropOps(sql, local.id)
            try sql.exec("UPDATE notes SET dirty = 1 WHERE id = ?", local.id)
            try sql.enqueue(local.id, type: deleteOp, payload: nil, now: clock())
        } else if remote.deletedAt != nil {
            // Can't happen for one note's history; still, keep the text rather than guess.
            let title = copyTitle(local.title, recoveredSuffix)
            let copy = try sql.insertCopy(
                title: title, content: local.content, conflictOf: nil, spaceId: local.spaceId, now: clock())
            try dropOps(sql, local.id)
            try tombstone(sql, remote)
            events.append(.recoveredCopy(copyId: copy, title: title))
        } else {
            try sql.exec(
                """
                UPDATE notes SET dirty = 1, base_title = ?, base_content = ?, base_deleted_at = NULL, base_space_id = ?
                WHERE id = ?
                """,
                remote.title, remote.content, remote.spaceId, local.id)
            try sql.queueText(
                local.id, title: local.title, content: local.content, spaceId: local.spaceId, now: clock())
        }
    }

    /// Local becomes exactly the server's note, clean.
    private func overwrite(_ sql: Sql, _ local: Note?, _ remote: RemoteNote) throws {
        if local == nil {
            try sql.exec(
                """
                INSERT INTO notes (id, title, content, created_at, updated_at, conflict_of, server_revision,
                    server_change_seq, space_id, dirty)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                remote.id, remote.title, remote.content, remote.createdAt, remote.updatedAt, remote.conflictOf,
                remote.revision, remote.changeSeq, remote.spaceId)
        } else {
            try sql.exec(
                """
                UPDATE notes SET title = ?, content = ?, created_at = ?, updated_at = ?, deleted_at = NULL,
                    conflict_of = ?, server_revision = ?, server_change_seq = ?, space_id = ?, dirty = 0,
                    base_title = NULL, base_content = NULL, base_deleted_at = NULL, base_space_id = NULL
                WHERE id = ?
                """,
                remote.title, remote.content, remote.createdAt, remote.updatedAt, remote.conflictOf, remote.revision,
                remote.changeSeq, remote.spaceId, remote.id)
        }
        try sql.index(remote.id, title: remote.title, content: remote.content)
    }

    private func tombstone(_ sql: Sql, _ remote: RemoteNote) throws {
        try adoptRevision(sql, remote)
        try markTombstone(sql, remote.id, deletedAt: remote.deletedAt ?? remote.updatedAt, updatedAt: remote.updatedAt)
    }

    /// Matches the server's tombstone: no text, out of the index, nothing pending.
    private func markTombstone(_ sql: Sql, _ id: String, deletedAt: Int64, updatedAt: Int64) throws {
        try sql.unindex(id)
        try sql.forgetImages(id)
        try sql.exec(
            """
            UPDATE notes SET title = '', content = '', deleted_at = ?, updated_at = ?, dirty = 0,
                base_title = NULL, base_content = NULL, base_deleted_at = NULL, base_space_id = NULL
            WHERE id = ?
            """,
            deletedAt, updatedAt, id)
    }

    private func dropOps(_ sql: Sql, _ noteId: String) throws {
        try sql.exec("DELETE FROM sync_outbox WHERE note_id = ?", noteId)
    }
}

extension Sql {
    /// A locked body from the server comes with its images: kept, so a copy this device makes of it lists them.
    fileprivate func rememberRemoteImages(_ remote: RemoteNote) throws {
        if remote.deletedAt == nil {
            try rememberImages(remote.id, content: remote.content, imageIds: remote.imageIds ?? [])
        }
    }
}
