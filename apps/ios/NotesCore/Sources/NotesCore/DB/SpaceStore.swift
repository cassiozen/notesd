import Foundation

public enum Spaces {
    /// The space every install has (§7.7): renamed maybe, never deleted.
    public static let defaultId = "default"
    public static let defaultName = "Notes"
    public static let maxName = 64

    /// A name as the server stores it: trimmed, inner whitespace collapsed; nil when nothing is left.
    public static func normalize(_ name: String) -> String? {
        let words = name.split(whereSeparator: { $0.isWhitespace })
        return words.isEmpty ? nil : words.joined(separator: " ")
    }

    /// `name` as the server will store it; throws for one it would refuse, which would
    /// otherwise be lost at sync.
    static func checked(_ name: String) throws -> String {
        guard let stored = normalize(name) else { throw DatabaseError(code: 0, message: "a space needs a name") }
        if stored.unicodeScalars.count > maxName {
            throw DatabaseError(code: 0, message: "at most \(maxName) characters")
        }
        if stored.unicodeScalars.contains(where: { $0.properties.generalCategory == .control }) {
            throw DatabaseError(code: 0, message: "no control characters")
        }
        return stored
    }
}

/// A space as this device has it, with how many live notes are in it here.
public struct Space: Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var noteCount: Int
    /// A key into the app's icons; "" for the default one.
    public var icon: String = ""
}

/// A space change waiting to go to the server.
struct SpaceChange: Sendable {
    var id: String
    var name: String
    var icon: String
    var delete: Bool
}

/// This device's copy of the spaces (§7.7). Changes made here wait in the
/// rows themselves (dirty, pending_delete) until sync sends them; the
/// server's list replaces every row that has nothing waiting.
public final class SpaceStore: Sendable {
    private let db: Database
    private let clock: Clock

    public init(db: Database, clock: @escaping Clock = systemClock) {
        self.db = db
        self.clock = clock
    }

    /// The default space first, then by name; deleted ones already gone.
    public func list() async throws -> [Space] {
        try await db.read { sql in
            try sql.query(
                """
                SELECT s.id, s.name,
                    (SELECT count(*) FROM notes n WHERE n.space_id = s.id AND n.deleted_at IS NULL), s.icon
                FROM spaces s WHERE s.pending_delete = 0
                ORDER BY s.id != ?, lower(s.name), s.id
                """,
                Spaces.defaultId
            ) { Space(id: $0.text(0), name: $0.text(1), noteCount: Int($0.int64(2)), icon: $0.text(3)) }
        }
    }

    /// Creates a space here, to be sent on the next sync. Returns its id.
    @discardableResult
    public func create(_ name: String, icon: String = "") async throws -> String {
        let stored = try Spaces.checked(name)
        let id = Uuid7.generate()
        let now = clock()
        try await db.write { sql in
            try sql.exec(
                "INSERT INTO spaces (id, name, icon, created_at, dirty) VALUES (?, ?, ?, ?, 1)", id, stored, icon, now)
        }
        return id
    }

    /// Renames the space, and changes its icon unless `icon` is nil.
    public func update(_ id: String, name: String, icon: String? = nil) async throws {
        let stored = try Spaces.checked(name)
        try await db.write { sql in
            try sql.exec(
                "UPDATE spaces SET name = ?, icon = coalesce(?, icon), dirty = 1 WHERE id = ?", stored, icon, id)
        }
    }

    /// Deletes an empty space; false (and nothing changes) for the default one or one with notes here.
    @discardableResult
    public func delete(_ id: String) async throws -> Bool {
        if id == Spaces.defaultId { return false }
        return try await db.write { sql in
            if try sql.int64("SELECT count(*) FROM notes WHERE space_id = ? AND deleted_at IS NULL", id) > 0 {
                return false
            }
            try sql.exec("UPDATE spaces SET pending_delete = 1 WHERE id = ?", id)
            return true
        }
    }

    // MARK: for the sync engine

    func pending() async throws -> [SpaceChange] {
        try await db.read { sql in
            try sql.query(
                "SELECT id, name, icon, pending_delete FROM spaces WHERE dirty = 1 OR pending_delete = 1 ORDER BY rowid"
            ) {
                SpaceChange(id: $0.text(0), name: $0.text(1), icon: $0.text(2), delete: $0.int64(3) == 1)
            }
        }
    }

    /// The server stored `change` as `stored` (maybe renamed to settle a clash). Changed again since: still waiting.
    func sent(_ change: SpaceChange, stored: RemoteSpace) async throws {
        try await db.write { sql in
            try sql.exec(
                "UPDATE spaces SET name = ?, icon = ?, dirty = 0 WHERE id = ? AND name = ? AND icon = ?",
                stored.name, stored.icon, change.id, change.name, change.icon)
        }
    }

    /// The server refused `change` for good (an invalid name, a space that isn't empty): the
    /// list it sends next decides. Changed again since it went: the new version is still waiting.
    func dropped(_ change: SpaceChange) async throws {
        try await db.write { sql in
            if change.delete {
                try sql.exec("UPDATE spaces SET pending_delete = 0 WHERE id = ?", change.id)
            } else {
                try sql.exec(
                    "UPDATE spaces SET dirty = 0 WHERE id = ? AND name = ? AND icon = ?", change.id, change.name,
                    change.icon)
            }
        }
    }

    /// Deleted on the server.
    func deleted(_ change: SpaceChange) async throws {
        try await db.write { sql in try sql.exec("DELETE FROM spaces WHERE id = ? AND pending_delete = 1", change.id) }
    }

    /// The server's list replaces every row with nothing waiting to go.
    func replace(_ remote: [RemoteSpace]) async throws {
        try await db.write { sql in
            try sql.exec("DELETE FROM spaces WHERE dirty = 0 AND pending_delete = 0 AND id != ?", Spaces.defaultId)
            for s in remote {
                try sql.exec(
                    """
                    INSERT INTO spaces (id, name, icon, created_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET name = excluded.name, icon = excluded.icon,
                        created_at = excluded.created_at
                    WHERE dirty = 0 AND pending_delete = 0
                    """,
                    s.id, s.name, s.icon, s.createdAt)
            }
        }
    }
}
