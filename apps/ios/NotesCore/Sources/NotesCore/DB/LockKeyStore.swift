import Foundation
import Synchronization

/// This device's copy of the locked-notes keyring, so locked notes open offline (§10.1).
/// Every write moves `generation` on, so a fetch that started before a local
/// change (setup, a new password, a reset) can tell it's stale.
public final class LockKeyStore: Sendable {
    private let db: Database
    private let writes = Atomic<Int>(0)

    public init(db: Database) {
        self.db = db
    }

    /// Taken before fetching the server's keyring, for `replaceIfUnchanged`.
    public func generation() -> Int { writes.load(ordering: .sequentiallyConsistent) }

    public func keys() async throws -> [LockKey] {
        try await db.read { sql in
            try sql.query(
                """
                SELECT id, kind, credential_id, salt, iterations, wrapped, created_at, kdf FROM lock_keys
                ORDER BY kind != 'password', created_at, id
                """
            ) {
                LockKey(
                    id: $0.text(0), kind: $0.text(1), credentialId: $0.textOrNil(2), salt: $0.text(3),
                    iterations: Int($0.int64(4)), wrapped: $0.text(5), createdAt: $0.int64(6),
                    kdf: try $0.textOrNil(7).map { try JSONDecoder().decode(Kdf.self, from: Data($0.utf8)) })
            }
        }
    }

    /// Replaces the copy with the server's keyring.
    public func replace(_ keys: [LockKey]) async throws {
        try await db.write { sql in try self.store(sql, keys) }
    }

    /// Replaces the copy with a keyring fetched since `generation`, unless this
    /// device wrote one in the meantime (it's newer than the fetch). False when skipped.
    @discardableResult
    public func replaceIfUnchanged(_ keys: [LockKey], generation: Int) async throws -> Bool {
        try await db.write { sql in
            guard self.writes.load(ordering: .sequentiallyConsistent) == generation else { return false }
            try self.store(sql, keys)
            return true
        }
    }

    // Writes are serialised by the database, so the check above and this bump don't interleave.
    private func store(_ sql: Sql, _ keys: [LockKey]) throws {
        writes.add(1, ordering: .sequentiallyConsistent)
        try sql.exec("DELETE FROM lock_keys")
        for k in keys {
            try sql.exec(
                """
                INSERT INTO lock_keys (id, kind, credential_id, salt, iterations, wrapped, created_at, kdf)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                k.id, k.kind, k.credentialId, k.salt, Int64(k.iterations), k.wrapped, k.createdAt,
                try k.kdf.map { String(decoding: try jsonEncoder.encode($0), as: UTF8.self) })
        }
    }
}
