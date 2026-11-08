import Foundation
import SQLite3
import Synchronization

/// The local SQLite database: one writer connection (transactions are
/// `BEGIN IMMEDIATE`, one at a time) and one read-only connection, so reads
/// never wait for a write in WAL mode. Each connection lives on its own serial
/// queue, off the caller's thread, and opens on first use. The system SQLite
/// has FTS5 (checked by the tests), so search behaves as on the server.
public final class Database: Sendable {
    private let writer: ConnectionActor
    private let reader: ConnectionActor
    private let changeFeed = Broadcast<Void>()
    private let writerOpened = Mutex(false)

    public init(path: String) {
        writer = ConnectionActor(label: "notesd.db.write") {
            let conn = try Connection(path: path, readOnly: false)
            try conn.exec("PRAGMA journal_mode = WAL")
            try conn.exec("PRAGMA synchronous = NORMAL")
            try conn.exec("PRAGMA busy_timeout = 5000")
            try Migrations.apply(conn)
            try rebuildIfAsked(conn)
            return conn
        }
        reader = ConnectionActor(label: "notesd.db.read") {
            let conn = try Connection(path: path, readOnly: true)
            try conn.exec("PRAGMA busy_timeout = 5000")
            return conn
        }
    }

    /// Emits after every committed write. Observers re-query; at this scale that's cheap.
    public func changes() -> AsyncStream<Void> { changeFeed.stream() }

    public func write<T: Sendable>(_ body: @Sendable (Sql) throws -> T) async throws -> T {
        let result = try await writer.run { conn in
            try conn.exec("BEGIN IMMEDIATE")
            do {
                let value = try body(Sql(conn))
                try conn.exec("COMMIT")
                return value
            } catch {
                try? conn.exec("ROLLBACK")
                throw error
            }
        }
        changeFeed.send(())
        return result
    }

    public func read<T: Sendable>(_ body: @Sendable (Sql) throws -> T) async throws -> T {
        // The writer creates the file and the schema; a read-only connection can't. Only the first read
        // waits for it: afterwards a read queued behind a long write would defeat the separate reader.
        if !writerOpened.withLock({ $0 }) {
            try await writer.run { _ in }
            writerOpened.withLock { $0 = true }
        }
        return try await reader.run { conn in
            // One snapshot for the whole block, even if it runs several queries.
            try conn.exec("BEGIN")
            defer { try? conn.exec("COMMIT") }
            return try body(Sql(conn))
        }
    }

    /// Closes both connections; the next call opens them again.
    public func close() async {
        writerOpened.withLock { $0 = false }
        await reader.close()
        await writer.close()
    }
}

/// A migration that changes derived data (search text, tags) asks for a rebuild.
private func rebuildIfAsked(_ conn: Connection) throws {
    let sql = Sql(conn)
    guard try sql.int64("SELECT count(*) FROM rebuild_index") > 0 else { return }
    try conn.exec("BEGIN IMMEDIATE")
    do {
        _ = try sql.rebuildIndex()
        try conn.exec("COMMIT")
    } catch {
        try? conn.exec("ROLLBACK")
        throw error
    }
}

public struct DatabaseError: Error, CustomStringConvertible {
    public let code: Int32
    public let message: String

    public var description: String { "SQLite error \(code): \(message)" }
}

/// One SQLite connection. Only its actor touches it.
final class Connection {
    let handle: OpaquePointer

    init(path: String, readOnly: Bool) throws {
        var db: OpaquePointer?
        let flags = (readOnly ? SQLITE_OPEN_READONLY : SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE) | SQLITE_OPEN_NOMUTEX
        let rc = sqlite3_open_v2(path, &db, flags, nil)
        guard rc == SQLITE_OK, let db else {
            let message = db.map { String(cString: sqlite3_errmsg($0)) } ?? "can't open \(path)"
            sqlite3_close_v2(db)
            throw DatabaseError(code: rc, message: message)
        }
        handle = db
    }

    deinit {
        sqlite3_close_v2(handle)
    }

    /// Runs one or more statements with no arguments.
    func exec(_ sql: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(handle, sql, nil, nil, &error)
        if rc != SQLITE_OK {
            let message = error.map { String(cString: $0) } ?? "unknown"
            sqlite3_free(error)
            throw DatabaseError(code: rc, message: message)
        }
    }

    func error(_ rc: Int32) -> DatabaseError {
        DatabaseError(code: rc, message: String(cString: sqlite3_errmsg(handle)))
    }
}

/// Owns a connection and runs work on it one block at a time, on its own queue
/// so SQLite's blocking calls never hold up Swift's cooperative threads.
private actor ConnectionActor {
    private let queue: DispatchSerialQueue
    private let open: @Sendable () throws -> Connection
    private var connection: Connection?

    nonisolated var unownedExecutor: UnownedSerialExecutor { queue.asUnownedSerialExecutor() }

    init(label: String, open: @escaping @Sendable () throws -> Connection) {
        queue = DispatchSerialQueue(label: label)
        self.open = open
    }

    func run<T: Sendable>(_ body: (Connection) throws -> T) throws -> T {
        let conn = try connection ?? open()
        connection = conn
        return try body(conn)
    }

    func close() {
        connection = nil
    }
}

