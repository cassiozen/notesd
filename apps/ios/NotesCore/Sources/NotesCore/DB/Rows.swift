import CryptoKit
import Foundation

// Row access shared by NoteStore (local edits) and SyncStore (the sync engine). Callers hold a write transaction.

let createOp = "create"
let updateOp = "update"
let deleteOp = "delete"

public struct Note: Hashable, Sendable {
    public var id: String
    public var title: String
    public var content: String
    public var createdAt: Int64
    public var updatedAt: Int64
    public var deletedAt: Int64?
    public var conflictOf: String?
    public var serverRevision: Int64?
    public var dirty: Bool
    /// The last synced version, kept while `dirty`: what a merge compares both sides against.
    public var baseTitle: String?
    public var baseContent: String?
    /// The space the note is in (§7.7), and the synced one while `dirty`.
    public var spaceId: String = Spaces.defaultId
    public var baseSpaceId: String?

    public var locked: Bool { LockedNotes.isLocked(content) }
}

let noteColumns =
    "id, title, content, created_at, updated_at, deleted_at, conflict_of, server_revision, dirty, base_title, base_content, "
    + "space_id, base_space_id"

func noteRow(_ r: Row) -> Note {
    Note(
        id: r.text(0),
        title: r.text(1),
        content: r.text(2),
        createdAt: r.int64(3),
        updatedAt: r.int64(4),
        deletedAt: r.int64OrNil(5),
        conflictOf: r.textOrNil(6),
        serverRevision: r.int64OrNil(7),
        dirty: r.int64(8) == 1,
        baseTitle: r.textOrNil(9),
        baseContent: r.textOrNil(10),
        spaceId: r.text(11),
        baseSpaceId: r.textOrNil(12)
    )
}

extension Sql {
    func note(_ id: String) throws -> Note? {
        try one("SELECT \(noteColumns) FROM notes WHERE id = ?", id, row: noteRow)
    }

    /// Replaces the search entry and tags for a note.
    func index(_ id: String, title: String, content: String) throws {
        let seq = try int64("SELECT seq FROM notes WHERE id = ?", id)
        try unindex(seq: seq)
        try insertIndex(seq: seq, title: title, stored: content)
    }

    func unindex(_ id: String) throws {
        try unindex(seq: try int64("SELECT seq FROM notes WHERE id = ?", id))
    }

    private func unindex(seq: Int64) throws {
        try exec("DELETE FROM notes_fts WHERE rowid = ?", seq)
        try exec("DELETE FROM note_tags WHERE note_seq = ?", seq)
    }

    private func insertIndex(seq: Int64, title: String, stored: String) throws {
        // A locked body is ciphertext: only the title is searchable and tagged, as on the server (§10.1).
        let content = LockedNotes.isLocked(stored) ? "" : stored
        try exec("INSERT INTO notes_fts (rowid, title, body_text) VALUES (?, ?, ?)", seq, title, plainText(content))
        for tag in tags(title: title, markdown: content) {
            try exec("INSERT INTO note_tags (note_seq, key, name) VALUES (?, ?, ?)", seq, tag.key, tag.name)
        }
    }

    /// Rebuilds the search index and tags from the notes. Sync state is untouched.
    func rebuildIndex() throws -> Int {
        try exec("DELETE FROM notes_fts")
        try exec("DELETE FROM note_tags")
        try exec("DELETE FROM rebuild_index")
        let live = try query("SELECT seq, title, content FROM notes WHERE deleted_at IS NULL") {
            ($0.int64(0), $0.text(1), $0.text(2))
        }
        for (seq, title, content) in live { try insertIndex(seq: seq, title: title, stored: content) }
        return live.count
    }
}

/// The request body an operation will send, minus what is fixed at send time (operationId, baseRevision).
struct Payload: Codable, Hashable, Sendable {
    var title = ""
    var content = ""
    var createdAt: Int64?
    var updatedAt: Int64?
    var conflictOf: String?
    /// A locked body's images (§7.6), when this device knows them; nil leaves the server's record as it is.
    var imageIds: [String]?
    /// The note's space (§7.7); nil (operations queued before spaces) keeps the server's.
    var spaceId: String?

    init(
        title: String = "", content: String = "", createdAt: Int64? = nil, updatedAt: Int64? = nil,
        conflictOf: String? = nil, imageIds: [String]? = nil, spaceId: String? = nil
    ) {
        self.title = title
        self.content = content
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.conflictOf = conflictOf
        self.imageIds = imageIds
        self.spaceId = spaceId
    }

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        content = try c.decodeIfPresent(String.self, forKey: .content) ?? ""
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt)
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt)
        conflictOf = try c.decodeIfPresent(String.self, forKey: .conflictOf)
        imageIds = try c.decodeIfPresent([String].self, forKey: .imageIds)
        spaceId = try c.decodeIfPresent(String.self, forKey: .spaceId)
    }

    var json: String { String(decoding: try! jsonEncoder.encode(self), as: UTF8.self) }

    static func decode(_ json: String) throws -> Payload {
        try JSONDecoder().decode(Payload.self, from: Data(json.utf8))
    }
}

