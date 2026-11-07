package notes.app.notes

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import notes.app.ui.NotesTheme
import notes.core.api.SpaceTrait
import notes.core.db.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A space's icon, name and trait glyphs (§7.8). */
@RunWith(AndroidJUnit4::class)
class SpaceIdentityTest {
    @get:Rule
    val compose = createComposeRule()

    private val shared = SpaceTrait("shared", "Shared", "With Ana and Ben", exposure = "Visible to Ana and Ben")
    private val published = SpaceTrait("published", "Published", "family.example", problem = "Publishing failed")

    private val hasDescription = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

    @Test
    fun `without traits it's the icon and name alone`() {
        compose.setContent { NotesTheme(dark = false) { SpaceIdentity("", "Family", emptyList(), onTraits = {}) } }
        compose.onNodeWithText("Family").assertExists()
        compose.onNode(hasDescription).assertDoesNotExist()
        compose.onNode(hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun `loose glyphs follow the name, described by their labels`() {
        compose.setContent { NotesTheme(dark = false) { SpaceIdentity("", "Family", listOf(shared, published)) } }
        compose.onNodeWithText("Family").assertExists()
        compose.onNodeWithContentDescription("Shared, Published (Publishing failed)").assertExists()
        compose.onNode(hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun `with somewhere to open, the glyphs are one button for space info`() {
        var opened = 0
        compose.setContent {
            NotesTheme(dark = false) { SpaceIdentity("", "Family", listOf(shared, published), onTraits = { opened++ }) }
        }
        compose.onNodeWithContentDescription("Space info: Shared, Published (Publishing failed)")
            .assertHasClickAction()
            .performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the switcher has no glyph button for a space without traits`() {
        val spaces = listOf(Space("default", "Notes", 2))
        compose.setContent { NotesTheme(dark = false) { SpaceSwitcher(spaces, "default", {}, {}) } }
        compose.onNodeWithContentDescription("Space: Notes").assertExists()
        assertEquals(1, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }
}
