import Foundation

/// `locked`: the body is encrypted (§10.1), so there's no preview.
public struct NoteSummary: Hashable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var preview: String
    public var updatedAt: Int64
    public var locked: Bool
}

/// Narrows a query to one space (§7.7), or to every space but one; nil leaves it open.
public enum SpaceScope: Hashable, Sendable {
    case only(String)
    case except(String)

    fileprivate var sql: (String, [(any SQLValue)?]) {
        switch self {
        case .only(let id): ("n.space_id = ?", [id])
        case .except(let id): ("n.space_id != ?", [id])
        }
    }
}

extension SpaceScope? {
    fileprivate var sql: (String, [(any SQLValue)?]) { self?.sql ?? ("1", []) }
}

/// Both conditions, ANDed.
private func and(_ a: (String, [(any SQLValue)?]), _ b: (String, [(any SQLValue)?])) -> (String, [(any SQLValue)?]) {
    ("(\(a.0)) AND (\(b.0))", a.1 + b.1)
}

/// Narrows the list and search; nil shows every note.
public enum NoteFilter: Hashable, Sendable, Codable {
    /// Notes with the tag `key` or one nested under it. `name` is how to show it.
    case tag(key: String, name: String)
    case untagged
    case locked

    /// A condition on the notes row aliased n, with its arguments.
    fileprivate var sql: (String, [(any SQLValue)?]) {
        switch self {
        case .untagged:
            ("NOT EXISTS (SELECT 1 FROM note_tags t WHERE t.note_seq = n.seq)", [])
        case .locked:
            ("substr(n.content, 1, ?) = ?", [LockedNotes.prefix.count, LockedNotes.prefix])
        case .tag(let key, _):
            // '0' sorts right after '/', so the range holds exactly the nested keys.
            (
                "EXISTS (SELECT 1 FROM note_tags t WHERE t.note_seq = n.seq AND (t.key = ? OR (t.key > ? AND t.key < ?)))",
                [key, key + "/", key + "0"]
            )
        }
    }
}

extension NoteFilter? {
    fileprivate var sql: (String, [(any SQLValue)?]) { self?.sql ?? ("1", []) }
}

/// A tag in live notes, with the number of notes carrying it or one nested under it.
public struct TagCount: Hashable, Sendable {
    public var key: String
    public var name: String
    public var count: Int

    public init(key: String, name: String, count: Int) {
        self.key = key
        self.name = name
        self.count = count
    }
}

/// A search result. `excerpt` marks matches with `Search.markOpen` / `Search.markClose`; a `locked` note matched on its title only.
public struct SearchHit: Hashable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var excerpt: String
    public var updatedAt: Int64
    public var locked: Bool
    public var spaceId: String = Spaces.defaultId
}

public struct OutboxEntry: Hashable, Sendable {
    public var operationId: String
    public var noteId: String
    public var type: String
    public var baseRevision: Int64?
    public var payloadJson: String
    public var sent: Bool
}

/// Unix milliseconds, the time unit the server and the schema use.
public typealias Clock = @Sendable () -> Int64

public let systemClock: Clock = { Int64(Date().timeIntervalSince1970 * 1000) }

/// Notes on this device. Every local change is one transaction that updates the
/// note, its search entry and the sync outbox together, so the three can't
/// disagree after a crash. Outbox operations coalesce until sent: an unsent
/// create or update takes the latest text; once sent, a later change queues a
/// new operation (a sent payload must be resent as is).
public final class NoteStore: Sendable {
    private let db: Database
    private let clock: Clock
    private let localEditFeed = Broadcast<Void>()

    public init(db: Database, clock: @escaping Clock = systemClock) {
        self.db = db
        self.clock = clock
    }

    /// Every committed write, local or from sync.
    public func changes() -> AsyncStream<Void> { db.changes() }

    /// Only edits made on this device: what should trigger an upload (sync's own writes don't).
    public func localEdits() -> AsyncStream<Void> { localEditFeed.stream() }

    public func get(_ id: String) async throws -> Note? {
        try await db.read { try $0.note(id) }
    }