extension Sql {
    func enqueue(_ noteId: String, type: String, payload: Payload?, now: Int64) throws {
        var sent = payload
        if let content = payload?.content { sent?.imageIds = try lockedImageIds(content) }
        try exec(
            """
            INSERT INTO sync_outbox (operation_id, note_id, operation_type, base_revision, payload_json, created_at)
            VALUES (?, ?, ?, NULL, ?, ?)
            """,
            Uuid7.generate(), noteId, type, sent?.json ?? "{}", now
        )
    }

    /// The newest operation for `noteId` that hasn't been sent: the one later edits coalesce into.
    func unsentOp(_ noteId: String) throws -> (operationId: String, type: String, payload: Payload)? {
        try one(
            """
            SELECT operation_id, operation_type, payload_json FROM sync_outbox
            WHERE note_id = ? AND sent = 0 ORDER BY created_at DESC, rowid DESC LIMIT 1
            """,
            noteId
        ) { ($0.text(0), $0.text(1), try Payload.decode($0.text(2))) }
    }

    /// Makes sure the server will receive `title`/`content` in `spaceId` for `noteId`: rewrites the
    /// unsent create or update, or queues a new update (a sent payload is never changed).
    func queueText(_ noteId: String, title: String, content: String, spaceId: String, now: Int64) throws {
        if let pending = try unsentOp(noteId), pending.type != deleteOp {
            var payload = pending.payload
            payload.title = title
            payload.content = content
            payload.updatedAt = now
            payload.imageIds = try lockedImageIds(content)
            payload.spaceId = spaceId
            try exec(
                "UPDATE sync_outbox SET payload_json = ? WHERE operation_id = ?", payload.json, pending.operationId)
        } else {
            try enqueue(
                noteId, type: updateOp,
                payload: Payload(title: title, content: content, updatedAt: now, spaceId: spaceId), now: now)
        }
    }

    /// A new note holding `title`/`content` in `spaceId`, queued for upload. Conflicted and
    /// recovered copies are made this way, so no text is ever dropped.
    @discardableResult
    func insertCopy(
        title: String, content: String, conflictOf: String?, spaceId: String, now: Int64, imageIds: [String]? = nil
    ) throws -> String {
        let id = Uuid7.generate()
        try rememberImages(id, content: content, imageIds: imageIds ?? lockedImageIds(content))
        try exec(
            """
            INSERT INTO notes (id, title, content, created_at, updated_at, conflict_of, space_id, dirty)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1)
            """,
            id, title, content, now, now, conflictOf, spaceId
        )
        try index(id, title: title, content: content)
        let payload = Payload(
            title: title, content: content, createdAt: now, updatedAt: now, conflictOf: conflictOf, spaceId: spaceId)
        try enqueue(id, type: createOp, payload: payload, now: now)
        return id
    }
}

func copyTitle(_ title: String, _ suffix: String) -> String {
    let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
    return (trimmed.isEmpty ? "Untitled" : trimmed) + suffix
}

let conflictedSuffix = " (Conflicted copy)"
let recoveredSuffix = " (Recovered copy)"

/// How many recent locked bodies per note keep their images: saved, stored, and one a conflict copies.
private let bodiesKept = 3

func sha256Hex(_ data: some DataProtocol) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

extension Sql {
    /// Records that the locked body `content` of `noteId` shows `imageIds`, so
    /// whatever later sends that body (a save, a merge, a copy) lists them. The
    /// few newest bodies per note are kept; older ones are never sent again.
    func rememberImages(_ noteId: String, content: String, imageIds: [String]?) throws {
        guard let imageIds, LockedNotes.isLocked(content) else { return }
        let ids = String(decoding: try jsonEncoder.encode(imageIds), as: UTF8.self)
        try exec(
            "INSERT OR REPLACE INTO locked_images (note_id, content_sha, image_ids) VALUES (?, ?, ?)",
            noteId, sha256Hex(Data(content.utf8)), ids
        )
        try exec(
            """
            DELETE FROM locked_images WHERE note_id = ? AND rowid NOT IN
                (SELECT rowid FROM locked_images WHERE note_id = ? ORDER BY rowid DESC LIMIT ?)
            """,
            noteId, noteId, bodiesKept
        )
    }

    /// A note gone from this device: its bodies' images aren't needed.
    func forgetImages(_ noteId: String) throws {
        try exec("DELETE FROM locked_images WHERE note_id = ?", noteId)
    }

    /// The images in a locked body this device synced; nil for a plain body or an unknown one.
    func lockedImageIds(_ content: String) throws -> [String]? {
        guard LockedNotes.isLocked(content) else { return nil }
        // Any note's row will do: a conflicted or recovered copy carries the original's body and images.
        // Envelopes use a random nonce, so the same envelope always means the same plaintext and images.
        return try one(
            "SELECT image_ids FROM locked_images WHERE content_sha = ? ORDER BY rowid DESC LIMIT 1",
            sha256Hex(Data(content.utf8))
        ) { try JSONDecoder().decode([String].self, from: Data($0.text(0).utf8)) }
    }
}
