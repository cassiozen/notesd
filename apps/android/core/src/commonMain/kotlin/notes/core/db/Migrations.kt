package notes.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Numbered SQL files in src/commonMain/migrations, applied in order and tracked
 * with `PRAGMA user_version`. The build compiles them into [MIGRATIONS], since
 * neither an APK's classpath nor a browser bundle can be listed. Statements are
 * split at a ";" ending a line, so a migration can't hold a trigger body (nor a
 * comment with ";" at its end).
 */
internal object Migrations {
    val files = MIGRATIONS.map { it.first }

    suspend fun apply(conn: SQLiteConnection) {
        val current = conn.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0).toInt() else 0 }
        for (version in current + 1..files.size) {
            conn.executeSQL("BEGIN IMMEDIATE")
            try {
                for (statement in statements(MIGRATIONS[version - 1].second)) conn.executeSQL(statement)
                conn.executeSQL("PRAGMA user_version = $version")
                conn.executeSQL("COMMIT")
            } catch (e: Throwable) {
                withContext(NonCancellable) { conn.executeSQL("ROLLBACK") }
                throw e
            }
        }
    }

    fun statements(sql: String): List<String> = sql.split(Regex(";[ \\t]*(--[^\\n]*)?(\\n|$)"))
        .map { it.trim() }
        .filter { s -> s.lines().any { it.isNotBlank() && !it.trimStart().startsWith("--") } }
}