    /// Live notes matching `filter` in `space` (nil: every space), most recently edited first.
    public func list(_ filter: NoteFilter? = nil, space: String? = nil) async throws -> [NoteSummary] {
        try await db.read { sql in
            let (cond, args) = and(filter.sql, space.map { SpaceScope.only($0) }.sql)
            let all: [(any SQLValue)?] = [LockedNotes.prefix.count, LockedNotes.prefix] + args
            return try sql.queryArray(
                """
                SELECT n.id, n.title, n.updated_at, f.body_text, substr(n.content, 1, ?) = ?
                FROM notes n LEFT JOIN notes_fts f ON f.rowid = n.seq
                WHERE n.deleted_at IS NULL AND \(cond)
                ORDER BY n.updated_at DESC, n.id DESC
                """,
                all
            ) {
                NoteSummary(
                    id: $0.text(0), title: $0.text(1), preview: previewOf($0.textOrNil(3) ?? ""),
                    updatedAt: $0.int64(2), locked: $0.int64(4) == 1)
            }
        }
    }

    /// Live locked notes, in `space` or everywhere.
    public func lockedCount(space: String? = nil) async throws -> Int {
        try await db.read { sql in
            let (cond, args) = and(NoteFilter.locked.sql, space.map { SpaceScope.only($0) }.sql)
            return try sql.queryArray("SELECT count(*) FROM notes n WHERE n.deleted_at IS NULL AND \(cond)", args) {
                Int($0.int64(0))
            }.first ?? 0
        }
    }

    /// Ranked as on the server: title matches weigh 10×, then the most recently edited.
    public func search(_ input: String, filter: NoteFilter? = nil, limit: Int = 50, scope: SpaceScope? = nil)
        async throws -> [SearchHit]
    {
        let match = Search.buildQuery(input)
        if match.isEmpty { return [] }
        return try await db.read { sql in
            let (cond, args) = and(filter.sql, scope.sql)
            let all: [(any SQLValue)?] =
                [Search.markOpen, Search.markClose, LockedNotes.prefix.count, LockedNotes.prefix, match] + args
                + [Search.titleWeight, Search.contentWeight, limit]
            return try sql.queryArray(
                """
                SELECT n.id, n.title, n.updated_at, snippet(notes_fts, 1, ?, ?, '…', 20), substr(n.content, 1, ?) = ?,
                    n.space_id
                FROM notes_fts JOIN notes n ON n.seq = notes_fts.rowid
                WHERE notes_fts MATCH ? AND n.deleted_at IS NULL AND \(cond)
                ORDER BY bm25(notes_fts, ?, ?), n.updated_at DESC
                LIMIT ?
                """,
                all
            ) {
                SearchHit(
                    id: $0.text(0), title: $0.text(1), excerpt: $0.textOrNil(3) ?? "", updatedAt: $0.int64(2),
                    locked: $0.int64(4) == 1, spaceId: $0.text(5))
            }
        }
    }

    /// Every tag in live notes in `space` (nil: every space), sorted by key, parents of nested tags included.
    /// A tag is named as in the most recently edited note using it (as on the server).
    public func tags(space: String? = nil) async throws -> [TagCount] {
        try await db.read { sql in
            var names: [String: String] = [:]
            var notes: [String: Set<Int64>] = [:]
            let (cond, args) = space.map { SpaceScope.only($0) }.sql
            let rows = try sql.queryArray(
                """
                SELECT t.key, t.name, t.note_seq FROM note_tags t JOIN notes n ON n.seq = t.note_seq
                WHERE n.deleted_at IS NULL AND \(cond) ORDER BY n.updated_at DESC, n.id DESC
                """,
                args
            ) { ($0.text(0), $0.text(1), $0.int64(2)) }
            for (key, name, seq) in rows {
                let keys = key.split(separator: "/", omittingEmptySubsequences: false)
                let levels = name.split(separator: "/", omittingEmptySubsequences: false)
                for i in keys.indices {
                    let k = keys[...i].joined(separator: "/")
                    if names[k] == nil { names[k] = levels.prefix(i + 1).joined(separator: "/") }
                    notes[k, default: []].insert(seq)
                }
            }
            return names.map { TagCount(key: $0.key, name: $0.value, count: notes[$0.key]?.count ?? 0) }
                .sorted { $0.key.utf8.lexicographicallyPrecedes($1.key.utf8) }
        }
    }

