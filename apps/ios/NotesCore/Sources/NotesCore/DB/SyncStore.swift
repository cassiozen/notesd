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
    /// Both sides changed the body and it merged (§6.2); `MergeUndo` can split it back.
    case merged(noteId: String, title: String)
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

/// Unexpected answers a change gets before it's parked.
let maxAttempts = 3

extension Sql {
    /// Notes sync holds back whole, so their operations still go in order: one with a parked
    /// operation, one filed in a parked new space (the server would file it in the default), or
    /// one showing a parked image (its bytes must reach the server before anything referencing them).
    fileprivate func heldNotes() throws -> Set<String> {
        var held = Set(try query("SELECT note_id FROM sync_outbox WHERE parked = 1") { $0.text(0) })
        let spaces = Set(try query("SELECT id FROM spaces WHERE parked = 1 AND dirty = 1") { $0.text(0) })
        let images = Set(try query("SELECT id FROM pending_images WHERE parked = 1") { $0.text(0) })
        if spaces.isEmpty && images.isEmpty { return held }
        for (noteId, json) in try query(
            "SELECT note_id, payload_json FROM sync_outbox", row: { ($0.text(0), $0.text(1)) })
        where !held.contains(noteId) {
            guard let payload = try? Payload.decode(json) else { continue }
            let shown = Set(ImageRef.ids(payload.content)).union(payload.imageIds ?? [])
            if payload.spaceId.map(spaces.contains) == true || !shown.isDisjoint(with: images) { held.insert(noteId) }
        }
        return held
    }
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

    /// The account's feed's state, or (`feed`) a shared space's, with the account's last sync and error.
    public func state(feed: String = "") async throws -> SyncState {
        try await db.read { sql in
            var own = try sql.one(
                "SELECT cursor, generation, full_sync, last_sync_at, last_error FROM sync_state WHERE id = 1"
            ) {
                SyncState(
                    cursor: $0.int64(0), generation: $0.textOrNil(1), fullSync: $0.int64(2) == 1,
                    lastSyncAt: $0.int64OrNil(3), lastError: $0.textOrNil(4))
            }!
            if feed.isEmpty { return own }
            let row = try sql.one("SELECT cursor, generation, full_sync FROM feeds WHERE space_id = ?", feed) {
                ($0.int64(0), $0.textOrNil(1), $0.int64(2) == 1)
            }
            (own.cursor, own.generation, own.fullSync) = row ?? (0, nil, false)
            return own
        }
    }

    /// The signed-in account's id, so its own edits are told from other members' (§7.9).
    public func setUserId(_ id: Int64) async throws {
        try await db.write { sql in
            try sql.exec("UPDATE sync_state SET user_id = ? WHERE id = 1", id > 0 ? id : nil)
        }
    }

    public func userId() async throws -> Int64? {
        try await db.read { sql in
            try sql.one("SELECT user_id FROM sync_state WHERE id = 1") { $0.int64OrNil(0) } ?? nil
        }
    }

    /// The shared spaces' feeds, as the space list last made them.
    public func feeds() async throws -> [String] {
        try await db.read { sql in try sql.query("SELECT space_id FROM feeds ORDER BY space_id") { $0.text(0) } }
    }

