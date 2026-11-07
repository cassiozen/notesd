package notes.app.account

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import notes.app.TestSealer
import notes.core.api.Session
import notes.core.api.SignIn
import notes.core.api.Tokens

class AccountStoreTest {
    private val dir = Files.createTempDirectory("account").toFile()
    private val file = File(dir, "account")
    private val sealer = TestSealer()
    private val signIn = SignIn(Session("s", "device", "Pixel", 1, 1), "me", "access-secret", 99, "refresh-secret", 100)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `signing in stores tokens sealed, and they survive a restart`() = runBlocking {
        AccountStore(file, sealer).signedIn("https://n.example/", signIn)
        val text = file.readBytes().decodeToString()
        assertFalse("secret" in text, "tokens are never stored in the clear")
        val again = AccountStore(file, sealer)
        assertEquals(Account.SignedIn("https://n.example/", "me"), again.account.value)
        assertEquals(Tokens("access-secret", 99, "refresh-secret"), again.tokens())
    }

    @Test
    fun `whether it's an admin is kept, and follows what the server says`() = runBlocking {
        val store = AccountStore(file, sealer)
        store.signedIn("https://n.example/", signIn.copy(isAdmin = true))
        assertEquals(
            Account.SignedIn("https://n.example/", "me", isAdmin = true),
            AccountStore(file, sealer).account.value
        )
        store.refreshed(Tokens("a2", 200, "refresh-secret"))
        assertEquals(
            Account.SignedIn("https://n.example/", "me", isAdmin = true),
            AccountStore(file, sealer).account.value
        )
        store.adminChanged(false)
        assertEquals(Account.SignedIn("https://n.example/", "me"), AccountStore(file, sealer).account.value)
    }

    @Test
    fun `an account saved before the app kept whether it's an admin doesn't know`() = runBlocking {
        val old = """{"server":"https://n.example/","username":"me","access":"a","accessExpiresAt":1,"refresh":"r"}"""
        file.writeBytes(sealer.seal(old.encodeToByteArray()))
        val store = AccountStore(file, sealer)
        assertEquals(Account.SignedIn("https://n.example/", "me", isAdmin = null), store.account.value)
        store.adminChanged(true)
        assertEquals(
            Account.SignedIn("https://n.example/", "me", isAdmin = true),
            AccountStore(file, sealer).account.value
        )
    }

    @Test
    fun `refreshing replaces the tokens`() = runBlocking {
        val store = AccountStore(file, sealer)
        store.signedIn("https://n.example/", signIn)
        store.refreshed(Tokens("a2", 200, "refresh-secret"))
        assertEquals("a2", AccountStore(file, sealer).tokens()!!.access)
    }

    @Test
    fun `a revoked session keeps the server and username`() = runBlocking {
        val store = AccountStore(file, sealer)
        store.signedIn("https://n.example/", signIn)
        store.revoked()
        assertEquals(Account.SignedOut("https://n.example/", "me"), store.account.value)
        assertNull(store.tokens())
        assertEquals(Account.SignedOut("https://n.example/", "me"), AccountStore(file, sealer).account.value)
    }

    @Test
    fun `a file this key can't open counts as never connected`() = runBlocking {
        AccountStore(file, sealer).signedIn("https://n.example/", signIn)
        assertEquals(Account.None, AccountStore(file, TestSealer()).account.value)
        assertFalse(file.exists())
    }

    @Test
    fun `clear forgets everything`() = runBlocking {
        val store = AccountStore(file, sealer)
        store.signedIn("https://n.example/", signIn)
        store.clear()
        assertEquals(Account.None, store.account.value)
        assertFalse(file.exists())
    }
}