    /// Saves the editor's text for `id`, creating the note on first save. A note
    /// that doesn't exist yet isn't created while blank, so opening "New note"
    /// and leaving leaves nothing behind.
    ///
    /// `basedOn` is the stored title and content the editor's text grew from.
    /// If sync changed the note since, the two are merged per field; where both
    /// changed the same field the editor's text wins and the other version
    /// becomes a conflicted copy, so nothing is lost either way.
    ///
    /// Returns the stored note, or nil when nothing was stored (blank draft,
    /// deleted note).
    ///
    /// A new note is created in `spaceId`; an existing one stays where it is (`move` moves it).
    @discardableResult
    public func save(
        _ id: String, title: String, content: String, basedOn: (title: String, content: String)? = nil,
        imageIds: [String]? = nil, spaceId: String = Spaces.defaultId
    ) async throws -> Note? {
        let clock = clock
        let saved = try await db.write { sql -> Note? in
            try sql.rememberImages(id, content: content, imageIds: imageIds)
            guard let current = try sql.note(id) else {
                if isBlank(title) && isBlank(content) { return nil }
                return try Self.create(sql, id, title, content, spaceId, clock())
            }
            if current.deletedAt != nil { return nil }
            if let basedOn, !(current.title.same(as: basedOn.title) && current.content.same(as: basedOn.content)) {
                return try Self.mergeWithSync(sql, current, title, content, basedOn, clock)
            }
            if current.title.same(as: title) && current.content.same(as: content) { return current }
            return try Self.update(sql, current, title, content, clock())
        }
        if saved != nil { localEditFeed.send(()) }
        return saved
    }

    private static func create(
        _ sql: Sql, _ id: String, _ title: String, _ content: String, _ spaceId: String, _ now: Int64
    ) throws -> Note {
        try sql.exec(
            "INSERT INTO notes (id, title, content, created_at, updated_at, space_id, dirty) VALUES (?, ?, ?, ?, ?, ?, 1)",
            id, title, content, now, now, spaceId
        )
        try sql.index(id, title: title, content: content)
        let payload = Payload(title: title, content: content, createdAt: now, updatedAt: now, spaceId: spaceId)
        try sql.enqueue(id, type: createOp, payload: payload, now: now)
        return try sql.note(id)!
    }

    private static func update(
        _ sql: Sql, _ current: Note, _ title: String, _ content: String, _ clock: Int64, spaceId: String? = nil
    ) throws -> Note {
        let now = max(clock, current.updatedAt)
        let space = spaceId ?? current.spaceId
        // Right-hand sides see the row before the update: base_* capture the synced text when a clean note first changes.
        try sql.exec(
            """
            UPDATE notes SET title = ?, content = ?, space_id = ?, updated_at = ?, dirty = 1,
                base_title = CASE WHEN dirty = 0 THEN title ELSE base_title END,
                base_content = CASE WHEN dirty = 0 THEN content ELSE base_content END,
                base_deleted_at = CASE WHEN dirty = 0 THEN deleted_at ELSE base_deleted_at END,
                base_space_id = CASE WHEN dirty = 0 THEN space_id ELSE base_space_id END
            WHERE id = ?
            """,
            title, content, space, now, current.id
        )
        try sql.index(current.id, title: title, content: content)
        try sql.queueText(current.id, title: title, content: content, spaceId: space, now: now)
        return try sql.note(current.id)!
    }

    /// Moves a note to another space (§7.7), saved like an edit. False if there's no such live note.
    @discardableResult
    public func move(_ id: String, to spaceId: String) async throws -> Bool {
        let clock = clock
        let moved = try await db.write { sql -> Bool in
            guard let current = try sql.note(id), current.deletedAt == nil else { return false }
            if current.spaceId == spaceId { return true }
            _ = try Self.update(sql, current, current.title, current.content, clock(), spaceId: spaceId)
            return true
        }
        if moved { localEditFeed.send(()) }
        return moved
    }

    /// How many live notes outside `space` a search finds: the "more in other spaces" row (§7.7).
    public func countElsewhere(_ input: String, space: String) async throws -> Int {
        let match = Search.buildQuery(input)
        if match.isEmpty { return 0 }
        return try await db.read { sql in
            Int(
                try sql.int64(
                    """
                    SELECT count(*) FROM notes_fts JOIN notes n ON n.seq = notes_fts.rowid
                    WHERE notes_fts MATCH ? AND n.deleted_at IS NULL AND n.space_id != ?
                    """,
                    match, space))
        }
    }

    private static func mergeWithSync(
        _ sql: Sql, _ current: Note, _ title: String, _ content: String, _ basedOn: (title: String, content: String),
        _ clock: Clock
    ) throws -> Note {
        let mergedTitle = Merge.field(base: basedOn.title, mine: title, theirs: current.title, same: Merge.sameText)
        let mergedContent = Merge.field(
            base: basedOn.content, mine: content, theirs: current.content, same: Merge.sameMarkdown)
        if mergedTitle == nil || mergedContent == nil {
            try sql.insertCopy(
                title: copyTitle(current.title, conflictedSuffix), content: current.content, conflictOf: current.id,
                spaceId: current.spaceId, now: clock())
        }
        let t = mergedTitle ?? title
        let c = mergedContent ?? content
        if t.same(as: current.title) && c.same(as: current.content) { return current }
        return try update(sql, current, t, c, clock())
    }

