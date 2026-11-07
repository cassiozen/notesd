package notes.core.sync

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import notes.core.db.SyncEvent

/** §6.2 between devices: bodies both sides changed merge block by block, and Undo splits a merge back. */
class BodyMergeSyncTest {
    private val server = TestServer()
    private val clients = ArrayList<TestClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        server.close()
    }

    private suspend fun client() = TestClient(server).also { clients += it }.signIn()

    private suspend fun syncAll(vararg cs: TestClient) {
        repeat(2) { for (c in cs) assertEquals(SyncOutcome.Done, c.sync()) }
    }

    /** a and b share note "n" with [title] and [body]; then a sets [fromA] and b [fromB], a syncing first. */
    private suspend fun edited(
        title: String,
        body: String,
        fromA: String,
        fromB: String
    ): Pair<TestClient, TestClient> {
        val a = client()
        val b = client()
        a.save("n", title, body)
        syncAll(a, b)
        a.save("n", title, fromA)
        b.save("n", title, fromB)
        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(SyncOutcome.Done, b.sync())
        syncAll(a, b)
        return a to b
    }

    @Test
    fun `different blocks merge without a copy`() = runBlocking<Unit> {
        val (a, b) = edited("Plan", "One\n\nTwo\n", "One!\n\nTwo\n", "One\n\nTwo!\n")
        val expected = setOf(NoteView("n", "Plan", "One!\n\nTwo!\n", null))
        assertEquals(expected, a.live())
        assertEquals(expected, b.live())
        assertEquals(SyncEvent.Merged("n", "Plan"), b.events.single())
    }

    @Test
    fun `a ticked item and a reworded one merge`() = runBlocking<Unit> {
        val (a, b) = edited(
            "List",
            "- [ ] milk\n- [ ] eggs\n",
            "- [ ] oat milk\n- [ ] eggs\n",
            "- [x] milk\n- [ ] eggs\n"
        )
        assertEquals(setOf(NoteView("n", "List", "- [x] oat milk\n- [ ] eggs\n", null)), a.live())
        assertEquals(a.live(), b.live())
    }

    @Test
    fun `a partial conflict gives both notes every change that merged`() = runBlocking<Unit> {
        val (a, b) = edited(
            "Plan",
            "Intro\n\nTuesday\n\nOutro\n",
            "Intro, edited\n\nWednesday\n\nOutro\n",
            "Intro\n\nThursday\n\nOutro, edited\n"
        )
        val live = a.live()
        assertEquals(live, b.live())
        assertEquals(
            NoteView("n", "Plan", "Intro, edited\n\nWednesday\n\nOutro, edited\n", null),
            live.single {
                it.id ==
                    "n"
            }
        )
        val copy = live.single { it.id != "n" }
        assertEquals(
            NoteView(copy.id, "Plan (Conflicted copy)", "Intro, edited\n\nThursday\n\nOutro, edited\n", "n"),
            copy
        )
        assertIs<SyncEvent.ConflictedCopy>(b.events.single())
        assertNull(b.notes.mergeUndo("n"))
    }

    @Test
    fun `a title both changed still merges the body into both notes`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "Plan", "One\n\nTwo\n")
        syncAll(a, b)
        a.save("n", "Plan A", "One!\n\nTwo\n")
        b.save("n", "Plan B", "One\n\nTwo!\n")
        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(SyncOutcome.Done, b.sync())
        syncAll(a, b)
        val live = b.live()
        assertEquals(NoteView("n", "Plan A", "One!\n\nTwo!\n", null), live.single { it.id == "n" })
        assertEquals("Plan B (Conflicted copy)", live.single { it.id != "n" }.title)
        assertEquals("One!\n\nTwo!\n", live.single { it.id != "n" }.content)
    }

    @Test
    fun `undo puts the note back as the server had it and keeps this device's version as a copy`() = runBlocking<Unit> {
        val (a, b) = edited("Plan", "One\n\nTwo\n", "One!\n\nTwo\n", "One\n\nTwo!\n")
        assertNotNull(b.notes.mergeUndo("n"))
        val copyId = assertNotNull(b.notes.undoMerge("n"))
        assertNull(b.notes.mergeUndo("n"))
        syncAll(b, a)
        val expected =
            setOf(
                NoteView("n", "Plan", "One!\n\nTwo\n", null),
                NoteView(copyId, "Plan (Conflicted copy)", "One\n\nTwo!\n", "n")
            )
        assertEquals(expected, a.live())
        assertEquals(expected, b.live())
    }

    @Test
    fun `undo after a later edit only makes the copy`() = runBlocking<Unit> {
        val (_, b) = edited("Plan", "One\n\nTwo\n", "One!\n\nTwo\n", "One\n\nTwo!\n")
        b.save("n", "Plan", "One!\n\nTwo!\n\nThree\n")
        val copyId = assertNotNull(b.notes.undoMerge("n"))
        assertEquals("One!\n\nTwo!\n\nThree\n", b.note("n")!!.content)
        assertEquals("One\n\nTwo!\n", b.note(copyId)!!.content)
    }

    @Test
    fun `undo is gone once the note is deleted`() = runBlocking<Unit> {
        val (_, b) = edited("Plan", "One\n\nTwo\n", "One!\n\nTwo\n", "One\n\nTwo!\n")
        b.notes.delete("n")
        assertNull(b.notes.mergeUndo("n"))
        assertNull(b.notes.undoMerge("n"))
    }

    @Test
    fun `a save over a sync change merges the body, the typing staying in the note`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "Plan", "One\n\nTwo\n")
        syncAll(a, b)
        a.save("n", "Plan", "One!\n\nTwo\n")
        syncAll(a, b)
        // b's editor opened "One/Two" and its typing reaches the store after sync brought a's edit.
        val saved = b.notes.save("n", "Plan", "One\n\nTwo!\n", basedOn = "Plan" to "One\n\nTwo\n")
        assertEquals("One!\n\nTwo!\n", saved!!.content)
        assertEquals(1, b.live().size)

        b.notes.undoMerge("n")
        val live = b.live()
        assertEquals("One\n\nTwo!\n", live.single { it.id == "n" }.content)
        assertEquals("One!\n\nTwo\n", live.single { it.id != "n" }.content)
    }
}