    /// A feed for each shared space in the list, and none for one no longer in it (left, removed, deleted): its
    /// notes and merge records leave the device (§7.9), but an edit not yet synced becomes a recovered copy in the
    /// default space first, since the space may be gone for reasons this device never warned about. A new feed
    /// counts as seen now, so what was there before this device joined isn't news.
    @discardableResult
    public func syncFeeds() async throws -> [SyncEvent] {
        let now = clock()
        return try await db.write { sql in
            var events: [SyncEvent] = []
            try sql.exec("INSERT OR IGNORE INTO feeds (space_id) SELECT id FROM spaces WHERE shared IS NOT NULL")
            try sql.exec("UPDATE spaces SET visited_at = ? WHERE shared IS NOT NULL AND visited_at IS NULL", now)
            let gone = try sql.query(
                "SELECT space_id FROM feeds WHERE space_id NOT IN (SELECT id FROM spaces WHERE shared IS NOT NULL)"
            ) { $0.text(0) }
            for feed in gone {
                for note in try sql.query("SELECT \(noteColumns) FROM notes WHERE space_id = ?", feed, row: noteRow) {
                    // Moved into it here, the move not yet sent: the server still has it where it was, so it goes
                    // back.
                    if let back = note.baseSpaceId, note.serverRevision != nil, back != feed, !gone.contains(back),
                        try sql.reaches(back)
                    {
                        try sql.exec("UPDATE notes SET space_id = ? WHERE id = ?", back, note.id)
                        if note.deletedAt == nil {
                            try sql.queueText(
                                note.id, title: note.title, content: note.content, spaceId: back, now: now)
                        }
                        continue
                    }
                    if note.dirty && note.deletedAt == nil {
                        let title = copyTitle(note.title, recoveredSuffix)
                        let copy = try sql.insertCopy(
                            title: title, content: note.content, conflictOf: nil, spaceId: Spaces.defaultId, now: now)
                        events.append(.recoveredCopy(copyId: copy, title: title))
                    }
                    try sql.unindex(note.id)
                    try sql.forgetMerge(note.id)
                    try sql.forgetImages(note.id)
                    try sql.exec("DELETE FROM sync_outbox WHERE note_id = ?", note.id)
                    try sql.exec("DELETE FROM notes WHERE id = ?", note.id)
                }
                // Moved out of it here, the move not yet sent: the server's copy is out of reach now, so the note
                // goes to the server again, as new, where it is.
                let strays = try sql.query(
                    """
                    SELECT \(noteColumns) FROM notes
                    WHERE base_space_id = ? AND space_id != ? AND server_revision IS NOT NULL
                    """, feed, feed, row: noteRow)
                for note in strays {
                    try sql.exec("DELETE FROM sync_outbox WHERE note_id = ?", note.id)
                    if note.deletedAt != nil {
                        try sql.exec("DELETE FROM notes WHERE id = ?", note.id)
                        continue
                    }
                    try sql.exec(
                        """
                        UPDATE notes SET server_revision = NULL, server_change_seq = NULL, dirty = 1, base_title = NULL,
                            base_content = NULL, base_deleted_at = NULL, base_space_id = NULL
                        WHERE id = ?
                        """,
                        note.id)
                    let payload = Payload(
                        title: note.title, content: note.content, createdAt: note.createdAt,
                        updatedAt: note.updatedAt, conflictOf: note.conflictOf, spaceId: note.spaceId)
                    try sql.enqueue(note.id, type: createOp, payload: payload, now: now)
                }
                try sql.exec("DELETE FROM feeds WHERE space_id = ?", feed)
            }
            return events
        }
    }

    // MARK: push

    /// The oldest operation of a note that isn't held (see `heldNotes`).
    public func nextOperation() async throws -> Operation? {
        try await db.read { sql in
            let held = try sql.heldNotes()
            // The outbox is small: read whole rather than built into a query.
            return try sql.query(
                "SELECT \(operationColumns) FROM sync_outbox ORDER BY created_at, rowid", row: operationRow
            ).first { !held.contains($0.noteId) }
        }
    }

    /// Counts an unexpected answer against `op`; the third parks it (see 0006_parking.sql).
    public func attempted(_ op: Operation) async throws {
        try await db.write { sql in
            try sql.exec(
                "UPDATE sync_outbox SET attempts = attempts + 1, parked = attempts + 1 >= ? WHERE operation_id = ?",
                maxAttempts, op.operationId)
        }
    }

    /// `op` again under a new operationId: the server had the old one for another request.
    public func remint(_ op: Operation) async throws -> Operation {
        let id = Uuid7.generate()
        try await db.write { sql in
            try sql.exec("UPDATE sync_outbox SET operation_id = ? WHERE operation_id = ?", id, op.operationId)
        }
        var next = op
        next.operationId = id
        return next
    }

    /// Parked changes (operations, spaces, images) get one more try: a failure parks them again at once.
    public func unpark() async throws {
        try await db.write { sql in
            for table in ["sync_outbox", "spaces", "pending_images"] {
                try sql.exec("UPDATE \(table) SET parked = 0, attempts = ? WHERE parked = 1", maxAttempts - 1)
            }
        }
    }

    /// Notes and spaces held back by parking.
    public func parkedCount() async throws -> Int {
        try await db.read { sql in
            try sql.heldNotes().count + Int(try sql.int64("SELECT count(*) FROM spaces WHERE parked = 1"))
        }
    }

