package notes.app.notes

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.shutDown
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Which notes count as open, for an agent to work in: every way out of a note closes it, a rotation doesn't. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class, qualifiers = "w411dp-h891dp")
class OpenNotesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private lateinit var scenario: ActivityScenario<MainActivity>

    // Made in the test, not as the class is: Main isn't Robolectric's looper yet then.
    private lateinit var watching: CoroutineScope
    private val closed = ArrayList<String>()

    @After
    fun tearDown() {
        watching.cancel()
        scenario.close()
        graph.shutDown()
    }

    /** The app on its list, with Groceries and Trip in the default space and Work as another. */
    private fun launch(): String {
        val work = runBlocking {
            graph.store.save("a", "Groceries", "Milk\n")
            graph.store.save("b", "Trip", "Tickets\n")
            graph.sync.spaces.create("Work")
        }
        watching = CoroutineScope(Dispatchers.Main)
        watching.launch { graph.openNotes.closed.collect { closed += it } }
        graph.setOnboardingDismissed(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
        return work
    }

    /** Waits for [condition], the main looper kept going: a plain waitUntil doesn't run Robolectric's. */
    private fun until(condition: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "never came true" }
            compose.waitForIdle()
            looper.idle()
            Thread.sleep(5)
        }
    }

    private fun open(title: String, id: String) {
        compose.onNode(hasText(title) and hasClickAction()).performClick()
        until { id in graph.openNotes.ids.value }
        until { graph.openNotes.note(id)?.view?.value != null }
    }

    private fun closes(id: String) {
        until { id !in graph.openNotes.ids.value }
        assertEquals(listOf(id), closed)
    }

    private fun menu(item: String) {
        compose.waitUntilExactlyOneExists(hasContentDescription("More"))
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitUntilExactlyOneExists(hasText(item))
        compose.onNodeWithText(item).performClick()
    }

    @Test
    fun `Back closes it`() {
        launch()
        open("Groceries", "a")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        closes("a")
    }

    @Test
    fun `a rotation keeps it open, and its editor comes back`() {
        launch()
        open("Groceries", "a")
        scenario.recreate()
        until { graph.openNotes.note("a")?.view?.value != null }
        assertEquals(setOf("a"), graph.openNotes.ids.value)
        assertEquals(emptyList(), closed)
    }

    @Test
    fun `deleting closes it`() {
        launch()
        open("Groceries", "a")
        menu("Delete note")
        closes("a")
    }

    @Test
    fun `moving it to another space closes it`() {
        launch()
        open("Groceries", "a")
        menu("Move to space…")
        compose.waitUntilExactlyOneExists(hasText("Work") and hasClickAction())
        compose.onNode(hasText("Work") and hasClickAction()).performClick()
        closes("a")
    }

    @Test
    fun `a new note from the launcher closes the one open`() {
        launch()
        open("Groceries", "a")
        // As the launcher's shortcut reaches the app already open (onNewIntent is protected).
        scenario.onActivity {
            MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }
                .invoke(it, Intent(MainActivity.ACTION_NEW_NOTE))
        }
        closes("a")
        until { graph.openNotes.ids.value.size == 1 }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `beside the list, opening another note closes the one open`() {
        launch()
        open("Groceries", "a")
        open("Trip", "b")
        closes("a")
        assertEquals(setOf("b"), graph.openNotes.ids.value)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `switching space closes it`() {
        launch()
        open("Groceries", "a")
        compose.onNodeWithContentDescription("Space: Notes").performClick()
        compose.waitUntilExactlyOneExists(hasText("Work") and hasClickAction())
        compose.onNode(hasText("Work") and hasClickAction()).performClick()
        closes("a")
    }
}