    /// Keeps text whose note was deleted elsewhere while it was being edited:
    /// a new "(Recovered copy)" note in `spaceId`, as sync makes for edits it finds. Returns its id.
    @discardableResult
    public func recover(
        title: String, content: String, imageIds: [String]? = nil, spaceId: String = Spaces.defaultId
    ) async throws -> String {
        let clock = clock
        let id = try await db.write {
            try $0.insertCopy(
                title: copyTitle(title, recoveredSuffix), content: content, conflictOf: nil, spaceId: spaceId,
                now: clock(), imageIds: imageIds)
        }
        localEditFeed.send(())
        return id
    }

    /// Deletes `id`: a note the server never received is simply removed;
    /// otherwise it becomes a local tombstone (hidden, out of the index) with a
    /// delete queued, replacing unsent edits.
    public func delete(_ id: String) async throws {
        let clock = clock
        try await db.write { sql in
            guard let current = try sql.note(id), current.deletedAt == nil else { return }
            let sent = try sql.int64("SELECT count(*) FROM sync_outbox WHERE note_id = ? AND sent = 1", id) > 0
            try sql.unindex(id)
            try sql.forgetImages(id)
            if current.serverRevision == nil && !sent {
                try sql.exec("DELETE FROM sync_outbox WHERE note_id = ?", id)
                try sql.exec("DELETE FROM notes WHERE id = ?", id)
                return
            }
            try sql.exec("DELETE FROM sync_outbox WHERE note_id = ? AND sent = 0", id)
            try sql.exec(
                """
                UPDATE notes SET deleted_at = ?, dirty = 1,
                    base_title = CASE WHEN dirty = 0 THEN title ELSE base_title END,
                    base_content = CASE WHEN dirty = 0 THEN content ELSE base_content END,
                    base_deleted_at = CASE WHEN dirty = 0 THEN deleted_at ELSE base_deleted_at END,
                    base_space_id = CASE WHEN dirty = 0 THEN space_id ELSE base_space_id END
                WHERE id = ?
                """,
                clock(), id
            )
            try sql.enqueue(id, type: deleteOp, payload: nil, now: clock())
        }
        localEditFeed.send(())
    }

    /// Rebuilds the search index and tags from the notes. Sync state is untouched.
    public func reindex() async throws -> Int {
        try await db.write { try $0.rebuildIndex() }
    }

    /// Removes every note and all sync state from this device.
    public func wipe() async throws {
        try await db.write { sql in
            try sql.exec("DELETE FROM notes_fts")
            try sql.exec("DELETE FROM note_tags")
            try sql.exec("DELETE FROM notes")
            try sql.exec("DELETE FROM sync_outbox")
            try sql.exec("DELETE FROM locked_images")
            try sql.exec("DELETE FROM pending_images")
            try sql.exec("DELETE FROM lock_keys")
            try sql.exec("DELETE FROM spaces")
            try sql.exec(
                "INSERT INTO spaces (id, name, created_at) VALUES (?, ?, 0)", Spaces.defaultId, Spaces.defaultName)
            try sql.exec(
                """
                UPDATE sync_state SET cursor = 0, generation = NULL, last_sync_at = NULL, last_error = NULL,
                    full_sync = 0
                """
            )
        }
    }

    /// How many notes have changes the server hasn't confirmed.
    public func pendingNotes() async throws -> Int {
        try await db.read { Int(try $0.int64("SELECT count(DISTINCT note_id) FROM sync_outbox")) }
    }

    /// For tests of coalescing without a server: pretend `operationId` went out.
    func markSent(_ operationId: String) async throws {
        try await db.write { try $0.exec("UPDATE sync_outbox SET sent = 1 WHERE operation_id = ?", operationId) }
    }

    /// Unsent and in-flight operations, oldest first.
    public func outbox() async throws -> [OutboxEntry] {
        try await db.read { sql in
            try sql.query(
                """
                SELECT operation_id, note_id, operation_type, base_revision, payload_json, sent
                FROM sync_outbox ORDER BY created_at, rowid
                """
            ) {
                OutboxEntry(
                    operationId: $0.text(0), noteId: $0.text(1), type: $0.text(2), baseRevision: $0.int64OrNil(3),
                    payloadJson: $0.text(4), sent: $0.int64(5) == 1)
            }
        }
    }
}

/// Java's String.isBlank: empty or only whitespace.
func isBlank(_ s: String) -> Bool {
    s.unicodeScalars.allSatisfy { $0.properties.isWhitespace }
}
