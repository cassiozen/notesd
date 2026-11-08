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
    fun `the name and photo are kept from sign-in, follow the server, and outlive signing out`() = runBlocking {
        val store = AccountStore(file, sealer)
        store.signedIn("https://n.example/", signIn.copy(userId = 2, displayName = "Maria", avatar = "v1"))
        assertEquals(Profile(2, "Maria", "v1"), (AccountStore(file, sealer).account.value as Account.SignedIn).profile)
        store.profileChanged("https://n.example/", "me", Profile(2, "Ana", ""))
        assertEquals(Profile(2, "Ana", ""), (AccountStore(file, sealer).account.value as Account.SignedIn).profile)
        // Asked for as someone else, who has since signed out: not this account's.
        store.sessionRead("https://n.example/", "sam", Profile(3, "Sam", ""), isAdmin = true)
        store.profileChanged("https://other.example/", "me", Profile(9, "Other", ""))
        assertEquals(
            Account.SignedIn("https://n.example/", "me", userId = 2, profile = Profile(2, "Ana", "")),
            AccountStore(file, sealer).account.value
        )
        store.signOut()
        assertEquals(
            Account.SignedOut("https://n.example/", "me", Profile(2, "Ana", "")),
            AccountStore(file, sealer).account.value
        )
        assertEquals("Ana", Profile(2, "Ana").name("me"))
        assertEquals("me", Profile().name("me"))
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
    fun `an id found for an account no longer the one signed in isn't kept`() = runBlocking {
        val store = AccountStore(file, sealer)
        store.signedIn("https://n.example/", signIn)
        val looked = store.account.value as Account.SignedIn
        store.signedIn("https://n.example/", signIn.copy(username = "ana"))
        store.userIdFound(looked, 7)
        assertEquals(Account.SignedIn("https://n.example/", "ana"), AccountStore(file, sealer).account.value)
        store.signedIn("https://n.example/", signIn)
        store.userIdFound(looked, 7)
        assertEquals(
            Account.SignedIn("https://n.example/", "me", userId = 7, profile = Profile(7)),
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