    /// Queues the text of every note edited here that has nothing queued: an older app could drop a
    /// change it shouldn't have, leaving the note dirty with nothing to send it.
    public func requeueDirty() async throws {
        let clock = clock
        try await db.write { sql in
            let orphans = try sql.query(
                """
                SELECT \(noteColumns) FROM notes n
                WHERE n.dirty = 1 AND NOT EXISTS (SELECT 1 FROM sync_outbox o WHERE o.note_id = n.id)
                """, row: noteRow)
            for note in orphans {
                if note.deletedAt != nil {
                    try sql.enqueue(note.id, type: deleteOp, payload: nil, now: clock())
                } else {
                    try sql.queueText(
                        note.id, title: note.title, content: note.content, spaceId: note.spaceId, now: clock())
                }
            }
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

    /// A delete the server answered not_found for: it doesn't have the note either, so it's done.
    public func gone(_ op: Operation) async throws {
        try await db.write { sql in
            try sql.exec("DELETE FROM sync_outbox WHERE operation_id = ?", op.operationId)
            guard let note = try sql.note(op.noteId), note.deletedAt != nil else { return }
            if try sql.int64("SELECT count(*) FROM sync_outbox WHERE note_id = ?", note.id) == 0 {
                try self.markTombstone(sql, note.id, deletedAt: note.deletedAt!, updatedAt: note.updatedAt)
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

    /// The server refused `op` because it would put a locked body in a shared space (§7.9): locked offline, then
    /// shared from another device. The locked version becomes a recovered copy in the default space, where it can
    /// be locked, and the note goes back to the server's version, as the space's members have it.
    public func lockedOut(_ op: Operation) async throws -> SyncEvent? {
        let now = clock()
        return try await db.write { sql in
            guard let note = try sql.note(op.noteId) else { return nil }
            let title = copyTitle(note.title, recoveredSuffix)
            let copy = try sql.insertCopy(
                title: title, content: note.content, conflictOf: nil, spaceId: Spaces.defaultId, now: now)
            try self.dropOps(sql, note.id)
            if note.serverRevision == nil {
                // The server never had it: the copy is all of it.
                try sql.unindex(note.id)
                try sql.forgetImages(note.id)
                try sql.exec("DELETE FROM notes WHERE id = ?", note.id)
            } else {
                let title0 = note.baseTitle ?? note.title
                let content0 = note.baseContent ?? note.content
                try sql.exec(
                    """
                    UPDATE notes SET title = ?, content = ?, space_id = COALESCE(base_space_id, space_id), dirty = 0,
                        base_title = NULL, base_content = NULL, base_deleted_at = NULL, base_space_id = NULL
                    WHERE id = ?
                    """,
                    title0, content0, note.id)
                try sql.index(note.id, title: title0, content: content0)
            }
            return .recoveredCopy(copyId: copy, title: title)
        }
    }

    /// `op` lost to a newer server version: the request recorded nothing, so drop it and reconcile with `current`.
    public func conflict(_ op: Operation, current: RemoteNote) async throws -> [SyncEvent] {
        try await db.write { sql in
            try sql.exec("DELETE FROM sync_outbox WHERE operation_id = ?", op.operationId)
            try sql.rememberRemoteImages(current)
            var events: [SyncEvent] = []
            // The feed the server's version came from: wherever the note is now, which the server found.
            try self.reconcile(sql, current, &events, fullSync: false, feed: try sql.feedOf(current.spaceId))
            return events
        }
    }

    // MARK: pull

    /// Applies one page of `feed` (the account's, or a shared space's) and stores the cursor it leads to, together.
    public func applyPage(_ page: SyncPage, feed: String = "") async throws -> [SyncEvent] {
        try await db.write { sql in
            var events: [SyncEvent] = []
            let fullSync =
                feed.isEmpty
                ? try sql.int64("SELECT full_sync FROM sync_state WHERE id = 1") == 1
                : try sql.int64("SELECT count(*) FROM feeds WHERE space_id = ? AND full_sync = 1", feed) > 0
            for remote in page.changes {
                try sql.rememberRemoteImages(remote)
                try self.reconcile(sql, remote, &events, fullSync: fullSync, feed: feed)
                if fullSync { try sql.exec("UPDATE notes SET full_sync_seen = 1 WHERE id = ?", remote.id) }
            }
            if feed.isEmpty {
                try sql.exec(
                    "UPDATE sync_state SET cursor = ?, generation = ? WHERE id = 1", page.nextCursor, page.generation)
            } else {
                try sql.exec(
                    "UPDATE feeds SET cursor = ?, generation = ? WHERE space_id = ?", page.nextCursor, page.generation,
                    feed)
            }
            if fullSync && !page.hasMore { try self.finishFullSync(sql, feed: feed) }
            return events
        }
    }

    /// The server was restored from a backup: pull everything again from the
    /// start. What the full pull doesn't see is re-created on the server when
    /// it ends; missing after a restore never means deleted. Per feed: a shared
    /// space's file is restored on its own.
    public func startFullSync(feed: String = "") async throws {
        try await db.write { sql in
            if feed.isEmpty {
                try sql.exec("UPDATE sync_state SET cursor = 0, generation = NULL, full_sync = 1 WHERE id = 1")
            } else {
                try sql.exec(
                    "UPDATE feeds SET cursor = 0, generation = NULL, full_sync = 1 WHERE space_id = ?", feed)
            }
            let (inFeed, args) = Self.notesIn(feed)
            try sql.execArray("UPDATE notes SET full_sync_seen = 0 WHERE \(inFeed)", args)
        }
    }

    /// The notes of `feed`: a shared space's, or the account's (every space that isn't shared).
    private static func notesIn(_ feed: String) -> (String, [(any SQLValue)?]) {
        feed.isEmpty
            ? ("space_id NOT IN (SELECT id FROM spaces WHERE shared IS NOT NULL)", []) : ("space_id = ?", [feed])
    }

    private func finishFullSync(_ sql: Sql, feed: String) throws {
        let now = clock()
        let (inFeed, args) = Self.notesIn(feed)
        let missing = try sql.queryArray(
            "SELECT \(noteColumns) FROM notes WHERE full_sync_seen = 0 AND server_revision IS NOT NULL AND \(inFeed)",
            args, row: noteRow)
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
        if feed.isEmpty {
            try sql.exec("UPDATE sync_state SET full_sync = 0 WHERE id = 1")
        } else {
            try sql.exec("UPDATE feeds SET full_sync = 0 WHERE space_id = ?", feed)
        }
    }

    public func finished(error: String?) async throws {
        let now = clock()
        try await db.write { sql in
            try sql.exec("UPDATE sync_state SET last_sync_at = ?, last_error = ? WHERE id = 1", now, error)
            try sql.forgetOldMerges(now: now)
        }
    }

    // MARK: reconcile (§4.6): the one routine for pushes that lost and pulled changes

    private func reconcile(
        _ sql: Sql, _ remote: RemoteNote, _ events: inout [SyncEvent], fullSync: Bool, feed: String = ""
    ) throws {
        guard let local = try sql.note(remote.id) else {
            if remote.deletedAt == nil { try overwrite(sql, nil, remote) }
            return
        }
        // A note in another file than this feed's, as far as this device knows (§7.9).
        let elsewhere = try sql.serverSpace(local).map { try sql.feedOf($0) != feed } ?? false
        if remote.deletedAt != nil, let movedTo = remote.movedTo {
            // Left this feed: already followed (the move was ours, or the destination's feed came first), or follow it.
            if elsewhere { return }
            if try Self.follows(sql, remote, movedTo) { return try follow(sql, local, movedTo) }
            // Moved where this device can't follow: for it, a deletion.
            var deleted = remote
            deleted.movedTo = nil
            return try reconcile(sql, deleted, &events, fullSync: fullSync, feed: feed)
        }
        if elsewhere && remote.deletedAt == nil && !fullSync {
            // Arrived from another file: its revisions there aren't comparable with the ones this device has.
            let ops = try sql.int64("SELECT count(*) FROM sync_outbox WHERE note_id = ?", local.id)
            if !local.dirty && ops == 0 {
                try overwrite(sql, local, remote)
            } else {
                try merge(sql, local, remote, &events)
            }
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

    /// Whether this device follows a moved note: to a shared space it's in, or to one of the account's own only when
    /// the account moved it. Another member's move to *their* own space can name an id this account has too
    /// ("default"). Not knowing who moved it, or who this account is, it's a deletion: were the move this account's,
    /// its own feed brings the note back.
    private static func follows(_ sql: Sql, _ remote: RemoteNote, _ movedTo: String) throws -> Bool {
        guard try sql.reaches(movedTo) else { return false }
        if try !sql.feedOf(movedTo).isEmpty { return true }
        guard let by = remote.updatedBy,
            let me = try sql.one("SELECT user_id FROM sync_state WHERE id = 1", row: { $0.int64OrNil(0) }) ?? nil
        else { return false }
        return by == me
    }

    /// A note moved to `spaceId` elsewhere: it's there now, text unchanged, until that space's feed brings it.
    private func follow(_ sql: Sql, _ local: Note, _ spaceId: String) throws {
        let server = local.baseSpaceId ?? local.spaceId
        try sql.exec(
            """
            UPDATE notes SET space_id = CASE WHEN dirty = 0 OR space_id = ? THEN ? ELSE space_id END,
                base_space_id = CASE WHEN dirty = 1 THEN ? ELSE base_space_id END
            WHERE id = ?
            """,
            server, spaceId, spaceId, local.id)
    }

    private func merge(_ sql: Sql, _ local: Note, _ remote: RemoteNote, _ events: inout [SyncEvent]) throws {
        // Undo splits the last merge only: this one may be a conflict that records none.
        try sql.forgetMerge(local.id)
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
        let body =
            content.map { BodyMerge.clean($0) }
            ?? Merge.body(base: baseContent, mine: local.content, theirs: remote.content)
        let theirsSide: String
        let mineSide: String
        switch body {
        case .conflict:
            let copyTitle = copyTitle(local.title, conflictedSuffix)
            let copy = try sql.insertCopy(
                title: copyTitle, content: local.content, conflictOf: local.id, spaceId: local.spaceId, now: clock())
            try faultAfterCopy?()
            try dropOps(sql, local.id)
            try overwrite(sql, local, remote)
            events.append(.conflictedCopy(noteId: local.id, copyId: copy, title: copyTitle))
            return
        case .clean(let merged):
            (theirsSide, mineSide) = (merged, merged)
        case .partial(let theirs, let mine):
            (theirsSide, mineSide) = (theirs, mine)
        }
        guard let title, case .clean(let merged) = body else {
            // Both get every change that merged, so the two differ only where both sides changed the same thing.
            let copyTitle = copyTitle(title ?? local.title, conflictedSuffix)
            let copy = try sql.insertCopy(
                title: copyTitle, content: mineSide, conflictOf: local.id, spaceId: local.spaceId, now: clock())
            try faultAfterCopy?()
            try storeMerged(sql, local, remote, title ?? remote.title, theirsSide, space)
            events.append(.conflictedCopy(noteId: local.id, copyId: copy, title: copyTitle))
            return
        }
        try storeMerged(sql, local, remote, title, merged, space)
        if content == nil {
            try sql.recordMerge(
                MergeUndo(
                    noteId: local.id, keepTitle: remote.title, keepContent: remote.content, copyTitle: local.title,
                    copyContent: local.content, mergedTitle: title, mergedContent: merged, mergedAt: clock()))
            events.append(.merged(noteId: local.id, title: title))
        }
    }

    /// The note becomes `title`/`content` in `space`, based on `remote` and queued, unless that's just `remote`.
    private func storeMerged(
        _ sql: Sql, _ local: Note, _ remote: RemoteNote, _ title: String, _ content: String, _ space: String
    ) throws {
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
                    server_change_seq, space_id, dirty, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                remote.id, remote.title, remote.content, remote.createdAt, remote.updatedAt, remote.conflictOf,
                remote.revision, remote.changeSeq, remote.spaceId, remote.updatedBy)
        } else {
            try sql.exec(
                """
                UPDATE notes SET title = ?, content = ?, created_at = ?, updated_at = ?, deleted_at = NULL,
                    conflict_of = ?, server_revision = ?, server_change_seq = ?, space_id = ?, dirty = 0,
                    base_title = NULL, base_content = NULL, base_deleted_at = NULL, base_space_id = NULL,
                    updated_by = ?
                WHERE id = ?
                """,
                remote.title, remote.content, remote.createdAt, remote.updatedAt, remote.conflictOf, remote.revision,
                remote.changeSeq, remote.spaceId, remote.updatedBy, remote.id)
        }
        try sql.index(remote.id, title: remote.title, content: remote.content)
        // Undo would keep the text from before the lock in plain text here.
        if LockedNotes.isLocked(remote.content) { try sql.forgetMerge(remote.id) }
    }

    private func tombstone(_ sql: Sql, _ remote: RemoteNote) throws {
        try adoptRevision(sql, remote)
        try markTombstone(sql, remote.id, deletedAt: remote.deletedAt ?? remote.updatedAt, updatedAt: remote.updatedAt)
    }

    /// Matches the server's tombstone: no text, out of the index, nothing pending.
    private func markTombstone(_ sql: Sql, _ id: String, deletedAt: Int64, updatedAt: Int64) throws {
        try sql.unindex(id)
        try sql.forgetMerge(id)
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
