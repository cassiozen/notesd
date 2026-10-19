import Foundation

/// Numbered SQL files in Resources/migrations, applied in order and tracked
/// with `PRAGMA user_version`. Each file runs whole, in one transaction.
///
/// iOS starts at Android's schema as of its 0007, collapsed into one file
/// (there were no iOS databases to upgrade). Add later changes as new files.
enum Migrations {
    static let files = ["0001_init", "0002_pending_images", "0003_lock_keys", "0004_spaces", "0005_space_icons"]

    static func apply(_ conn: Connection) throws {
        let current = try Sql(conn).int64("PRAGMA user_version")
        guard current < files.count else { return }
        for version in (Int(current) + 1)...files.count {
            try conn.exec("BEGIN IMMEDIATE")
            do {
                try conn.exec(load(files[version - 1]))
                try conn.exec("PRAGMA user_version = \(version)")
                try conn.exec("COMMIT")
            } catch {
                try? conn.exec("ROLLBACK")
                throw error
            }
        }
    }

    private static func load(_ name: String) throws -> String {
        guard let url = Bundle.module.url(forResource: name, withExtension: "sql", subdirectory: "migrations") else {
            throw DatabaseError(code: 0, message: "missing migration \(name)")
        }
        return try String(contentsOf: url, encoding: .utf8)
    }
}
