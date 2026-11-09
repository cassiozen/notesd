package notes.app.notes

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.runOffMain
import notes.app.setTraits
import notes.app.shutDown
import notes.core.api.SpaceTrait
import notes.core.db.Spaces
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
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
        graph.shutDown()
    }

    @Test
    // At the class's size, Robolectric's non-touch mode focuses the dialog's name field on open and its
    // window never stops laying out; devices are fine. The default size doesn't trip it.
    @Config(qualifiers = "w320dp-h470dp")
    fun `with one space the sidebar still names it, and its sheet makes another`() {
        runBlocking { graph.store.save("a", "Groceries", "Milk\n") }
        launch("Groceries")
        // The list is titled by what it shows.
        compose.onNodeWithText("All Notes").assertExists()
        compose.onNodeWithContentDescription("Sidebar").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Space: Notes"))
        compose.onNodeWithContentDescription("Space: Notes").performClick()
        compose.waitUntilExactlyOneExists(hasText("New space…"))
        compose.onNodeWithText("New space…").performClick()
        compose.waitUntilExactlyOneExists(hasText("Create"))
    }

    @Test
    @Config(qualifiers = "w320dp-h470dp")
    fun `a name with control characters is refused in the dialog, not by the store`() {
        runBlocking { graph.store.save("a", "Groceries", "Milk\n") }
        launch("Groceries")
        compose.onNodeWithContentDescription("Sidebar").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Space: Notes"))
        compose.onNodeWithContentDescription("Space: Notes").performClick()
        compose.waitUntilExactlyOneExists(hasText("New space…"))
        compose.onNodeWithText("New space…").performClick()
        compose.waitUntilExactlyOneExists(hasText("Create"))
        compose.onNodeWithText("Name").performTextInput("Work\u0007")
        compose.onNode(hasText("Create") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Remove the special characters from the name."))
        assertEquals(1, compose.runOffMain { graph.sync.spaces.list() }.size)
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

        // The switcher is in the sidebar; choosing a space goes back to its list.
        compose.onNodeWithContentDescription("Sidebar").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Space: Notes"))
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
        compose.waitUntilDoesNotExist(hasText("Groceries"))
        compose.runOffMain { graph.sync.spaces.delete(work) }
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
        assertEquals(Spaces.DEFAULT, graph.space.value)
    }

    @Test
    fun `the space shown sets the theme, and the default space's is kept for the next start`() {
        val dragons = runBlocking {
            graph.store.save("a", "Groceries", "Milk\n")
            graph.sync.spaces.update(Spaces.DEFAULT, Spaces.DEFAULT_NAME, "comet")
            graph.sync.spaces.create("Dragons", "dragon")
        }
        launch("Groceries")
        compose.waitUntil { graph.spaceTheme.value == "comet" }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        assertEquals("comet", prefs.getString("default_space_theme", null))

        graph.switchSpace(dragons)
        compose.waitUntil { graph.spaceTheme.value == "dragon" }
        // The next start opens on the default space, so it's still the default's that's kept.
        assertEquals("comet", prefs.getString("default_space_theme", null))
    }

    private fun movedTo(space: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (compose.runOffMain { graph.store.spaceOf("a") } != space) {
            check(System.currentTimeMillis() < deadline) { "the note wasn't moved" }
            compose.waitForIdle()
        }
    }

    private fun openSpaceInfoFromSettings(name: String) {
        compose.onNodeWithContentDescription("Sidebar").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Settings"))
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilExactlyOneExists(hasText("Spaces"))
        compose.onNodeWithText("Spaces").performClick()
        compose.waitUntilExactlyOneExists(hasText(name) and hasClickAction())
        compose.onNode(hasText(name) and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Delete space…"))
    }

    @Test
    fun `a row of Settings › Spaces opens its Space info, where an empty space is deleted`() {
        runBlocking {
            graph.store.save("a", "Groceries", "Milk\n")
            graph.sync.spaces.create("Work")
        }
        launch("Groceries")
        openSpaceInfoFromSettings("Work")
        compose.onNodeWithText("0 notes").assertExists()
        // No traits on, but a space of your own can be shared: by inviting someone, right there.
        compose.onNodeWithText("People").assertExists()
        compose.onNodeWithText("Invite people to read and edit every note in Work, and add their own.").assertExists()
        compose.onNodeWithText("Delete space…").performScrollTo().performClick()
        compose.onNode(hasText("Delete") and hasClickAction()).performScrollTo().performClick()
        // Gone: back to the list of spaces.
        compose.waitUntilExactlyOneExists(hasText("New space"))
        compose.onNode(hasText("Work") and hasClickAction()).assertDoesNotExist()
        assertEquals(listOf("Notes"), compose.runOffMain { graph.sync.spaces.list() }.map { it.name })
    }

    @Test
    fun `the default space's Space info says why it can't be deleted`() {
        runBlocking { graph.store.save("a", "Groceries", "Milk\n") }
        launch("Groceries")
        openSpaceInfoFromSettings("Notes")
        compose.onNodeWithText("1 note").assertExists()
        compose.onNodeWithText("Delete space…").assertIsNotEnabled()
        compose.onNodeWithText("This space can be renamed, not deleted.").assertExists()
        // Everyone keeps a space of their own: the default can't be shared.
        compose.onNodeWithText("People").assertDoesNotExist()
    }

    @Test
    fun `Space info lists the space's traits, a newer app's too`() {
        runBlocking {
            graph.store.save("a", "Groceries", "Milk\n")
            graph.setTraits(
                Spaces.DEFAULT,
                SpaceTrait("shared", "Shared", "With Ana and Ben", exposure = "Visible to Ana and Ben"),
                SpaceTrait("archived", "Archived", "Read only")
            )
        }
        launch("Groceries")
        // The list's title carries the traits' button, which opens Space info.
        compose.waitUntilExactlyOneExists(hasContentDescription("Space info: Shared, Archived"))
        compose.onNodeWithContentDescription("Space info: Shared, Archived").performClick()
        compose.waitUntilExactlyOneExists(hasText("Delete space…"))
        // Sharing has a section of its own; a trait without one is a row with the server's words.
        compose.onNodeWithText("People").assertExists()
        compose.onNodeWithText("Archived").assertExists()
        compose.onNodeWithText("Read only").assertExists()
        compose.onNodeWithText("Update the app to change this.").assertExists()
        // Publishing can't be turned on from this app yet, so it's left out while off.
        compose.onNodeWithText("Published").assertDoesNotExist()
    }

    @Test
    fun `moving a note into a space that shows it to others asks first`() {
        val family = runBlocking {
            graph.store.save("a", "Groceries", "Milk\n")
            val id = graph.sync.spaces.create("Family")
            graph.setTraits(id, SpaceTrait("shared", "Shared", "With Ana and Ben", exposure = "Visible to Ana and Ben"))
            id
        }
        launch("Groceries")
        compose.onNodeWithText("Groceries").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("More"))
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Move to space…").performClick()
        compose.waitUntilExactlyOneExists(hasText("Family") and hasClickAction())
        compose.onNode(hasText("Family") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Move “Groceries” to Family?"))
        compose.onNodeWithText("In Family, this note will be:").assertExists()
        compose.onNodeWithText("Visible to Ana and Ben").assertExists()
        compose.onNode(hasText("Move") and hasClickAction()).performClick()
        movedTo(family)
    }

    @Test
    fun `moving into a space that shows no one else moves straight away`() {
        val work = runBlocking {
            graph.store.save("a", "Groceries", "Milk\n")
            graph.sync.spaces.create("Work")
        }
        launch("Groceries")
        compose.onNodeWithText("Groceries").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("More"))
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Move to space…").performClick()
        compose.waitUntilExactlyOneExists(hasText("Work") and hasClickAction())
        compose.onNode(hasText("Work") and hasClickAction()).performClick()
        movedTo(work)
        compose.onNodeWithText("Move “Groceries” to Work?").assertDoesNotExist()
    }

    @Test
    fun `Space info opened from the tags screen keeps the list beneath when the window widens`() {
        runBlocking {
            graph.store.save("a", "Groceries", "Milk\n")
            graph.sync.spaces.create("Work")
        }
        launch("Groceries")
        compose.onNodeWithContentDescription("Sidebar").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Space: Notes"))
        compose.onNodeWithContentDescription("Space: Notes").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Work"))
        compose.onNodeWithContentDescription("Work").performTouchInput { longClick() }
        compose.waitUntilExactlyOneExists(hasText("Delete space…"))

        RuntimeEnvironment.setQualifiers("w1280dp-h800dp")
        scenario.recreate()
        compose.waitUntilExactlyOneExists(hasText("Delete space…"))
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
    }

    @Test
    fun `a long press on a space in the switcher opens its Space info, staying where you are`() {
        runBlocking {
            graph.store.save("a", "Groceries", "Milk\n")
            graph.sync.spaces.create("Work")
        }
        launch("Groceries")
        compose.onNodeWithContentDescription("Sidebar").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Space: Notes"))
        compose.onNodeWithContentDescription("Space: Notes").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Work"))
        compose.onNodeWithContentDescription("Work").performTouchInput { longClick() }
        compose.waitUntilExactlyOneExists(hasText("Delete space…"))
        compose.onNodeWithText("0 notes").assertExists()
        assertEquals(Spaces.DEFAULT, graph.space.value)
    }

    @Test
    fun `Space info renames the space and changes its icon in place`() {
        runBlocking {
            graph.store.save("a", "Groceries", "Milk\n")
            graph.sync.spaces.create("Work", "castle")
        }
        launch("Groceries")
        openSpaceInfoFromSettings("Work")
        val name = compose.onNode(hasSetTextAction())
        name.requestFocus()
        name.performTextReplacement("Deep  work")
        name.performImeAction()
        compose.waitUntil { compose.runOffMain { graph.sync.spaces.list() }.any { it.name == "Deep work" } }

        compose.onNodeWithContentDescription("Change icon").performClick()
        compose.onNodeWithContentDescription("Dragon").performScrollTo().performClick()
        compose.waitUntil { compose.runOffMain { graph.sync.spaces.list() }.any { it.icon == "dragon" } }
        // Nothing opened over the page: it's still Space info, with the new name.
        name.assertTextEquals("Deep work")
    }
}
