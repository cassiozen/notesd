package notes.core.sync

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import notes.core.db.Spaces

/** Spaces (§7.7) between devices and a real notesd. */
class SpacesSyncTest {
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

    private suspend fun TestClient.spaceNames() = engine.spaces.list().map { it.name }

    private suspend fun TestClient.spaceOf(id: String) = notes.get(id)?.spaceId

    @Test
    fun `a space made offline arrives with the notes filed in it`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        val work = a.engine.spaces.create("  Work  ")
        a.notes.save("n", "Plan", "Ship it #q3\n", spaceId = work)
        syncAll(a, b)

        assertEquals(listOf("Notes", "Work"), b.spaceNames())
        assertEquals(work, b.spaceOf("n"))
        assertEquals(listOf("n"), b.notes.list(space = work).map { it.id })
        assertEquals(emptyList(), b.notes.list(space = Spaces.DEFAULT))
        assertEquals(listOf("q3"), b.notes.tags(work).map { it.key })
        assertEquals(emptyList(), b.notes.tags(Spaces.DEFAULT))
        assertEquals(1, b.engine.spaces.list().single { it.id == work }.noteCount)
    }

    @Test
    fun `the same name made on two devices gets a number on the second`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.engine.spaces.create("Work")
        b.engine.spaces.create("work")
        syncAll(a, b)
        assertEquals(listOf("Notes", "Work", "work 2"), a.spaceNames())
        assertEquals(a.spaceNames(), b.spaceNames())
    }

    @Test
    fun `a name deleted here and used again keeps it`() = runBlocking<Unit> {
        val a = client()
        val work = a.engine.spaces.create("Work")
        syncAll(a)
        assertTrue(a.engine.spaces.delete(work))
        val again = a.engine.spaces.create("Work")
        syncAll(a)
        assertEquals(listOf("Notes", "Work"), a.spaceNames())
        assertEquals(again, a.engine.spaces.list().last().id)
    }

    @Test
    fun `a name deleted here while its notes move out is kept by the space made in its place`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        val old = a.engine.spaces.create("Work")
        a.notes.save("n", "Plan", "x\n", spaceId = old)
        syncAll(a, b)

        // Offline: the note out, the space deleted, a new one by the same name with a note in it.
        assertTrue(a.notes.move("n", Spaces.DEFAULT))
        assertTrue(a.engine.spaces.delete(old))
        val again = a.engine.spaces.create("Work")
        a.notes.save("m", "Standup", "y\n", spaceId = again)
        syncAll(a, b)

        assertEquals(listOf("Notes", "Work"), a.spaceNames())
        assertEquals(a.spaceNames(), b.spaceNames())
        assertEquals(again, b.spaceOf("m"))
        assertEquals(Spaces.DEFAULT, b.spaceOf("n"))
    }

    @Test
    fun `a rename reaches every device`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.engine.spaces.update(Spaces.DEFAULT, "Personal")
        syncAll(a, b)
        assertEquals(listOf("Personal"), b.spaceNames())
    }

    @Test
    fun `an icon goes with the space, and a new one replaces it`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        val work = a.engine.spaces.create("Work", icon = "duck")
        syncAll(a, b)
        assertEquals("duck", b.engine.spaces.list().single { it.id == work }.icon)
        assertEquals("", b.engine.spaces.list().single { it.id == Spaces.DEFAULT }.icon)

        b.engine.spaces.update(work, "Office", icon = "castle")
        syncAll(b, a)
        val s = a.engine.spaces.list().single { it.id == work }
        assertEquals("Office" to "castle", s.name to s.icon)
    }

    @Test
    fun `after a restore, a note deleted on both sides in different spaces stays deleted`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "Plan", "x\n")
        syncAll(a, b)
        server.stop()
        val backup = server.snapshot()
        server.restart()
        // Deleted on a, its cursor run ahead of the backup: after the restore its next sync is a full one.
        a.notes.delete("n")
        for (v in 1..3) {
            a.save("other", "Other", "$v\n")
            assertEquals(SyncOutcome.Done, a.sync())
        }
        server.stop()
        server.restore(backup)
        server.restart()
        backup.deleteRecursively()

        // Deleted again on b, after moving it: the server's tombstone is in another space.
        val work = b.engine.spaces.create("Work")
        syncAll(b)
        assertTrue(b.notes.move("n", work))
        syncAll(b)
        b.notes.delete("n")
        syncAll(b)
        syncAll(a)
        assertEquals(listOf("Other"), a.live().map { it.title })
    }

    @Test
    fun `after a restore, a note only moved on the server is taken without a copy`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.save("n", "Plan", "x\n")
        syncAll(a, b)
        server.stop()
        val backup = server.snapshot()
        server.restart()
        // a's cursor runs ahead of the backup, so after the restore its next sync is a full one.
        for (v in 1..3) {
            a.save("other", "Other", "$v\n")
            assertEquals(SyncOutcome.Done, a.sync())
        }
        server.stop()
        server.restore(backup)
        server.restart()
        backup.deleteRecursively()

        val work = b.engine.spaces.create("Work")
        syncAll(b)
        assertTrue(b.notes.move("n", work))
        syncAll(b)
        syncAll(a)
        assertEquals(work, a.spaceOf("n"))
        assertEquals(setOf("Plan", "Other"), a.live().map { it.title }.toSet())
        assertEquals(2, a.live().size)
    }

    @Test
    fun `a move here and an edit there both stay`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        val work = a.engine.spaces.create("Work")
        a.save("n", "Plan", "base\n")
        syncAll(a, b)

        assertTrue(a.notes.move("n", work))
        b.save("n", "Plan", "edited\n")
        syncAll(a, b)
        for (c in listOf(a, b)) {
            assertEquals(work, c.spaceOf("n"))
            assertEquals("edited\n", c.note("n")?.content)
        }
        assertTrue(a.live().size == 1, "no conflicted copy")
    }

    @Test
    fun `moved differently on two devices, the server's space wins`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        val work = a.engine.spaces.create("Work")
        val home = a.engine.spaces.create("Home")
        a.save("n", "Plan", "base\n")
        syncAll(a, b)

        a.notes.move("n", work)
        b.notes.move("n", home)
        assertEquals(SyncOutcome.Done, a.sync())
        syncAll(b, a)
        assertEquals(work, a.spaceOf("n"))
        assertEquals(work, b.spaceOf("n"))
        assertEquals(1, b.live().size)
    }

    @Test
    fun `a note filed into a space deleted elsewhere lands in the default`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        val work = a.engine.spaces.create("Work")
        syncAll(a, b)

        assertTrue(a.engine.spaces.delete(work))
        assertEquals(SyncOutcome.Done, a.sync())
        b.notes.save("n", "Late", "filed offline\n", spaceId = work)
        syncAll(b, a)
        assertEquals(Spaces.DEFAULT, b.spaceOf("n"))
        assertEquals(Spaces.DEFAULT, a.spaceOf("n"))
        assertEquals(listOf("Notes"), b.spaceNames())
    }

    @Test
    fun `a delete refused because notes arrived elsewhere brings the space back`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        val work = a.engine.spaces.create("Work")
        syncAll(a, b)

        b.notes.save("n", "Busy", "in work\n", spaceId = work)
        assertEquals(SyncOutcome.Done, b.sync())
        assertTrue(a.engine.spaces.delete(work))
        assertEquals(listOf("Notes"), a.spaceNames())
        assertEquals(SyncOutcome.Done, a.sync())
        // Why it's back is kept for the person who deleted it.
        assertTrue(a.engine.store.state().lastError.orEmpty().contains("“Work” wasn’t deleted"))
        syncAll(a)
        assertEquals(listOf("Notes", "Work"), a.spaceNames())
        assertEquals(work, a.spaceOf("n"))
    }

    @Test
    fun `notes moved out offline let the space be deleted in the same sync`() = runBlocking<Unit> {
        val a = client()
        val work = a.engine.spaces.create("Work")
        a.notes.save("n", "Plan", "x\n", spaceId = work)
        syncAll(a)

        assertFalse(a.engine.spaces.delete(work), "not while it holds a note")
        a.notes.move("n", Spaces.DEFAULT)
        assertTrue(a.engine.spaces.delete(work))
        syncAll(a)
        assertEquals(listOf("Notes"), a.spaceNames())
        val fresh = client()
        syncAll(fresh)
        assertEquals(listOf("Notes"), fresh.spaceNames())
        assertEquals(Spaces.DEFAULT, fresh.spaceOf("n"))
    }

    @Test
    fun `search counts the matches in other spaces`() = runBlocking<Unit> {
        val a = client()
        val work = a.engine.spaces.create("Work")
        a.notes.save("h", "Garden", "home\n")
        a.notes.save("w", "Garden plan", "work\n", spaceId = work)
        assertEquals(
            listOf("h"),
            a.notes.search("garden", scope = notes.core.db.SpaceScope.In(Spaces.DEFAULT)).map {
                it.id
            }
        )
        assertEquals(1, a.notes.countElsewhere("garden", Spaces.DEFAULT))
        assertEquals(
            listOf("w"),
            a.notes.search("garden", scope = notes.core.db.SpaceScope.Except(Spaces.DEFAULT)).map {
                it.id
            }
        )
    }
}
