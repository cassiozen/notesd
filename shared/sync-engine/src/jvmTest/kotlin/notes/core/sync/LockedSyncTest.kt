package notes.core.sync

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import notes.core.api.ApiResult
import notes.core.crypto.Keyring
import notes.core.crypto.LockedNotes

/** Locked notes (§10.1) between devices: the keyring travels, bodies stay ciphertext, conflicts stay locked. */
class LockedSyncTest {
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

    /** Device a creates the lock password; the key it holds. */
    private suspend fun setUp(a: TestClient): ByteArray {
        val created = Keyring.create("lock pass 123", iterations = 10_000)
        assertIs<ApiResult.Ok<*>>(a.api.putLockKey(created.entry, created.verifier))
        return created.key
    }

    @Test
    fun `the keyring syncs, so another device unlocks offline`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        val key = setUp(a)
        a.save("n", "Bank", LockedNotes.encrypt(key, "PIN 1234\n"))
        syncAll(a, b)

        b.offline = true
        val unlocked = Keyring.unlock(b.engine.lockKeys.keys(), "lock pass 123")
        assertEquals("PIN 1234\n", LockedNotes.decrypt(unlocked, b.note("n")!!.content))
        val summary = b.notes.list().single()
        assertTrue(summary.locked)
        assertEquals("", summary.preview)
        assertEquals(emptyList(), b.notes.search("1234"))
    }

    @Test
    fun `a locked note edited on both devices keeps both, still locked`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        val key = setUp(a)
        a.save("n", "Bank", LockedNotes.encrypt(key, "base\n"))
        syncAll(a, b)

        a.save("n", "Bank", LockedNotes.encrypt(key, "from a\n"))
        b.save("n", "Bank", LockedNotes.encrypt(key, "from b\n"))
        syncAll(a, b)

        // Sync merges without the key: both bodies survive, each one still encrypted.
        val bodies = a.live().map { LockedNotes.decrypt(key, a.note(it.id)!!.content) }.toSet()
        assertEquals(setOf("from a\n", "from b\n"), bodies)
        assertTrue(a.live().all { LockedNotes.isLocked(a.note(it.id)!!.content) })
        assertEquals(a.live(), b.live())
    }

    @Test
    fun `a title edit merges with a locked body edit`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        val key = setUp(a)
        a.save("n", "Bank", LockedNotes.encrypt(key, "base\n"))
        syncAll(a, b)

        a.save("n", "Bank (old)", a.note("n")!!.content)
        val body = LockedNotes.encrypt(key, "new body\n")
        b.save("n", "Bank", body)
        syncAll(a, b)

        val merged = a.note("n")!!
        assertEquals("Bank (old)", merged.title)
        assertEquals(body, merged.content)
        assertEquals(1, a.live().size)
    }

    @Test
    fun `a keyring fetched before a change made on this device doesn't overwrite it`() = runBlocking<Unit> {
        val a = client()
        setUp(a)
        syncAll(a)
        // A setup or new password on this device lands while the cycle's fetch is out.
        val local = Keyring.create("newer", iterations = 10_000).entry
        a.beforeNext({ it.method == "GET" && it.url.encodedPath == "/api/v1/locks" }) {
            runBlocking { a.engine.lockKeys.replace(listOf(local)) }
        }
        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(listOf(local), a.engine.lockKeys.keys())

        // The next cycle takes the server's again.
        assertEquals(SyncOutcome.Done, a.sync())
        assertTrue(a.engine.lockKeys.keys().none { it.wrapped == local.wrapped })
    }
}
