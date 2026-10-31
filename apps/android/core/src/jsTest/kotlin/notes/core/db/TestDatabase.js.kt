package notes.core.db

import kotlinx.coroutines.await
import notes.core.markdown.webCanonical

private var sqlite3: dynamic = null

actual suspend fun openTestDatabase(): TestDatabase {
    if (sqlite3 == null) sqlite3 = sqlite3InitModule().await()
    // The web editor's codec isn't available to Kotlin tests; no shared test depends on Markdown style.
    if (webCanonical == null) webCanonical = { it }
    val db = js("Reflect").construct(sqlite3.oo1.DB, arrayOf(":memory:"))
    return TestDatabase(directDatabase(db, sqlite3.capi)) {}
}
