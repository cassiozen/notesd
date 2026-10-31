package notes.core.sync

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import notes.core.db.NoteFilter
import notes.core.db.SyncEvent

/** §12.1 scenarios against a real notesd, each device with its own database. */
class SyncTest {
    private val server = TestServer()
    private val clients = ArrayList<TestClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        server.close()
    }

    private suspend fun client(pageSize: Int = 100) = TestClient(server, pageSize).also { clients += it }.signIn()

    /** What the server has, as a brand-new device sees it after one sync. */
    private suspend fun serverNotes(): Set<NoteView> {
        val fresh = client()
        assertEquals(SyncOutcome.Done, fresh.sync())
        return fresh.live()
    }

    private suspend fun syncAll(vararg cs: TestClient) {
        repeat(2) { for (c in cs) assertEquals(SyncOutcome.Done, c.sync()) }
    }

    @Test
    fun `different notes on two devices both arrive everywhere`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("a1", "From A", "alpha\n")
        b.save("b1", "From B", "beta\n")
        syncAll(a, b)
        val expected = setOf(NoteView("a1", "From A", "alpha\n", null), NoteView("b1", "From B", "beta\n", null))
        assertEquals(expected, a.live())
        assertEquals(expected, b.live())
        assertEquals(expected, serverNotes())
        assertEquals(0, a.notes.pendingNotes())
    }

    @Test
    fun `tags arrive with the text and leave with it`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "Trip", "Book #travel/flights\n")
        syncAll(a, b)
        assertEquals(listOf("travel", "travel/flights"), b.notes.tags().map { it.key })
        assertEquals(listOf("n"), b.notes.list(NoteFilter.Tag("travel", "travel")).map { it.id })

        a.save("n", "Trip", "Booked\n")
        syncAll(a, b)
        assertEquals(emptyList(), b.notes.tags())
        assertEquals(listOf("n"), b.notes.list(NoteFilter.Untagged).map { it.id })
    }

    @Test
    fun `different fields merge without a copy`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "Title", "body\n")
        syncAll(a, b)
        a.save("n", "New title", "body\n")
        b.save("n", "Title", "new body\n")
        syncAll(a, b)
        val expected = setOf(NoteView("n", "New title", "new body\n", null))
        assertEquals(expected, a.live())
        assertEquals(expected, b.live())
        assertEquals(expected, serverNotes())
    }

    @Test
    fun `the same field changed on both sides keeps both as a conflicted copy`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "Plan", "base\n")
        syncAll(a, b)
        a.save("n", "Plan", "from a\n")
        b.save("n", "Plan", "from b\n")
        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(SyncOutcome.Done, b.sync())
        syncAll(a, b)
        val all = serverNotes()
        assertEquals(2, all.size)
        assertEquals(NoteView("n", "Plan", "from a\n", null), all.single { it.id == "n" })
        val copy = all.single { it.id != "n" }
        assertEquals(NoteView(copy.id, "Plan (Conflicted copy)", "from b\n", "n"), copy)
        assertEquals(all, a.live())
        assertEquals(all, b.live())
        assertIs<SyncEvent.ConflictedCopy>(b.events.single())
    }

    @Test
    fun `another client's Markdown style is not an edit`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "T", "* a\n")
        syncAll(a, b)
        b.save("n", "T2", "- a\n") // what B's editor saves for the same list
        a.save("n", "T", "* a\n* b\n")
        assertEquals(SyncOutcome.Done, a.sync())
        syncAll(b, a)
        assertEquals(setOf(NoteView("n", "T2", "* a\n* b\n", null)), serverNotes())
    }

    @Test
    fun `a lost response is replayed, not applied twice`() = runBlocking<Unit> {
        val a = client()
        a.save("n", "T", "one\n")
        a.loseNextResponse { it.method == "POST" && it.url.encodedPath.endsWith("/notes") }
        assertEquals(SyncOutcome.Offline, a.sync())
        assertEquals(SyncOutcome.Done, a.sync())
        a.save("n", "T", "two\n")
        a.loseNextResponse { it.method == "PUT" }
        assertEquals(SyncOutcome.Offline, a.sync())
        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(setOf(NoteView("n", "T", "two\n", null)), serverNotes())
        assertEquals(false, a.note("n")!!.dirty)
        assertEquals(0, a.notes.pendingNotes())
    }

    @Test
    fun `a download cut off between pages resumes from the stored cursor`() = runBlocking<Unit> {
        val a = client()
        repeat(5) { a.save("n$it", "Note $it", "") }
        assertEquals(SyncOutcome.Done, a.sync())
        val b = client(pageSize = 2)
        var pages = 0
        b.beforeNext({ it.url.encodedPath.endsWith("/sync") }) { pages++ }
        b.failNext { it.url.encodedPath.endsWith("/sync") && pages >= 1 }
        assertEquals(SyncOutcome.Offline, b.sync())
        assertEquals(2, b.live().size)
        b.restart()
        assertEquals(SyncOutcome.Done, b.sync())
        assertEquals(a.live(), b.live())
    }

    @Test
    fun `a crash while making a conflicted copy leaves the edit intact for the next try`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "T", "base\n")
        syncAll(a, b)
        a.save("n", "T", "from a\n")
        b.save("n", "T", "from b\n")
        assertEquals(SyncOutcome.Done, a.sync())
        b.engine.store.faultAfterCopy = { throw IllegalStateException("process killed") }
        assertTrue(runCatching { b.sync() }.isFailure)
        assertEquals(setOf(NoteView("n", "T", "from b\n", null)), b.live())
        assertEquals(true, b.note("n")!!.dirty)
        b.restart()
        assertEquals(SyncOutcome.Done, b.sync())
        val live = b.live()
        assertEquals(2, live.size)
        assertEquals("from a\n", live.single { it.id == "n" }.content)
        assertEquals("from b\n", live.single { it.id != "n" }.content)
    }

    @Test
    fun `a delete reaches the other device`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "T", "x\n")
        syncAll(a, b)
        a.notes.delete("n")
        syncAll(a, b)
        assertEquals(emptySet(), b.live())
        assertEquals(emptySet(), serverNotes())
    }

    @Test
    fun `edited here, deleted elsewhere becomes a recovered copy`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "T", "x\n")
        syncAll(a, b)
        a.notes.delete("n")
        b.save("n", "T", "kept\n")
        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(SyncOutcome.Done, b.sync())
        syncAll(a, b)
        val live = serverNotes()
        val copy = live.single()
        assertEquals("T (Recovered copy)", copy.title)
        assertEquals("kept\n", copy.content)
        assertNull(copy.conflictOf)
        assertEquals(live, a.live())
        assertIs<SyncEvent.RecoveredCopy>(b.events.single())
    }

    @Test
    fun `deleted here, edited elsewhere comes back`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "T", "x\n")
        syncAll(a, b)
        a.save("n", "T", "edited\n")
        b.notes.delete("n")
        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(SyncOutcome.Done, b.sync())
        syncAll(a, b)
        val expected = setOf(NoteView("n", "T", "edited\n", null))
        assertEquals(expected, b.live())
        assertEquals(expected, serverNotes())
        assertIs<SyncEvent.Restored>(b.events.single())
    }

    @Test
    fun `deleted on both sides is simply gone`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "T", "x\n")
        syncAll(a, b)
        a.notes.delete("n")
        b.notes.delete("n")
        syncAll(a, b)
        assertEquals(emptySet(), a.live())
        assertEquals(emptySet(), b.live())
        assertEquals(emptyList(), b.events)
    }

    @Test
    fun `months offline catches up through many small pages`() = runBlocking<Unit> {
        val a = client()
        val b = client(pageSize = 7)
        repeat(30) { a.save("n$it", "Note $it", "v0\n") }
        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(SyncOutcome.Done, b.sync())
        b.offline = true
        assertEquals(SyncOutcome.Offline, b.sync())
        for (round in 1..8) {
            for (i in 0 until 30) if ((i + round) % 3 == 0) a.save("n$i", "Note $i", "v$round\n")
            if (round % 4 == 0) a.notes.delete("n$round")
            assertEquals(SyncOutcome.Done, a.sync())
        }
        b.offline = false
        assertEquals(SyncOutcome.Done, b.sync())
        assertEquals(a.live(), b.live())
        assertEquals(serverNotes(), b.live())
    }

    @Test
    fun `an edit made while its upload is in flight goes out next`() = runBlocking<Unit> {
        val a = client()
        a.save("n", "T", "first\n")
        assertEquals(SyncOutcome.Done, a.sync())
        a.save("n", "T", "second\n")
        a.beforeNext({ it.method == "PUT" }) { runBlocking { a.save("n", "T", "third\n") } }
        // "second" goes out, then the same cycle sends "third" against the revision "second" got.
        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(setOf(NoteView("n", "T", "third\n", null)), serverNotes())
        assertEquals(false, a.note("n")!!.dirty)
        assertEquals(0, a.notes.pendingNotes())
    }

    @Test
    fun `a server restored from a backup loses nothing`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("old", "Before backup", "x\n")
        syncAll(a, b)
        server.stop()
        val backup = server.snapshot()
        server.restart()
        a.save("new", "After backup", "y\n")
        b.save("old", "Before backup", "edited after\n")
        syncAll(a, b)
        server.stop()
        server.restore(backup)
        server.restart()
        backup.deleteRecursively()
        syncAll(a, b)
        val expected = setOf(
            NoteView("old", "Before backup", "edited after\n", null),
            NoteView("new", "After backup", "y\n", null)
        )
        assertEquals(expected, serverNotes())
        assertEquals(expected, a.live())
        assertEquals(expected, b.live())
    }

    @Test
    fun `signed out stops sync and keeps local work`() = runBlocking<Unit> {
        val a = TestClient(server).also { clients += it }
        a.save("n", "T", "x\n")
        assertEquals(SyncOutcome.SignedOut, a.sync())
        assertNotNull(a.note("n"))
        assertEquals(1, a.notes.pendingNotes())
    }
}
