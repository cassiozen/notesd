package notes.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Numbered SQL files in resources/migrations, applied in order and tracked with
 * `PRAGMA user_version`. Listed here because an APK's classpath can't be
 * enumerated. Statements are split at a ";" ending a line, so a migration can't
 * hold a trigger body (nor a comment with ";" at its end).
 */
internal object Migrations {
    val files =
        listOf(
            "0001_init.sql",
            "0002_full_sync.sql",
            "0003_tags.sql",
            "0004_lock_keys.sql",
            "0005_reindex_locked.sql",
            "0006_pending_images.sql",
            "0007_locked_images.sql",
            "0008_spaces.sql",
            "0009_space_icons.sql"
        )

    fun apply(conn: SQLiteConnection) {
        val current = conn.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0).toInt() else 0 }
        for (version in current + 1..files.size) {
            conn.execSQL("BEGIN IMMEDIATE")
            try {
                for (statement in statements(load(files[version - 1]))) conn.execSQL(statement)
                conn.execSQL("PRAGMA user_version = $version")
                conn.execSQL("COMMIT")
            } catch (e: Throwable) {
                conn.execSQL("ROLLBACK")
                throw e
            }
        }
    }

    private fun load(name: String): String =
        Migrations::class.java.getResourceAsStream("/migrations/$name")?.use { it.readBytes().decodeToString() }
            ?: error("Missing migration $name")

    fun statements(sql: String): List<String> = sql.split(Regex(";[ \\t]*(--[^\\n]*)?(\\n|$)"))
        .map { it.trim() }
        .filter { s -> s.lines().any { it.isNotBlank() && !it.trimStart().startsWith("--") } }
}
