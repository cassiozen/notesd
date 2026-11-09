package notes.app.notes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.runOffMain
import notes.app.shutDown
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A note merged with another device's edits (§6.2) says so, and Undo splits the merge into a conflicted copy. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class, qualifiers = "w411dp-h891dp")
class MergeUndoFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun seed() {
        graph.setOnboardingDismissed(true)
        runBlocking {
            graph.store.save("n", "Plan", "One\n\nTwo\n")
            // Stands in for sync: the note changed underneath an editor that started from "One/Two".
            graph.store.save("n", "Plan", "One, elsewhere\n\nTwo\n")
            graph.store.save("n", "Plan", "One\n\nTwo, here\n", basedOn = "Plan" to "One\n\nTwo\n")
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        graph.shutDown()
    }

    @Test
    fun `undo keeps the other version as a conflicted copy`() {
        assertEquals("One, elsewhere\n\nTwo, here\n", runBlocking { graph.store.get("n") }?.content)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Plan"))
        compose.onNodeWithText("Plan").performClick()
        compose.waitUntilExactlyOneExists(hasText("Merged with changes from another device."))

        compose.onNodeWithText("Undo").performClick()
        val undone = "Merge undone. The other version was kept as “Plan (Conflicted copy)”."
        compose.waitUntilExactlyOneExists(hasText(undone))
        compose.waitUntilDoesNotExist(hasText("Merged with changes from another device."))
        val notes = compose.runOffMain { graph.store.list().map { graph.store.get(it.id)!! } }
        assertEquals("One\n\nTwo, here\n", notes.single { it.id == "n" }.content)
        assertEquals("One, elsewhere\n\nTwo\n", notes.single { it.id != "n" }.content)
    }
}
