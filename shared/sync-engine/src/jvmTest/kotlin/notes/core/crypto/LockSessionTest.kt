package notes.core.crypto

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import notes.core.db.Database
import notes.core.db.LockKeyStore

class LockSessionTest {
    private val dir: File = Files.createTempDirectory("notes-locks").toFile()
    private val db = Database(File(dir, "notes.db").path)
    private val keys = LockKeyStore(db)
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val session = LockSession(keys, { null }, scope, iterations = 10_000)

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun `a device-kept key unlocks only while the password entry it was kept against stands`() = runBlocking {
        val created = Keyring.create("lock pass 123", iterations = 10_000)
        keys.replace(listOf(created.entry))
        session.refresh()
        val bound = session.passwordEntry()!!

        assertTrue(session.unlockWithKey(created.key, bound))
        assertEquals(LockSession.State.Unlocked, session.state.value)
        session.lock()
        assertNull(session.key())

        // Reset elsewhere and a new password made: the kept key is stale.
        keys.replace(listOf(Keyring.create("another one", iterations = 10_000).entry))
        assertFalse(session.unlockWithKey(created.key, bound))
        assertEquals(LockSession.State.Locked, session.state.value)
        assertFalse(session.unlockWithKey(ByteArray(5), session.passwordEntry()!!))
    }

    @Test
    fun `a new password or a reset synced from elsewhere locks an unlocked session`() = runBlocking {
        val created = Keyring.create("lock pass 123", iterations = 10_000)
        keys.replace(listOf(created.entry))
        session.refresh()
        assertEquals(LockSession.Outcome.Done, session.unlock("lock pass 123"))

        // Nothing changed: still unlocked.
        session.refresh()
        assertEquals(LockSession.State.Unlocked, session.state.value)

        keys.replace(listOf(Keyring.create("another one", iterations = 10_000).entry))
        session.refresh()
        assertEquals(LockSession.State.Locked, session.state.value)
        assertNull(session.key())

        assertEquals(LockSession.Outcome.Done, session.unlock("another one"))
        keys.replace(emptyList())
        session.refresh()
        assertEquals(LockSession.State.None, session.state.value)
        assertNull(session.key())
    }

    @Test
    fun `a save that fails keeps the key, unless the keyring moved on`() = runBlocking {
        val created = Keyring.create("lock pass 123", iterations = 10_000)
        keys.replace(listOf(created.entry))
        session.refresh()
        assertEquals(LockSession.Outcome.Done, session.unlock("lock pass 123"))
        var saves = 0
        val unhook = session.onBeforeLock {
            saves++
            false
        }

        assertFalse(session.lock())
        assertEquals(LockSession.State.Unlocked, session.state.value)
        assertTrue(session.key() != null)

        keys.replace(listOf(Keyring.create("another one", iterations = 10_000).entry))
        session.refresh()
        assertEquals(LockSession.State.Locked, session.state.value)
        assertEquals(2, saves)
        unhook()
    }

    @Test
    fun `an entry from a newer app or a broken one is never a wrong password`() = runBlocking {
        val created = Keyring.create("lock pass 123", iterations = 10_000)
        val argon = buildJsonObject { put("name", "argon2id") }
        keys.replace(listOf(created.entry.copy(kdf = argon)))
        session.refresh()
        assertEquals(LockSession.Outcome.UnsupportedKdf, session.unlock("lock pass 123"))

        keys.replace(listOf(created.entry.copy(kdf = Kdf.pbkdf2(0))))
        assertEquals(LockSession.Outcome.MalformedKey, session.unlock("lock pass 123"))
        keys.replace(listOf(created.entry.copy(kdf = null, iterations = -1)))
        assertEquals(LockSession.Outcome.MalformedKey, session.unlock("lock pass 123"))
        assertEquals(LockSession.State.Locked, session.state.value)

        // Before 1.0 there was no kdf: implied by the kind.
        keys.replace(listOf(created.entry.copy(kdf = null)))
        assertEquals(LockSession.Outcome.WrongPassword, session.unlock("wrong"))
        assertEquals(LockSession.Outcome.Done, session.unlock("lock pass 123"))
    }

    @Test
    fun `new entries say how they're derived, and the copy here keeps it`() = runBlocking {
        val created = Keyring.create("lock pass 123", iterations = 10_000)
        assertEquals(Kdf.pbkdf2(10_000), created.entry.kdf)
        keys.replace(listOf(created.entry))
        assertEquals(created.entry, keys.keys().single())
    }
}
