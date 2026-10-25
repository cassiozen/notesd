package notes.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

/**
 * A database on sqlite-wasm's oo1 [db], called directly. For an engine
 * running in the same worker as SQLite: no message per statement, unlike
 * androidx.sqlite's WebWorkerSQLiteDriver.
 */
fun directDatabase(db: dynamic, capi: dynamic): Database = Database(DirectConnections(db, capi))

private class DirectConnections(private val db: dynamic, private val capi: dynamic) : Connections {
    override val separateReader = false

    override suspend fun <T> run(block: suspend () -> T): T = block()

    override suspend fun openWriter(): SQLiteConnection = DirectConnection(db, capi)

    override suspend fun openReader(): SQLiteConnection = error("One connection in the browser")
}

/**
 * Statements are kept after close for reuse by SQL text: [Sql] prepares one per call,
 * and the engine runs the same few dozen statements thousands of times.
 */
private class DirectConnection(private val db: dynamic, private val capi: dynamic) : SQLiteConnection {
    private val idle = HashMap<String, ArrayDeque<dynamic>>()

    override suspend fun prepare(sql: String): SQLiteStatement {
        val st = idle[sql]?.removeLastOrNull() ?: db.prepare(sql)
        return DirectStatement(st, capi) { recycle(sql, st) }
    }

    private fun recycle(sql: String, st: dynamic) {
        st.reset(true)
        idle.getOrPut(sql) { ArrayDeque() }.addLast(st)
    }

    override fun close() {
        for (queue in idle.values) for (st in queue) st.finalize()
        idle.clear()
        db.close()
    }
}

private class DirectStatement(private val st: dynamic, private val capi: dynamic, private val release: () -> Unit) :
    SQLiteStatement {
    private var closed = false

    override fun bindBlob(index: Int, value: ByteArray) {
        val bytes = value.unsafeCast<Int8Array>()
        st.bind(index, Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length))
    }

    override fun bindDouble(index: Int, value: Double) {
        st.bind(index, value)
    }

    // Values here (ids, ms timestamps, counters) fit in a double's 53 bits.
    override fun bindLong(index: Int, value: Long) {
        st.bind(index, value.toDouble())
    }

    override fun bindText(index: Int, value: String) {
        st.bind(index, value)
    }

    override fun bindNull(index: Int) {
        st.bind(index, null)
    }

    override fun getBlob(index: Int): ByteArray {
        val bytes: Uint8Array = st.getBlob(index)
        return Int8Array(bytes.buffer, bytes.byteOffset, bytes.length).unsafeCast<ByteArray>()
    }

    override fun getDouble(index: Int): Double = st.getFloat(index).unsafeCast<Double>()

    override fun getLong(index: Int): Long = st.getFloat(index).unsafeCast<Double>().toLong()

    override fun getText(index: Int): String = st.getString(index).unsafeCast<String?>() ?: ""

    override fun isNull(index: Int): Boolean = getColumnType(index) == 5

    override fun getColumnCount(): Int = st.columnCount.unsafeCast<Int>()

    override fun getColumnName(index: Int): String = st.getColumnName(index).unsafeCast<String>()

    override fun getColumnType(index: Int): Int = capi.sqlite3_column_type(st.pointer, index).unsafeCast<Int>()

    override suspend fun step(): Boolean = st.step().unsafeCast<Boolean>()

    override fun reset() {
        st.reset()
    }

    override fun clearBindings() {
        st.clearBindings()
    }

    override fun close() {
        if (closed) return
        closed = true
        release()
    }
}
