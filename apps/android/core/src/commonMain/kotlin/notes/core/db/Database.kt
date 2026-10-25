package notes.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where a [Database]'s connections come from. The JVM opens a WAL writer and a
 * read-only reader on its own thread pool; the browser has one connection to a
 * worker (OPFS has no WAL), so reads queue behind writes there.
 */
interface Connections {
    /** False: reads share the writer and its lock. */
    val separateReader: Boolean

    /** Runs database work where it may block (an IO dispatcher; inline in the browser). */
    suspend fun <T> run(block: suspend () -> T): T

    /** Opens the connection that creates the file and the schema; migrations run on it after. */
    suspend fun openWriter(): SQLiteConnection

    suspend fun openReader(): SQLiteConnection

    fun close(conn: SQLiteConnection) = conn.close()
}

/**
 * The local SQLite database: one writer connection (transactions are
 * `BEGIN IMMEDIATE`, one at a time) and, where [Connections] allows, one
 * read-only connection, so reads never wait for a write in WAL mode.
 * Connections open lazily on the first call. Both drivers ship their own
 * SQLite with FTS5, so search behaves the same everywhere.
 */
class Database(private val connections: Connections) : AutoCloseable {
    private val writeLock = Mutex()
    private val readLock = if (connections.separateReader) Mutex() else writeLock
    private var writer: SQLiteConnection? = null
    private var reader: SQLiteConnection? = null

    private val changesFlow =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after every committed write. Observers re-query; at this scale that's cheap. */
    val changes: SharedFlow<Unit> = changesFlow

    suspend fun <T> write(block: suspend (Sql) -> T): T {
        val result = writeLock.withLock {
            connections.run {
                val conn = writerConnection()
                conn.executeSQL("BEGIN IMMEDIATE")
                try {
                    block(Sql(conn)).also { conn.executeSQL("COMMIT") }
                } catch (e: Throwable) {
                    // Cancelled mid-transaction, the rollback must still reach the connection.
                    withContext(NonCancellable) { conn.executeSQL("ROLLBACK") }
                    throw e
                }
            }
        }
        changesFlow.tryEmit(Unit)
        return result
    }

    suspend fun <T> read(block: suspend (Sql) -> T): T = readLock.withLock {
        connections.run {
            val conn = readerConnection()
            // One snapshot for the whole block, even if it runs several queries.
            conn.executeSQL("BEGIN")
            try {
                block(Sql(conn))
            } finally {
                withContext(NonCancellable) { conn.executeSQL("COMMIT") }
            }
        }
    }

    private suspend fun writerConnection(): SQLiteConnection = writer ?: run {
        val conn = connections.openWriter()
        Migrations.apply(conn)
        rebuildIfAsked(conn)
        writer = conn
        conn
    }

    /** A migration that changes derived data (search text, tags) asks for a rebuild. */
    private suspend fun rebuildIfAsked(conn: SQLiteConnection) {
        val sql = Sql(conn)
        if (sql.long("SELECT count(*) FROM rebuild_index") == 0L) return
        conn.executeSQL("BEGIN IMMEDIATE")
        try {
            sql.rebuildIndex()
            conn.executeSQL("COMMIT")
        } catch (e: Throwable) {
            withContext(NonCancellable) { conn.executeSQL("ROLLBACK") }
            throw e
        }
    }

    private suspend fun readerConnection(): SQLiteConnection {
        // Sharing the writer: readLock is writeLock, already held.
        if (!connections.separateReader) return writerConnection()
        return reader ?: run {
            // The writer creates the file and the schema; a read-only connection can't.
            writeLock.withLock { writerConnection() }
            val conn = connections.openReader()
            reader = conn
            conn
        }
    }

    override fun close() {
        reader?.let(connections::close)
        writer?.let(connections::close)
        reader = null
        writer = null
    }
}

/** Thin helpers over a connection: positional `?` arguments, rows mapped by the caller. */
class Sql(private val conn: SQLiteConnection) {
    suspend fun exec(sql: String, vararg args: Any?) {
        statement(sql, args).use { it.step() }
    }

    suspend fun <T> query(sql: String, vararg args: Any?, row: (SQLiteStatement) -> T): List<T> =
        statement(sql, args).use { st ->
            buildList { while (st.step()) add(row(st)) }
        }

    suspend fun <T> one(sql: String, vararg args: Any?, row: (SQLiteStatement) -> T): T? =
        statement(sql, args).use { st -> if (st.step()) row(st) else null }

    suspend fun long(sql: String, vararg args: Any?): Long = one(sql, *args) { it.getLong(0) } ?: 0

    private suspend fun statement(sql: String, args: Array<out Any?>): SQLiteStatement {
        val st = conn.prepare(sql)
        try {
            args.forEachIndexed { i, arg ->
                val index = i + 1
                when (arg) {
                    null -> st.bindNull(index)
                    is String -> st.bindText(index, arg)
                    is Long -> st.bindLong(index, arg)
                    is Int -> st.bindLong(index, arg.toLong())
                    is Boolean -> st.bindLong(index, if (arg) 1 else 0)
                    is Double -> st.bindDouble(index, arg)
                    else -> error("Can't bind ${arg::class.simpleName}")
                }
            }
        } catch (e: Throwable) {
            st.close()
            throw e
        }
        return st
    }
}

fun SQLiteStatement.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)

fun SQLiteStatement.textOrNull(column: Int): String? = if (isNull(column)) null else getText(column)
