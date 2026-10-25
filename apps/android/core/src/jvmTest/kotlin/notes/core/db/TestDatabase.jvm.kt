package notes.core.db

import java.io.File
import java.nio.file.Files

actual suspend fun openTestDatabase(): TestDatabase {
    val dir = Files.createTempDirectory("notes-store").toFile()
    return TestDatabase(Database(File(dir, "notes.db").path)) { dir.deleteRecursively() }
}
