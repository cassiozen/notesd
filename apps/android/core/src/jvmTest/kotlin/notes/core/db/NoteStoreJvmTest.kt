package notes.core.db

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import notes.core.crypto.LockedNotes

/** The store tests that need a database file (reopening, upgrades) or real lock crypto. */
class NoteStoreJvmTest {
    private val dir: File = Files.createTempDirectory("notes-store").toFile()
    private var now = 1_000L
    private val db = Database(File(dir, "notes.db").path)
    private val store = NoteStore(db) { now }

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun `data and schema survive reopening`() = runBlocking {
        store.save("a", "Alpha", "")
        db.close()
        val again = Database(File(dir, "notes.db").path)
        try {
            assertEquals("Alpha", NoteStore(again).get("a")!!.title)
            assertEquals(Migrations.files.size.toLong(), again.read { it.long("PRAGMA user_version") })
        } finally {
            again.close()
        }
    }

    @Test
    fun `an upgraded database fills in tags once`() = runBlocking {
        store.save("a", "", "#kept\n")
        db.close()
        // As a database from before tags: the rows missing, a rebuild requested.
        val conn = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(File(dir, "notes.db").path)
        conn.prepare("DELETE FROM note_tags").use { it.step() }
        conn.prepare("INSERT INTO rebuild_index (id) VALUES (1)").use { it.step() }
        conn.close()

        val reopened = Database(File(dir, "notes.db").path)
        try {
            assertEquals(listOf(TagCount("kept", "kept", 1)), NoteStore(reopened).tags())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `an upgrade drops ciphertext indexed before locked notes were known`() = runBlocking {
        val sealed = notes.core.crypto.LockedNotes.encrypt(notes.core.crypto.LockedNotes.newKey(), "PIN\n")
        store.save("a", "Bank", sealed)
        db.close()
        // As a database at version 4 whose indexer took the envelope for body text.
        val conn = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(File(dir, "notes.db").path)
        conn.prepare("UPDATE notes_fts SET body_text = 'notesd locked v1 ciphertext'").use { it.step() }
        conn.prepare("PRAGMA user_version = 4").use { it.step() }
        // Without what later migrations add, which they can't add twice.
        conn.prepare("ALTER TABLE notes DROP COLUMN space_id").use { it.step() }
        conn.prepare("ALTER TABLE notes DROP COLUMN base_space_id").use { it.step() }
        conn.prepare("DROP TABLE spaces").use { it.step() }
        conn.close()

        val reopened = Database(File(dir, "notes.db").path)
        try {
            val notes = NoteStore(reopened)
            assertEquals("", notes.list().single().preview)
            assertEquals(emptyList(), notes.search("ciphertext"))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `envelopes compare as stored, never as Markdown`() {
        val key = notes.core.crypto.LockedNotes.newKey()
        val a = notes.core.crypto.LockedNotes.encrypt(key, "same\n")
        val b = notes.core.crypto.LockedNotes.encrypt(key, "same\n")
        assertTrue(Merge.sameMarkdown(a, a))
        assertTrue(!Merge.sameMarkdown(a, b))
        assertNull(Merge.field(a, b, notes.core.crypto.LockedNotes.encrypt(key, "theirs\n"), Merge.sameMarkdown))
    }
}
