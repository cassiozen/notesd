package notes.app.notes

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import notes.app.ui.NotesTheme
import notes.core.api.SpaceTrait
import notes.core.db.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The space switcher (§7.7): the current space's card, invitations, the others to switch to. */
@RunWith(AndroidJUnit4::class)
class SpaceSwitcherTest {
    @get:Rule
    val compose = createComposeRule()

    private val shared = SpaceTrait("shared", "Shared", "With Ana and Ben", exposure = "Visible to Ana and Ben")
    private val failing = SpaceTrait("published", "Published", "family.example", problem = "Publishing failed")
    private val agent = SpaceTrait("agent", "Agent", "")

    private val notes = Space("default", "Notes", 3)
    private val family = Space("f", "Family", 2, "sunset", traits = listOf(shared, failing))
    private val journal = Space("j", "Journal", 1, "journal", traits = listOf(agent))
    private val work = Space("w", "Work", 4, "ledger", traits = listOf(shared))

    private val switched = ArrayList<String>()
    private val info = ArrayList<String>()
    private val joined = ArrayList<String>()
    private val declined = ArrayList<String>()
    private var created = 0

    /** What answering an invitation comes to: null, done. */
    private var refusal: String? = null

    private fun show(
        spaces: List<Space>,
        current: String,
        invites: List<SpaceInvite> = emptyList(),
        changes: Map<String, String> = emptyMap()
    ) {
        compose.setContent {
            NotesTheme(dark = false) {
                SwitcherContent(
                    spaces,
                    current,
                    invites,
                    changes,
                    onSwitch = { switched += it },
                    onInfo = { info += it },
                    onCreate = { created++ },
                    onJoin = {
                        joined += it
                        refusal
                    },
                    onDecline = {
                        declined += it
                        refusal
                    }
                )
            }
        }
    }

    @Test
    fun `the card says each trait in words, a problem instead of its summary`() {
        show(listOf(notes, family, journal), "f")
        compose.onNodeWithText("Shared · With Ana and Ben").assertExists()
        compose.onNodeWithText("Publishing failed").assertExists()
        compose.onNodeWithText("Published · family.example").assertDoesNotExist()
        compose.onNodeWithContentDescription("Space info").performClick()
        assertEquals(listOf("f"), info)
    }

    @Test
    fun `a trait without a summary is its label, and a space without traits is only you`() {
        assertEquals("Agent", cardLine(agent))
        show(listOf(notes, family), "default")
        compose.onNodeWithText("Only you").assertExists()
    }

    @Test
    fun `the current space is the card, not a row to switch to`() {
        show(listOf(notes, family, journal), "f")
        compose.onNodeWithText("SWITCH TO").assertExists()
        compose.onNode(hasContentDescription("Family", substring = true)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Notes, only you").performClick()
        assertEquals(listOf("default"), switched)
    }

    @Test
    fun `with one space there's nothing to switch to`() {
        show(listOf(notes), "default")
        compose.onNodeWithText("SWITCH TO").assertDoesNotExist()
        compose.onNodeWithText("New space…").performClick()
        assertEquals(1, created)
    }

    @Test
    fun `rows say their traits, a problem, a change, or only you`() {
        show(listOf(notes, family, journal, work), "j", changes = mapOf("w" to "Ben edited 3 notes · 2h"))
        compose.onNodeWithContentDescription("Notes, only you").assertExists()
        compose.onNodeWithContentDescription("Family, Shared, Publishing failed").assertExists()
        compose.onNode(hasContentDescription("Family, Shared, Publishing failed") and hasText("Publishing failed"))
            .assertExists()
        // A change replaces the trait labels on the second line.
        compose.onNodeWithContentDescription("Work, Ben edited 3 notes · 2h").assertExists()
        compose.onNode(hasText("Ben edited 3 notes · 2h") and hasText("Shared")).assertDoesNotExist()
    }

    @Test
    fun `a long press opens that space's info without switching, as does TalkBack's action`() {
        show(listOf(notes, family, journal), "default")
        compose.onNodeWithContentDescription("Journal, Agent").performTouchInput { longClick() }
        assertEquals(listOf("j"), info)
        assertEquals(emptyList(), switched)

        val node = compose.onNodeWithContentDescription("Family, Shared, Publishing failed").fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.CustomActions)?.single { it.label == "Space info" }
        compose.runOnIdle { action!!.action() }
        assertEquals(listOf("j", "f"), info)
        assertEquals(emptyList(), switched)
    }

    @Test
    fun `invitations are answered from the switcher`() {
        show(
            listOf(notes, family),
            "default",
            invites = listOf(SpaceInvite("i1", "Book club", "open-book", "Ana"), SpaceInvite("i2", "Chess", "", "Ben"))
        )
        compose.onNodeWithText("Ana invited you to Book club").assertExists()
        compose.onNodeWithContentDescription("Join Book club").performClick()
        compose.onNodeWithContentDescription("Decline Chess").performClick()
        assertEquals(listOf("i1"), joined)
        assertEquals(listOf("i2"), declined)
    }

    @Test
    fun `an invitation that can't be answered says why, in place`() {
        refusal = "This needs a connection. Try again when you're online."
        show(listOf(notes), "default", invites = listOf(SpaceInvite("i1", "Book club", "open-book", "Ana")))
        compose.onNodeWithContentDescription("Join Book club").performClick()
        compose.onNodeWithText("This needs a connection. Try again when you're online.").assertExists()
        assertEquals(listOf("i1"), joined)
    }

    @Test
    fun `the switcher's name opens the card, whose info opens space info`() {
        val spaces = listOf(notes, family)
        compose.setContent {
            NotesTheme(dark = false) { SpaceSwitcher(spaces, "f", {}, {}, onInfo = { info += it }) }
        }
        compose.onNodeWithContentDescription("Space info: Shared, Published (Publishing failed)").performClick()
        assertEquals(listOf("f"), info)
        compose.onNodeWithContentDescription("Space: Family").performClick()
        compose.waitUntil { compose.onAllNodes(hasText("New space…")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Space info").performClick()
        assertEquals(listOf("f", "f"), info)
    }

    @Test
    fun `docked, the switcher opens as a popup with the same content`() {
        val spaces = listOf(notes, family)
        compose.setContent {
            NotesTheme(dark = false) { SpaceSwitcher(spaces, "default", { switched += it }, {}, popup = true) }
        }
        compose.onNodeWithContentDescription("Space: Notes").performClick()
        compose.waitUntil { compose.onAllNodes(hasText("New space…")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Family, Shared, Publishing failed").performClick()
        assertEquals(listOf("f"), switched)
        compose.waitUntil { compose.onAllNodes(hasText("New space…")).fetchSemanticsNodes().isEmpty() }
    }
}
