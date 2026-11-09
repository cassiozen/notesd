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
    /// As the server last sent them (§7.8).
    public var traits: [SpaceTrait] = []
    /// Who's in it, when it's shared (§7.9); nil for the account's own.
    public var shared: SharedSpace? = nil
}

/// What other members changed in a shared space since this device last showed it (§7.9): `count` notes,
/// the `names` of who changed them (latest first), the latest at `latestAt`.
public struct SpaceNews: Hashable, Sendable {
    public var count: Int
    public var names: [String]
    public var latestAt: Int64

    public init(count: Int, names: [String], latestAt: Int64) {
        self.count = count
        self.names = names
        self.latestAt = latestAt
    }
}

enum TraitsColumn {
    // A row this release can't read (a later one wrote it) shows no traits rather than failing the list.
    static func decode(_ text: String) -> [SpaceTrait] {
        (try? JSONDecoder().decode([SpaceTrait].self, from: Data(text.utf8))) ?? []
    }

    static func encode(_ traits: [SpaceTrait]) -> String {
        (try? JSONEncoder().encode(traits)).map { String(decoding: $0, as: UTF8.self) } ?? "[]"
    }
}

enum SharedColumn {
    static func decode(_ text: String?) -> SharedSpace? {
        text.flatMap { try? JSONDecoder().decode(SharedSpace.self, from: Data($0.utf8)) }
    }

