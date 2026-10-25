package notes.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The database file at [path], with the bundled SQLite: WAL, a writer and a read-only reader, off the caller's thread. */
fun Database(path: String, io: CoroutineDispatcher = Dispatchers.IO): Database = Database(JvmConnections(path, io))

private class JvmConnections(private val path: String, private val io: CoroutineDispatcher) : Connections {
    override val separateReader = true

    override suspend fun <T> run(block: suspend () -> T): T = withContext(io) { block() }

    override suspend fun openWriter(): SQLiteConnection {
        val conn = driver.open(path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
        conn.execSQL("PRAGMA journal_mode = WAL")
        conn.execSQL("PRAGMA synchronous = NORMAL")
        conn.execSQL("PRAGMA busy_timeout = 5000")
        return conn
    }

    override suspend fun openReader(): SQLiteConnection {
        val conn = driver.open(path, SQLITE_OPEN_READONLY)
        conn.execSQL("PRAGMA busy_timeout = 5000")
        return conn
    }

    private companion object {
        val driver = BundledSQLiteDriver()
    }
}
