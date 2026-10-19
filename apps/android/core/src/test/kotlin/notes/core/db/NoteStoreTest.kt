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

class NoteStoreTest {
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
    fun `a deleted note's locked bodies forget their images`() = runBlocking {
        val body = LockedNotes.encrypt(ByteArray(32), "![](notesd:image/${"a".repeat(64)}?w=1&h=1)\n")
        store.save("n", "Secret", body, imageIds = listOf("a".repeat(64)))
        val rows = suspend { db.read { it.long("SELECT count(*) FROM locked_images") } }
        assertEquals(1L, rows())
        store.delete("n")
        assertEquals(0L, rows())
    }

    @Test
    fun `a blank new note is not created`() = runBlocking {
        assertNull(store.save("a", "", "  \n"))
        assertNull(store.get("a"))
        assertEquals(emptyList(), store.outbox())
    }

    @Test
    fun `creates, lists and reads a note`() = runBlocking {
        store.save("a", "Groceries", "- **Milk**\n- Bread\n")
        now = 2_000
        store.save("b", "Later", "")
        val a = store.get("a")!!
        assertEquals("Groceries", a.title)
        assertEquals(1_000, a.createdAt)
        assertTrue(a.dirty)
        assertNull(a.serverRevision)
        assertEquals(listOf("b", "a"), store.list().map { it.id })
        assertEquals("Milk\nBread", store.list()[1].preview)
    }

    @Test
    fun `edits before anything is sent collapse into the create`() = runBlocking {
        store.save("a", "One", "")
        now = 2_000
        store.save("a", "Two", "body\n")
        val ops = store.outbox()
        assertEquals(1, ops.size)
        assertEquals("create", ops[0].type)
        assertTrue(ops[0].payloadJson.contains("\"title\":\"Two\""))
        assertTrue(ops[0].payloadJson.contains("\"createdAt\":1000"))
        assertTrue(ops[0].payloadJson.contains("\"updatedAt\":2000"))
    }

    @Test
    fun `an edit after a send queues a new operation, and later edits coalesce into it`() = runBlocking {
        store.save("a", "One", "")
        store.markSent(store.outbox().single().operationId)
        store.save("a", "Two", "")
        store.save("a", "Three", "")
        val ops = store.outbox()
        assertEquals(listOf("create", "update"), ops.map { it.type })
        assertTrue(ops[0].payloadJson.contains("\"One\""), "a sent payload is frozen")
        assertTrue(ops[1].payloadJson.contains("\"Three\""))
    }

    @Test
    fun `saving unchanged text does nothing`() = runBlocking {
        store.save("a", "One", "x\n")
        now = 5_000
        store.save("a", "One", "x\n")
        assertEquals(1_000, store.get("a")!!.updatedAt)
    }

    @Test
    fun `deleting a never-sent note removes every trace`() = runBlocking {
        store.save("a", "One", "")
        store.delete("a")
        assertNull(store.get("a"))
        assertEquals(emptyList(), store.outbox())
        assertEquals(emptyList(), store.search("one"))
    }

    @Test
    fun `deleting a sent note leaves a tombstone and a delete operation`() = runBlocking {
        store.save("a", "One", "")
        store.markSent(store.outbox().single().operationId)
        store.save("a", "Two", "")
        store.delete("a")
        val a = store.get("a")!!
        assertNotNull(a.deletedAt)
        assertEquals(emptyList(), store.list())
        assertEquals(emptyList(), store.search("two"))
        assertEquals(listOf("create", "delete"), store.outbox().map { it.type }, "the unsent edit is dropped")
        assertNull(store.save("a", "Three", ""), "a deleted note can't be edited")
    }

    @Test
    fun `search matches prefixes, ignores diacritics and ranks titles first`() = runBlocking {
        store.save("body", "Shopping", "Visit the café on Main\n")
        store.save("title", "Cafe list", "Places\n")
        store.save("other", "Unrelated", "Nothing here\n")
        assertEquals(listOf("title", "body"), store.search("cafe").map { it.id })
        assertEquals(listOf("title", "body"), store.search("caf").map { it.id })
        assertEquals(listOf("body"), store.search("café main").map { it.id })
        val hit = store.search("main").single()
        assertEquals("Visit the café on ${Search.MARK_OPEN}Main${Search.MARK_CLOSE}", hit.excerpt)
    }

    @Test
    fun `search indexes plain text, not Markdown syntax`() = runBlocking {
        store.save("a", "T", "[link text](https://example.com/secret)\n")
        assertEquals(1, store.search("link").size)
        assertEquals(0, store.search("secret").size)
    }

    @Test
    fun `search follows edits immediately`() = runBlocking {
        store.save("a", "T", "apple\n")
        store.save("a", "T", "banana\n")
        assertEquals(0, store.search("apple").size)
        assertEquals(1, store.search("banana").size)
    }

    @Test
    fun `odd input never breaks search`() = runBlocking {
        store.save("a", "T", "x\n")
        for (q in listOf("\"", "*", "NEAR(", "a OR", "-", "^", "")) store.search(q)
    }

