package notes.app.notes

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
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
import kotlinx.coroutines.runBlocking
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.editor.FindSpan
import notes.app.editor.NoteEditText
import notes.app.shutDown
import notes.core.markdown.Markdown
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The find bar over the editor: opening it from the menu, finding, replacing, and closing it with back. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class, qualifiers = "w411dp-h891dp")
class FindFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun seed() {
        graph.setOnboardingDismissed(true)
        runBlocking { graph.store.save("a", "Roof", "Call about the **roof**. The roof leaks.\n") }
    }

    @After
    fun tearDown() {
        scenario.close()
        graph.shutDown()
    }

    private fun open() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        compose.waitUntilExactlyOneExists(hasText("Roof"))
        compose.onNodeWithText("Roof").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
    }

    private fun menu(item: String) {
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitUntilExactlyOneExists(hasText(item))
        compose.onNodeWithText(item).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Close find"))
    }

    private fun editor(): NoteEditText {
        fun find(v: View): NoteEditText? =
            v as? NoteEditText ?: (v as? ViewGroup)?.children?.firstNotNullOfOrNull(::find)
        return find(activity.window.decorView) ?: error("no editor")
    }

    @Test
    fun `find counts matches as you type, steps through them, and back closes it on the match`() {
        open()
        menu("Find")
        compose.onNodeWithContentDescription("Replace with").assertDoesNotExist()
        compose.onNodeWithContentDescription("Find in note").performTextInput("roof")
        compose.waitUntilExactlyOneExists(hasContentDescription("Match 1 of 3"))
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Match 2 of 3"))
        compose.onNodeWithContentDescription("Find in note").performTextInput("x")
        compose.waitUntilExactlyOneExists(hasText("No results"))

        compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
        compose.onNodeWithContentDescription("Close find").assertDoesNotExist()
        compose.runOnUiThread {
            val e = editor()
            assertEquals(0, e.text!!.getSpans(0, e.text!!.length, FindSpan::class.java).size)
        }
        // Still on the note, not back at the list.
        compose.onNodeWithContentDescription("More").assertExists()
    }

    @Test
    fun `reopening keeps the query and closing selects the match`() {
        open()
        menu("Find")
        compose.onNodeWithContentDescription("Find in note").performTextInput("leaks")
        compose.waitUntilExactlyOneExists(hasContentDescription("Match 1 of 1"))
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
        compose.runOnUiThread {
            val e = editor()
            assertEquals("leaks", e.text!!.substring(e.selectionStart, e.selectionEnd))
            // Nothing selected: the last query comes back.
            e.setSelection(0)
        }
        menu("Find")
        compose.waitUntilExactlyOneExists(hasContentDescription("Match 1 of 1"))
        compose.onNodeWithText("leaks").assertExists()
    }

    @Test
    fun `replace all changes every match in one step, and it saves`() {
        open()
        menu("Find and replace")
        compose.onNodeWithContentDescription("Find in note").performTextInput("roof")
        compose.waitUntilExactlyOneExists(hasContentDescription("Match 1 of 3"))
        compose.onNodeWithContentDescription("Replace with").performTextInput("gutter")
        compose.onNodeWithText("Replace all").performClick()
        compose.waitUntilExactlyOneExists(hasText("Replaced 3"))
        compose.runOnUiThread {
            val doc = editor().document()
            assertEquals("gutter", doc.title)
            assertEquals("Call about the **gutter**. The gutter leaks.\n", Markdown.serialize(doc))
        }
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.runOnUiThread {
            assertEquals("Call about the **roof**. The roof leaks.\n", Markdown.serialize(editor().document()))
        }
        compose.onNodeWithContentDescription("Redo").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        // The list's row for the note, from what was saved.
        compose.waitUntilExactlyOneExists(hasText("gutter"))
        compose.waitUntilExactlyOneExists(hasText("Call about the gutter. The gutter leaks.", substring = true))
    }

    @Test
    fun `the replace toggle shows and hides the replace row`() {
        open()
        menu("Find")
        compose.onNodeWithContentDescription("Replace").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Replace with"))
        compose.onNodeWithContentDescription("Replace").performClick()
        compose.waitUntilDoesNotExist(hasContentDescription("Replace with"))
    }

    @Test
    fun `turning replace on with a query focuses the replace field`() {
        open()
        menu("Find")
        compose.onNodeWithContentDescription("Find in note").performTextInput("roof")
        compose.onNodeWithContentDescription("Replace").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Replace with") and isFocused())
    }
}
