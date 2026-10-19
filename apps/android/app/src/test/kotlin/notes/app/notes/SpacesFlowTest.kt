package notes.app.notes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import notes.core.db.Spaces
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Spaces (§7.7) on a phone: hidden with one, switched from the list's title with two. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class, qualifiers = "w411dp-h891dp")
class SpacesFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private lateinit var scenario: ActivityScenario<MainActivity>

    private fun launch(first: String) {
        graph.setOnboardingDismissed(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText(first))
    }

    @After
    fun tearDown() {
        scenario.close()
        graph.database.close()
    }

    @Test
    fun `with one space there is no switcher`() {
        runBlocking { graph.store.save("a", "Groceries", "Milk\n") }
        launch("Groceries")
        compose.onNode(hasContentDescription("Space: Notes")).assertDoesNotExist()
    }

    @Test
    fun `the list, and new notes, follow the space chosen`() {
        val work = runBlocking {
            val id = graph.sync.spaces.create("Work")
            graph.store.save("a", "Groceries", "Milk\n")
            graph.store.save("b", "Standup", "Status\n", spaceId = id)
            id
        }
        launch("Groceries")
        compose.onNodeWithText("Standup").assertDoesNotExist()

        compose.onNodeWithContentDescription("Space: Notes").performClick()
        compose.onNodeWithText("Work").performClick()
        compose.waitUntilExactlyOneExists(hasText("Standup"))
        compose.onNodeWithText("Groceries").assertDoesNotExist()
        assertEquals(work, graph.space.value)

        graph.switchSpace(Spaces.DEFAULT)
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
    }

    @Test
    fun `a deleted space sends the list back to the default`() {
        val work = runBlocking {
            graph.store.save("a", "Groceries", "Milk\n")
            graph.sync.spaces.create("Work")
        }
        launch("Groceries")
        graph.switchSpace(work)
        compose.waitUntil { graph.space.value == work }
        runBlocking { graph.sync.spaces.delete(work) }
        compose.waitUntil { graph.space.value == Spaces.DEFAULT }
    }
}