private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// A value a statement can bind.
public protocol SQLValue {
    func bind(to statement: OpaquePointer, at index: Int32) -> Int32
}

extension String: SQLValue {
    public func bind(to statement: OpaquePointer, at index: Int32) -> Int32 {
        // With an explicit length, so text holding a NUL survives.
        var copy = self
        return copy.withUTF8 { buffer in
            sqlite3_bind_text(statement, index, buffer.baseAddress, Int32(buffer.count), transient)
        }
    }
}

extension Int64: SQLValue {
    public func bind(to statement: OpaquePointer, at index: Int32) -> Int32 {
        sqlite3_bind_int64(statement, index, self)
    }
}

extension Int: SQLValue {
    public func bind(to statement: OpaquePointer, at index: Int32) -> Int32 {
        sqlite3_bind_int64(statement, index, Int64(self))
    }
}

extension Bool: SQLValue {
    public func bind(to statement: OpaquePointer, at index: Int32) -> Int32 {
        sqlite3_bind_int64(statement, index, self ? 1 : 0)
    }
}

extension Double: SQLValue {
    public func bind(to statement: OpaquePointer, at index: Int32) -> Int32 {
        sqlite3_bind_double(statement, index, self)
    }
}

/// One result row; columns are read by index.
public struct Row {
    fileprivate let statement: OpaquePointer

    public func isNull(_ column: Int32) -> Bool {
        sqlite3_column_type(statement, column) == SQLITE_NULL
    }

    public func int64(_ column: Int32) -> Int64 {
        sqlite3_column_int64(statement, column)
    }

    public func int64OrNil(_ column: Int32) -> Int64? {
        isNull(column) ? nil : int64(column)
    }

    public func text(_ column: Int32) -> String {
        guard let bytes = sqlite3_column_text(statement, column) else { return "" }
        let count = Int(sqlite3_column_bytes(statement, column))
        return String(decoding: UnsafeBufferPointer(start: bytes, count: count), as: UTF8.self)
    }

    public func textOrNil(_ column: Int32) -> String? {
        isNull(column) ? nil : text(column)
    }
}

/// Thin helpers over a connection: positional `?` arguments, rows mapped by the caller.
public final class Sql {
    private let conn: Connection

    init(_ conn: Connection) {
        self.conn = conn
    }

    public func exec(_ sql: String, _ args: (any SQLValue)?...) throws {
        try execArray(sql, args)
    }

    /// `exec` with the arguments as an array, for statements built from parts.
    public func execArray(_ sql: String, _ args: [(any SQLValue)?]) throws {
        try withStatement(sql, args) { st in
            let rc = sqlite3_step(st)
            guard rc == SQLITE_DONE || rc == SQLITE_ROW else { throw conn.error(rc) }
        }
    }

    public func query<T>(_ sql: String, _ args: (any SQLValue)?..., row: (Row) throws -> T) throws -> [T] {
        try queryArray(sql, args, row: row)
    }

    /// `query` with the arguments as an array, for statements built from parts.
    public func queryArray<T>(_ sql: String, _ args: [(any SQLValue)?], row: (Row) throws -> T) throws -> [T] {
        try withStatement(sql, args) { st in
            var out: [T] = []
            while true {
                let rc = sqlite3_step(st)
                if rc == SQLITE_DONE { return out }
                guard rc == SQLITE_ROW else { throw conn.error(rc) }
                out.append(try row(Row(statement: st)))
            }
        }
    }

    public func one<T>(_ sql: String, _ args: (any SQLValue)?..., row: (Row) throws -> T) throws -> T? {
        try withStatement(sql, args) { st in
            let rc = sqlite3_step(st)
            if rc == SQLITE_DONE { return nil }
            guard rc == SQLITE_ROW else { throw conn.error(rc) }
            return try row(Row(statement: st))
        }
    }

    /// The first column of the first row, 0 when there is none.
    public func int64(_ sql: String, _ args: (any SQLValue)?...) throws -> Int64 {
        try withStatement(sql, args) { st in
            let rc = sqlite3_step(st)
            if rc == SQLITE_DONE { return 0 }
            guard rc == SQLITE_ROW else { throw conn.error(rc) }
            return sqlite3_column_int64(st, 0)
        }
    }

    private func withStatement<T>(_ sql: String, _ args: [(any SQLValue)?], _ body: (OpaquePointer) throws -> T)
        throws -> T
    {
        var statement: OpaquePointer?
        let rc = sqlite3_prepare_v2(conn.handle, sql, -1, &statement, nil)
        guard rc == SQLITE_OK, let st = statement else { throw conn.error(rc) }
        defer { sqlite3_finalize(st) }
        for (i, arg) in args.enumerated() {
            let index = Int32(i + 1)
            let rc = arg.map { $0.bind(to: st, at: index) } ?? sqlite3_bind_null(st, index)
            guard rc == SQLITE_OK else { throw conn.error(rc) }
        }
        return try body(st)
    }
}
