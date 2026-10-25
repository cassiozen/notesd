package notes.core.db

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest

/** A fresh, empty database for one test: a temporary file on the JVM, in memory under Node. */
expect suspend fun openTestDatabase(): TestDatabase

class TestDatabase(val db: Database, private val cleanUp: () -> Unit) {
    fun close() {
        db.close()
        cleanUp()
    }
}

/** A test's database and a store on it, with a clock the test sets. */
class StoreTest(val db: Database) {
    var now = 1_000L
    val store = NoteStore(db) { now }
}

fun storeTest(block: suspend StoreTest.() -> Unit): TestResult = runTest {
    val t = openTestDatabase()
    try {
        StoreTest(t.db).block()
    } finally {
        t.close()
    }
}