    static func encode(_ shared: SharedSpace?) -> String? {
        shared.flatMap { try? jsonEncoder.encode($0) }.map { String(decoding: $0, as: UTF8.self) }
    }
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
                    (SELECT count(*) FROM notes n WHERE n.space_id = s.id AND n.deleted_at IS NULL), s.icon, s.traits,
                    s.shared
                FROM spaces s WHERE s.pending_delete = 0
                ORDER BY s.id != ?, lower(s.name), s.id
                """,
                Spaces.defaultId
            ) {
                Space(
                    id: $0.text(0), name: $0.text(1), noteCount: Int($0.int64(2)), icon: $0.text(3),
                    traits: TraitsColumn.decode($0.text(4)), shared: SharedColumn.decode($0.textOrNil(5)))
            }
        }
    }

    /// The invitations waiting for this account, as last synced (§7.9).
    public func invites() async throws -> [RemoteInvite] {
        try await db.read { sql in
            try sql.query(
                """
                SELECT id, space_id, space_name, space_icon, from_id, from_username, created_at, from_display_name,
                    from_avatar
                FROM invites ORDER BY created_at, id
                """
            ) {
                RemoteInvite(
                    id: $0.text(0), spaceId: $0.text(1), spaceName: $0.text(2), spaceIcon: $0.text(3),
                    from: Member(id: $0.int64(4), username: $0.text(5), displayName: $0.text(7), avatar: $0.text(8)),
                    createdAt: $0.int64(6))
            }
        }
    }

    /// The space was shown until `at` (now if nil): what changed by then is no longer news. Pass the moment it was
    /// shown when the write may land later, or a change synced in between would never count.
    public func visit(_ id: String, at: Int64? = nil) async throws {
        let at = at ?? clock()
        try await db.write { sql in
            // Never back: a visit timed earlier can land after a later one.
            try sql.exec(
                "UPDATE spaces SET visited_at = max(coalesce(visited_at, 0), ?) WHERE id = ? AND shared IS NOT NULL",
                at, id)
        }
    }

    /// By shared space, what other members changed since this device last showed it; spaces with none left out.
    public func news() async throws -> [String: SpaceNews] {
        try await db.read { sql in
            guard let me = try sql.one("SELECT user_id FROM sync_state WHERE id = 1", row: { $0.int64OrNil(0) }) ?? nil
            else { return [:] }
            let spaces = try sql.query(
                "SELECT id, coalesce(visited_at, 0), shared FROM spaces WHERE shared IS NOT NULL"
            ) {
                ($0.text(0), $0.int64(1), SharedColumn.decode($0.textOrNil(2)))
            }
            var news: [String: SpaceNews] = [:]
            for (id, since, shared) in spaces {
                let changed = try sql.query(
                    """
                    SELECT updated_by, updated_at FROM notes
                    WHERE space_id = ? AND deleted_at IS NULL AND updated_by IS NOT NULL AND updated_by != ?
                        AND updated_at > ?
                    ORDER BY updated_at DESC
                    """,
                    id, me, since
                ) { ($0.int64(0), $0.int64(1)) }
                guard let latest = changed.first else { continue }
                let names = Dictionary((shared?.members ?? []).map { ($0.id, $0.name) }) { a, _ in a }
                var seen = Set<Int64>()
                let who = changed.compactMap { seen.insert($0.0).inserted ? names[$0.0] : nil }
                news[id] = SpaceNews(count: changed.count, names: who, latestAt: latest.1)
            }
            return news
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
                "UPDATE spaces SET name = ?, icon = coalesce(?, icon), dirty = 1, attempts = 0, parked = 0 WHERE id = ?",
                stored, icon, id)
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
                """
                SELECT id, name, icon, pending_delete FROM spaces
                WHERE (dirty = 1 OR pending_delete = 1) AND parked = 0 ORDER BY rowid
                """
            ) {
                SpaceChange(id: $0.text(0), name: $0.text(1), icon: $0.text(2), delete: $0.int64(3) == 1)
            }
        }
    }

    /// The server stored `change` as `stored` (maybe renamed to settle a clash). Changed again since: still waiting.
    func sent(_ change: SpaceChange, stored: RemoteSpace) async throws {
        try await db.write { sql in
            try sql.exec(
                "UPDATE spaces SET name = ?, icon = ?, dirty = 0, attempts = 0 WHERE id = ? AND name = ? AND icon = ?",
                stored.name, stored.icon, change.id, change.name, change.icon)
            try sql.exec(
                "UPDATE spaces SET traits = ?, shared = ? WHERE id = ?", TraitsColumn.encode(stored.traits),
                SharedColumn.encode(stored.shared), change.id)
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

    /// Counts an unexpected answer against `change`; the third parks it.
    func attempted(_ change: SpaceChange) async throws {
        try await db.write { sql in
            // Only the version that went: one changed meanwhile is a new change, with its own tries.
            if change.delete {
                try sql.exec(
                    "UPDATE spaces SET attempts = attempts + 1, parked = attempts + 1 >= ? WHERE id = ? AND pending_delete = 1",
                    maxAttempts, change.id)
            } else {
                try sql.exec(
                    """
                    UPDATE spaces SET attempts = attempts + 1, parked = attempts + 1 >= ?
                    WHERE id = ? AND name = ? AND icon = ? AND dirty = 1
                    """,
                    maxAttempts, change.id, change.name, change.icon)
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
            // Rows the list still has are updated in place: what this device keeps on them (visited_at) stays.
            let listed = Set(remote.map(\.id))
            let gone = try sql.query(
                "SELECT id FROM spaces WHERE dirty = 0 AND pending_delete = 0 AND id != ?", Spaces.defaultId
            ) { $0.text(0) }
            for id in gone where !listed.contains(id) { try sql.exec("DELETE FROM spaces WHERE id = ?", id) }
            for s in remote {
                try sql.exec(
                    """
                    INSERT INTO spaces (id, name, icon, created_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET name = excluded.name, icon = excluded.icon,
                        created_at = excluded.created_at
                    WHERE dirty = 0 AND pending_delete = 0
                    """,
                    s.id, s.name, s.icon, s.createdAt)
                // Even on a row with a change waiting: traits and members are the server's, not this device's.
                try sql.exec(
                    "UPDATE spaces SET traits = ?, shared = ? WHERE id = ?", TraitsColumn.encode(s.traits),
                    SharedColumn.encode(s.shared), s.id)
                // A space no longer shared forgets when it was shown: shared again, only what's new from then is news.
                if s.shared == nil { try sql.exec("UPDATE spaces SET visited_at = NULL WHERE id = ?", s.id) }
            }
        }
    }

    /// The server's invitations replace this device's copy.
    func replaceInvites(_ remote: [RemoteInvite]) async throws {
        try await db.write { sql in
            try sql.exec("DELETE FROM invites")
            for i in remote {
                try sql.exec(
                    """
                    INSERT INTO invites (id, space_id, space_name, space_icon, from_id, from_username, created_at,
                        from_display_name, from_avatar)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    i.id, i.spaceId, i.spaceName, i.spaceIcon, i.from.id, i.from.username, i.createdAt,
                    i.from.displayName, i.from.avatar)
            }
        }
    }
}