    @Test
    fun `reindex rebuilds the index from the notes`() = runBlocking {
        store.save("a", "Alpha", "")
        store.save("b", "Beta", "")
        db.write { it.exec("DELETE FROM notes_fts") }
        assertEquals(0, store.search("alpha").size)
        assertEquals(2, store.reindex())
        assertEquals(1, store.search("alpha").size)
        assertEquals("", store.list().first().preview)
    }

    @Test
    fun `wipe removes everything`() = runBlocking {
        store.save("a", "Alpha", "")
        store.wipe()
        assertEquals(emptyList(), store.list())
        assertEquals(emptyList(), store.outbox())
    }

    @Test
    fun `writes notify observers`() = runBlocking {
        val changed = async { store.changes.first() }
        yield()
        store.save("a", "Alpha", "")
        changed.await()
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

    // The editor saves text it started from [basedOn]; sync may have changed the note since.

    private suspend fun syncChanged(id: String, title: String, content: String) {
        db.write { it.exec("UPDATE notes SET title = ?, content = ? WHERE id = ?", title, content, id) }
    }

    @Test
    fun `an editor save merges with a sync change to another field`() = runBlocking {
        store.save("a", "Title", "body\n")
        syncChanged("a", "Title", "synced body\n")
        store.save("a", "Typed title", "body\n", basedOn = "Title" to "body\n")
        val a = store.get("a")!!
        assertEquals("Typed title", a.title)
        assertEquals("synced body\n", a.content)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `an editor save over a sync change to the same field keeps both`() = runBlocking {
        store.save("a", "Title", "body\n")
        syncChanged("a", "Title", "synced body\n")
        store.save("a", "Title", "typed body\n", basedOn = "Title" to "body\n")
        assertEquals("typed body\n", store.get("a")!!.content)
        val copy = store.list().single { it.id != "a" }
        val stored = store.get(copy.id)!!
        assertEquals("Title (Conflicted copy)", stored.title)
        assertEquals("synced body\n", stored.content)
        assertEquals("a", stored.conflictOf)
    }

    @Test
    fun `local edits are announced, and only local edits`() = runBlocking {
        val edited = async { store.localEdits.first() }
        yield()
        store.save("a", "Alpha", "")
        edited.await()
    }

    @Test
    fun `tags follow edits, deletes and nesting`() = runBlocking {
        store.save("a", "Roof #Home", "Call about #home/repairs/roof\n")
        now = 2_000
        store.save("b", "", "#home/garden and #work\n")
        store.save("c", "Plain", "no tags, # heading-ish, C#\n")
        store.save("d", "", "#homework\n")

        assertEquals(
            listOf(
                TagCount("home", "home", 2),
                TagCount("home/garden", "home/garden", 1),
                TagCount("home/repairs", "home/repairs", 1),
                TagCount("home/repairs/roof", "home/repairs/roof", 1),
                TagCount("homework", "homework", 1),
                TagCount("work", "work", 1)
            ),
            store.tags()
        )
        // A parent matches its nested tags, not a tag that merely starts with it.
        assertEquals(setOf("a", "b"), store.list(NoteFilter.Tag("home", "home")).map { it.id }.toSet())
        assertEquals(listOf("a"), store.list(NoteFilter.Tag("home/repairs", "home/repairs")).map { it.id })
        assertEquals(listOf("c"), store.list(NoteFilter.Untagged).map { it.id })
        assertEquals(listOf("b"), store.search("home", NoteFilter.Tag("work", "work")).map { it.id })

        now = 3_000
        store.save("b", "", "#WORK only\n")
        store.delete("a")
        assertEquals(listOf(TagCount("homework", "homework", 1), TagCount("work", "WORK", 1)), store.tags())
        assertEquals(listOf("c"), store.list(NoteFilter.Untagged).map { it.id })
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
    fun `a locked body is neither indexed, tagged nor previewed`() = runBlocking {
        val key = notes.core.crypto.LockedNotes.newKey()
        val sealed = notes.core.crypto.LockedNotes.encrypt(key, "PIN 1234 #money\n")
        store.save("a", "Bank #private", sealed)
        val summary = store.list().single()
        assertTrue(summary.locked)
        assertEquals("", summary.preview)
        assertEquals(emptyList(), store.search("1234"))
        assertEquals(listOf("private"), store.tags().map { it.key })
        assertTrue(store.search("bank").single().locked)
        store.save("b", "Open", "plain\n")
        assertEquals(listOf("a"), store.list(NoteFilter.Locked).map { it.id })
        assertEquals(1, store.lockedCount())

        // Unlocking is an ordinary edit: the body is indexed again.
        store.save("a", "Bank #private", "PIN 1234 #money\n")
        assertEquals(listOf("a"), store.search("1234").map { it.id })
        assertEquals(listOf("money", "private"), store.tags().map { it.key })
        assertEquals(emptyList(), store.list(NoteFilter.Locked))
        assertEquals(0, store.lockedCount())
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
