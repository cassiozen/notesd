package notes.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import notes.app.editor.NoteEditText
import notes.core.api.RemoteNote
import notes.core.api.SyncPage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class MainActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun launch() {
        // Past first-run onboarding (OnboardingFlowTest covers it), straight to the list.
        graph.setOnboardingDismissed(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After
    fun tearDown() {
        scenario.close()
        graph.shutDown()
    }

    @Test
    fun `a new note left blank leaves nothing behind`() {
        compose.waitUntilExactlyOneExists(hasText("No notes yet"))
        compose.onNodeWithContentDescription("New note").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Back"))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("No notes yet"))
    }

    @Test
    fun `typing saves the note and it appears in the list`() {
        compose.onNodeWithContentDescription("New note").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Back"))
        val editor = editor()
        compose.waitForIdle()
        compose.runOnUiThread {
            assertTrue(editor.isFocused, "a new note opens with the cursor in the title")
            assertEquals(0, editor.selectionStart)
            editor.text!!.insert(0, "Packing list")
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Packing list"))
        compose.onNodeWithText("Packing list").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Back"))
        val reopened = editor()
        compose.runOnUiThread { assertEquals("Packing list", reopened.document().title) }
    }

    /** The editor view, once the note has loaded (on the IO thread, then the main looper). */
    private fun editor(): NoteEditText {
        fun find(v: View): NoteEditText? =
            v as? NoteEditText ?: (v as? ViewGroup)?.children?.firstNotNullOfOrNull(::find)
        repeat(50) {
            compose.waitForIdle()
            find(activity.window.decorView)?.let { return it }
            Thread.sleep(100)
        }
        error("The editor never appeared")
    }

    @Test
    fun `stored notes are listed and searchable`() {
        compose.runOffMain {
            graph.store.save("a", "Groceries", "- Oat milk\n")
            graph.store.save("b", "Trip", "Lisbon\n")
        }
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
        compose.onNodeWithText("Oat milk").assertExists()
        compose.onNodeWithContentDescription("Search notes").performClick()
        compose.onNodeWithContentDescription("Search notes").performTextInput("lisb")
        compose.waitUntilDoesNotExist(hasText("Groceries"))
        compose.onNodeWithText("Trip").assertExists()
    }

    // Sync changing a note while it's open, simulated by applying feed pages as the engine would.

    private fun remote(id: String, title: String, content: String, revision: Long, deleted: Boolean = false) =
        compose.runOffMain {
            graph.sync.store.applyPage(
                SyncPage(
                    listOf(
                        RemoteNote(id, title, content, revision, revision, 1, revision, if (deleted) revision else null)
                    ),
                    revision,
                    false,
                    "g"
                )
            )
        }

    @Test
    fun `a sync change shows up in the open editor`() {
        remote("n", "Trip", "Pack\n", 1)
        compose.waitUntilExactlyOneExists(hasText("Trip"))
        compose.onNodeWithText("Trip").performClick()
        val editor = editor()
        remote("n", "Trip", "Pack the tent\n", 2)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.waitForIdle()
            editor.text.toString().contains("Pack the tent")
        }
    }

    @Test
    fun `typing into a note deleted elsewhere is kept as a recovered copy`() {
        remote("n", "Trip", "Pack\n", 1)
        compose.waitUntilExactlyOneExists(hasText("Trip"))
        compose.onNodeWithText("Trip").performClick()
        val editor = editor()
        compose.runOnUiThread { editor.text!!.append(" tent") }
        remote("n", "", "", 2, deleted = true)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Trip (Recovered copy)"))
    }
}
