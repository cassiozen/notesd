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
import kotlinx.coroutines.runBlocking
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class, qualifiers = "w1280dp-h800dp")
class TagsSidebarTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        graph.setOnboardingDismissed(true)
        runBlocking {
            graph.store.save("a", "Roof", "Call about #home/repairs\n")
            graph.store.save("c", "Standup", "Notes #work\n")
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasContentDescription("#work, 1 note"))
    }

    @After
    fun tearDown() {
        scenario.close()
        graph.database.close()
    }

    @Test
    fun `wide windows show the tags beside the list, which has no way back to them`() {
        compose.waitUntilExactlyOneExists(hasText("Roof"))
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNode(hasContentDescription("#work, 1 note")).performClick()
        compose.waitUntilDoesNotExist(hasText("Roof"))
        compose.onNodeWithText("Standup").assertExists()
    }

    @Test
    fun `settings from the sidebar goes back to the notes`() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilExactlyOneExists(hasText("Appearance", ignoreCase = true))
        compose.onNode(hasContentDescription("Back") and hasText("Notes")).assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilDoesNotExist(hasText("Appearance", ignoreCase = true))
    }
}
