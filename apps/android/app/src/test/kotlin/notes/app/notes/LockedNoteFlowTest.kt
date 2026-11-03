package notes.app.notes

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import notes.app.FakeBiometrics
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.core.crypto.Keyring
import notes.core.crypto.LockSession
import notes.core.crypto.LockedNotes
import notes.core.crypto.UnsupportedKdfException
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Locked notes (§10.1) on the phone: the prompt, unlocking offline, locking again, removing the lock. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class LockedNoteFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var key: ByteArray

    @Before
    fun seed() {
        graph.setOnboardingDismissed(true)
        // A keyring as sync would have brought it; few iterations keep the test quick.
        val created = Keyring.create(PASSWORD, iterations = 10_000)
        key = created.key
        onMain {
            graph.sync.lockKeys.replace(listOf(created.entry))
            graph.locks.refresh()
            graph.store.save("n", "Bank", LockedNotes.encrypt(key, "PIN 1234\n"))
        }
    }

    /**
     * Turns the paused main looper until [done]. compose.waitUntil doesn't before an
     * activity exists or once it's stopped, nor for the graph's main-scope coroutines
     * (the fingerprint offer), which resume through the looper.
     */
    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "condition still false after 5s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /**
     * Runs [block] on the app's main scope. runBlocking on the main thread deadlocks: the
     * graph's own main-thread coroutines hold the lock session's mutex (and the store's
     * locks) and need this thread to release them.
     */
    private fun onMain(block: suspend () -> Unit) {
        val done = graph.scope.async { block() }
        pumpUntil { done.isCompleted }
        runBlocking { done.await() }
    }

    /** Reads the store off the main thread, for the same reason as [onMain]. */
    private fun <T> read(block: suspend () -> T): T {
        val done = CoroutineScope(Dispatchers.IO).async { block() }
        pumpUntil { done.isCompleted }
        return runBlocking { done.await() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        graph.database.close()
        FakeBiometrics.cancelNext = false
        FakeBiometrics.failNext = false
        FakeBiometrics.invalidated = false
    }

    private fun openBank() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Locked"))
        compose.onNodeWithText("Bank").performClick()
        compose.waitUntilExactlyOneExists(hasText("This note is locked."))
    }

    private fun unlock(password: String) {
        // The field, not Security's line of the same name behind the dialog.
        compose.onNode(hasText("Lock password") and hasSetTextAction()).performTextInput(password)
        compose.onNode(hasText("Unlock") and hasClickAction()).performClick()
    }

    @Test
    fun `a lock password entry from a newer notesd isn't a wrong password`() {
        onMain {
            val entry = graph.sync.lockKeys.keys().single()
            graph.sync.lockKeys.replace(listOf(entry.copy(kdf = buildJsonObject { put("name", "argon2id") })))
        }
        openBank()
        unlock(PASSWORD)
        compose.waitUntilExactlyOneExists(hasText(UnsupportedKdfException.UNSUPPORTED_KDF_MESSAGE))
        compose.onNodeWithText("Wrong password.").assertDoesNotExist()
    }

    @Test
    fun `the list shows Locked, the note opens with the lock password, offline`() {
        openBank()
        compose.onNodeWithText("PIN 1234").assertDoesNotExist()

        unlock("wrong one")
        compose.waitUntilExactlyOneExists(hasText("Wrong password."))
        compose.onNodeWithText("Lock password").performTextClearance()
        unlock(PASSWORD)
        compose.waitUntil(5_000) { graph.locks.state.value == LockSession.State.Unlocked }
        compose.waitUntilDoesNotExist(hasText("This note is locked."))
        compose.waitUntilExactlyOneExists(hasContentDescription("Lock now"))
    }

    @Test
    fun `Lock now and leaving the app both lock again`() {
        openBank()
        unlock(PASSWORD)
        compose.waitUntilExactlyOneExists(hasContentDescription("Lock now"))
        compose.onNodeWithContentDescription("Lock now").performClick()
        compose.waitUntilExactlyOneExists(hasText("This note is locked."))
        assertEquals(LockSession.State.Locked, graph.locks.state.value)

        unlock(PASSWORD)
        compose.waitUntilExactlyOneExists(hasContentDescription("Lock now"))
        scenario?.moveToState(Lifecycle.State.CREATED)
        pumpUntil { graph.locks.state.value == LockSession.State.Locked }
    }

    @Test
    fun `removing the lock stores the text plainly`() {
        openBank()
        unlock(PASSWORD)
        compose.waitUntilExactlyOneExists(hasContentDescription("Lock now"))
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Remove lock").performClick()
        pumpUntil { read { graph.store.get("n")?.content } == "PIN 1234\n" }
        compose.waitUntilDoesNotExist(hasContentDescription("Lock now"))
        assertTrue(read { graph.store.list().single() }.let { !it.locked && it.preview == "PIN 1234" })
    }

    @Test
    fun `locking another note asks for the existing lock password, not a new one`() {
        onMain { graph.store.save("d", "Diary", "Dear diary\n") }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Diary"))
        compose.onNodeWithText("Diary").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("More"))
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Lock note").performClick()
        compose.waitUntilExactlyOneExists(hasText("Lock this note"))
        compose.onNodeWithText("Create a lock password").assertDoesNotExist()
        compose.onNodeWithText("Lock password").performTextInput(PASSWORD)
        compose.onNode(hasText("Lock") and hasClickAction()).performClick()
        pumpUntil { read { graph.store.get("d")?.content }?.let { LockedNotes.isLocked(it) } == true }
        assertEquals("Dear diary\n", LockedNotes.decrypt(key, read { graph.store.get("d")!!.content }))
    }

    @Test
    fun `the first lock password typed offers fingerprint unlock, which opens the note next time`() {
        openBank()
        unlock(PASSWORD)
        // The fake fingerprint matches as soon as it's asked for.
        pumpUntil { graph.fingerprint.enabled.value }
        compose.waitUntilExactlyOneExists(hasContentDescription("Lock now"))

        onMain { graph.locks.lock() }
        assertEquals(LockSession.State.Locked, graph.locks.state.value)
        // Not locked from this screen: the placeholder asks for the fingerprint at once.
        compose.waitUntil(5_000) { graph.locks.state.value == LockSession.State.Unlocked }
        compose.waitUntilExactlyOneExists(hasContentDescription("Lock now"))
    }

    @Test
    fun `Not now is remembered, the password alone next time`() {
        FakeBiometrics.cancelNext = true
        openBank()
        unlock(PASSWORD)
        pumpUntil { graph.locks.state.value == LockSession.State.Unlocked && !FakeBiometrics.cancelNext }
        compose.waitForIdle()
        assertEquals(false, graph.fingerprint.enabled.value)

        onMain { graph.locks.lock() }
        compose.waitUntilExactlyOneExists(hasText("This note is locked."))
        unlock(PASSWORD)
        compose.waitUntil(5_000) { graph.locks.state.value == LockSession.State.Unlocked }
        compose.waitForIdle()
        // Asked again, the fake would have matched.
        assertEquals(false, graph.fingerprint.enabled.value)
    }

    @Test
    fun `a fingerprint prompt that failed rather than being declined is offered again`() {
        FakeBiometrics.failNext = true
        openBank()
        unlock(PASSWORD)
        pumpUntil { graph.locks.state.value == LockSession.State.Unlocked && !FakeBiometrics.failNext }
        compose.waitForIdle()
        assertEquals(false, graph.fingerprint.enabled.value)

        onMain { graph.locks.lock() }
        compose.waitUntilExactlyOneExists(hasText("This note is locked."))
        unlock(PASSWORD)
        pumpUntil { graph.fingerprint.enabled.value }
    }

    @Test
    fun `fingerprint unlock, once turned on in Settings, opens locked notes`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Locked"))
        // Settings is on the sidebar screen, behind the list.
        compose.onNodeWithContentDescription("Sidebar").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Security").performClick()
        compose.onNode(hasText("Unlock with fingerprint")).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Unlock locked notes"))
        unlock(PASSWORD)
        compose.waitUntilExactlyOneExists(hasText("Fingerprint unlock is on for this phone."))
        assertTrue(graph.fingerprint.enabled.value)

        onMain { graph.locks.lock() }
        // Security, then Settings' list, back to the sidebar screen.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Appearance"))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("All notes").performClick()
        compose.onNodeWithText("Bank").performClick()
        // Opening the note asks for the fingerprint at once; the fake one matches.
        compose.waitUntilExactlyOneExists(hasContentDescription("Lock now"))
        assertEquals(LockSession.State.Unlocked, graph.locks.state.value)
    }

    @Test
    fun `a new lock password elsewhere turns fingerprint unlock off`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Locked"))
        onMain { graph.locks.unlock(PASSWORD) }
        scenario!!.onActivity { activity -> runBlocking { graph.fingerprint.enable(activity) } }
        assertTrue(graph.fingerprint.enabled.value)
        onMain {
            graph.locks.lock()
            // The keyring was reset on another device and a new password made: the kept key is stale.
            graph.sync.lockKeys.replace(listOf(Keyring.create("another one", iterations = 10_000).entry))
        }

        compose.onNodeWithText("Bank").performClick()
        compose.waitUntilExactlyOneExists(hasText("Fingerprint unlock is off", substring = true))
        assertEquals(false, graph.fingerprint.enabled.value)
        compose.onNodeWithText("Use fingerprint").assertDoesNotExist()
    }

    @Test
    fun `Security opens the lock password forms in dialogs, only once asked`() {
        onMain { graph.locks.unlock(PASSWORD) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Bank"))
        compose.onNodeWithContentDescription("Sidebar").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Security").performClick()
        // A line, not a form: nothing to type until asked.
        compose.waitUntilExactlyOneExists(hasText("Unlocked on this phone"))
        compose.onNodeWithText("Current lock password").assertDoesNotExist()

        compose.onNodeWithContentDescription("Change lock password").performClick()
        compose.onNodeWithText("Current lock password").performTextInput(PASSWORD)
        compose.onNodeWithText("New lock password").performTextInput("a newer lock pass")
        compose.onNodeWithText("Confirm new lock password").performTextInput("a newer lock pass")
        compose.onNode(hasText("Change") and hasClickAction()).performScrollTo().performClick()
        // No server here: the change can't be kept, and the dialog says so with what was typed still in it.
        compose.waitUntilExactlyOneExists(hasText("Connect to your server first", substring = true))
        compose.onNodeWithText("Current lock password").assertExists()
        compose.onNode(hasText("Cancel") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilDoesNotExist(hasText("Current lock password"))

        compose.onNode(hasText("Forgot the lock password?")).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Reset locked notes?"))
        compose.onNodeWithText("Account password").assertExists()
        compose.onNode(hasText("Cancel") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilDoesNotExist(hasText("Reset locked notes?"))
    }

    private companion object {
        const val PASSWORD = "lock pass 123"
    }
}
