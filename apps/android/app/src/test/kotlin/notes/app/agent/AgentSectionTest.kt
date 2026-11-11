package notes.app.agent

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import notes.app.FakeNotesd
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.shutDown
import notes.core.api.AgentConfig
import notes.core.api.RemoteNote
import notes.core.crypto.LockedEnvelope
import notes.core.db.Spaces
import notes.core.sync.SyncOutcome
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The agent's section of Space info (§7.11), against a fake notesd. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class AgentSectionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val notesd = FakeNotesd()

    @After
    fun tearDown() {
        scenario?.close()
        graph.shutDown()
        notesd.close()
    }

    /**
     * Signed in and synced, on the default space's Space info until [until] shows; then, with [page], its Agent row
     * pressed, on the agent's page.
     */
    private fun open(until: String = "Agent", page: Boolean = true) {
        notesd.start()
        notesd.signIn(graph)
        graph.setOnboardingDismissed(true)
        assertEquals(SyncOutcome.Done, runBlocking { graph.sync.sync() })
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Spaces"))
        compose.onNodeWithText("Spaces").performClick()
        compose.waitUntilExactlyOneExists(hasText("Notes") and hasClickAction())
        compose.onNode(hasText("Notes") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText(until), 5_000)
        if (page) {
            compose.onNode(hasText("Agent") and hasClickAction()).performScrollTo().performClick()
            compose.waitUntilExactlyOneExists(hasContentDescription("Back") and hasText("Notes"), 5_000)
        }
    }

    /** One of the top bar's buttons: there without scrolling. */
    private fun top(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo()

    private fun described(description: String) =
        compose.onNode(hasContentDescription(description) and hasClickAction()).performScrollTo()

    private fun field(label: String): SemanticsNodeInteraction =
        compose.onNode(hasSetTextAction() and hasText(label, substring = true)).performScrollTo()

    private fun toggle() = compose.onNode(hasContentDescription("Agent") and isToggleable()).performScrollTo()

    private fun saved(prompt: String = "Keep it tidy.", triggers: Map<String, String>, enabled: Boolean = true) {
        notesd.agents[Spaces.DEFAULT] = AgentConfig(Spaces.DEFAULT, enabled, prompt, "", triggers)
    }

    @Test
    fun `it's turned on with a rule, which can't be left empty`() {
        open()
        compose.onNodeWithText("Acts on this space's notes", substring = true).assertExists()
        toggle().performClick()
        compose.waitUntilExactlyOneExists(hasText("No rules yet. Without one, the agent does nothing."))
        compose.onNodeWithText("Notes go to ai.example.com", substring = true).assertExists()

        button("+ Add rule").performClick()
        compose.waitUntilExactlyOneExists(hasText("While you write"))
        button("When you finish a new note").performClick()
        compose.waitUntilExactlyOneExists(hasText("When you close it, or leave it for 2 minutes."))
        top("Save").performClick()
        compose.waitUntilExactlyOneExists(hasText("Say what the agent does then, or remove the rule."))
        assertEquals(emptyList(), notesd.agentSaves)

        button("Title and tags").performClick()
        top("Save").performClick()
        compose.waitUntilExactlyOneExists(hasText("Saved"))
        assertEquals(
            listOf(
                """{"enabled":true,"prompt":"","menuLabel":"","triggers":{"newNoteDone":"Give the note a short """ +
                    """title if it has none, and add 1–3 tags at the end."},"referenceNotes":[]}"""
            ),
            notesd.agentSaves
        )
        // Back as saved: nothing to save, nothing to ask about.
        compose.onNode(hasText("Cancel") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun `an example is offered while nothing's written, and starting from one never turns it on or saves`() {
        saved(prompt = "", triggers = emptyMap(), enabled = false)
        open()
        button("Recipes").performClick()
        compose.waitUntilExactlyOneExists(hasText("When you finish a new note"))
        compose.onNodeWithText("Recipes I cook.", substring = true).assertExists()
        compose.onNodeWithText("Scale to 2 servings").assertExists()
        // Something's written: nothing to start from over it.
        compose.onNodeWithText("Start from an example").assertDoesNotExist()
        compose.onNodeWithText("Off").assertExists()
        assertEquals(emptyList(), notesd.agentSaves)

        top("Cancel").performClick()
        compose.waitUntilExactlyOneExists(hasText("Start from an example"))
    }

    @Test
    fun `a rule changes moment, a menu rule names its command, and a note to read is found by its title`() {
        notesd.feeds[""] = listOf(
            RemoteNote("n1", "Groceries", "Groceries\n", revision = 1, changeSeq = 1, createdAt = 1, updatedAt = 1)
        )
        saved(triggers = mapOf("editDone" to "Fix my spelling."))
        open()
        compose.waitUntilExactlyOneExists(hasText("When you finish editing a note"))
        described("When you finish editing a note. Change when").performClick()
        compose.waitUntilExactlyOneExists(hasText("When notes come and go"))
        button("When you ask, from the note’s menu").performClick()
        compose.waitUntilExactlyOneExists(hasText("Command name", substring = true))
        field("Command name").performTextInput("Spelling")

        field("Note title").performTextInput("groc")
        compose.waitUntilExactlyOneExists(hasText("Groceries") and hasClickAction())
        compose.onNode(hasText("Groceries") and hasClickAction()).performScrollTo().performClick()
        described("Stop reading Groceries").assertExists()

        top("Save").performClick()
        compose.waitUntilExactlyOneExists(hasText("Saved"))
        assertEquals(
            """{"enabled":true,"prompt":"Keep it tidy.","menuLabel":"Spelling",""" +
                """"triggers":{"menu":"Fix my spelling."},"referenceNotes":["Groceries"]}""",
            notesd.agentSaves.single()
        )
    }

    @Test
    fun `leaving with changes not saved asks first`() {
        saved(triggers = mapOf("editDone" to "Fix my spelling."))
        open()
        compose.waitUntilExactlyOneExists(hasText("When you finish editing a note"))
        field("What this space is for").performTextInput(" Briefly.")
        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitUntilExactlyOneExists(
            hasText("Discard your changes? Your changes to the agent aren't saved.", substring = true)
        )
        compose.onNode(hasText("Cancel") and hasClickAction() and hasAnySibling(hasText("Discard"))).performClick()
        compose.waitUntilDoesNotExist(hasText("Discard your changes?", substring = true))

        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Discard") and hasClickAction())
        compose.onNode(hasText("Discard") and hasClickAction()).performClick()
        compose.waitUntilDoesNotExist(hasText("What this space is for"))
        assertEquals(emptyList(), notesd.agentSaves)
    }

    @Test
    fun `it isn't offered until AI is set up`() {
        notesd.aiConfigured = false
        open(until = "Delete space…", page = false)
        compose.waitForIdle()
        compose.onNodeWithText("Agent").assertDoesNotExist()
    }

    @Test
    fun `when it couldn't load, it says so in any space, and Try again brings it`() {
        notesd.failing += "GET /api/v1/agent/spaces"
        // Its row says so, and still opens the page, where Try again is.
        open(until = "Couldn't load the agent.")
        synchronized(notesd) { notesd.failing.clear() }
        button("Try again").performClick()
        compose.waitUntilExactlyOneExists(hasText("Acts on this space's notes", substring = true), 5_000)
    }

    @Test
    fun `a locked note's title is there to read, though never offered`() {
        notesd.feeds[""] = listOf(
            RemoteNote(
                "n1",
                "Digest",
                "${LockedEnvelope.FAMILY}AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                revision = 1,
                changeSeq = 1,
                createdAt = 1,
                updatedAt = 1
            )
        )
        notesd.agents[Spaces.DEFAULT] = AgentConfig(
            Spaces.DEFAULT,
            true,
            "Journal.",
            "",
            mapOf("editDone" to "Fix my spelling."),
            referenceNotes = listOf("Digest", "Ideas")
        )
        open()
        compose.waitUntilExactlyOneExists(hasText("Ideas"))
        // Only the one with no note at all.
        compose.waitUntilExactlyOneExists(hasText("not here yet"))
        described("Stop reading Digest").performClick()
        field("Note title").performTextInput("dig")
        compose.waitForIdle()
        compose.onNode(hasText("Digest") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun `one set up before AI was disconnected stays, paused`() {
        notesd.aiConfigured = false
        saved(triggers = mapOf("editDone" to "Fix my spelling."))
        open()
        compose.waitUntilExactlyOneExists(hasText("Paused", substring = true))
        compose.onNodeWithText("Fix my spelling.").assertExists()
    }

    @Test
    fun `a refusal about a field shows under it, and a space the server lacks says so`() {
        saved(triggers = mapOf("editDone" to "Fix my spelling."))
        open()
        compose.waitUntilExactlyOneExists(hasText("When you finish editing a note"))
        field("What this space is for").performTextInput(" Briefly.")
        notesd.refuseAgentSave = listOf("400", "invalid_request", "triggers.editDone", "the rule is too long")
        top("Save").performClick()
        compose.waitUntilExactlyOneExists(hasText("The rule is too long."))

        notesd.refuseAgentSave = listOf("404", "not_found", "", "no such space")
        top("Save").performClick()
        compose.waitUntilExactlyOneExists(hasText("This space hasn't reached the server yet", substring = true))
        assertTrue(notesd.agents.getValue(Spaces.DEFAULT).prompt == "Keep it tidy.")
    }
}
