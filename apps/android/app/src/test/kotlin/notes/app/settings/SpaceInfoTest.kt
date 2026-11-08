package notes.app.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import notes.app.ui.NotesTheme
import notes.core.api.Member
import notes.core.api.SharedSpace
import notes.core.db.Space
import notes.core.db.Spaces
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Space info (§7.8): one page, everything changed where it's shown, nothing opened over it. */
@RunWith(AndroidJUnit4::class)
class SpaceInfoTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var space by mutableStateOf(Space("w", "Work", 0, "castle"))
    private val saved = ArrayList<Pair<String, String>>()
    private var deletes = 0
    private var backs = 0

    /** What saving comes to: null, saved. */
    private var refusal: String? = null

    private fun show(start: Space) {
        space = start
        compose.setContent {
            NotesTheme(dark = false) {
                SpaceInfoPage(
                    space,
                    backLabel = "Spaces",
                    onBack = { backs++ },
                    onSave = { name, icon ->
                        saved += name to icon
                        refusal ?: run {
                            space = space.copy(name = name, icon = icon)
                            null
                        }
                    },
                    onDelete = {
                        deletes++
                        null
                    }
                )
            }
        }
    }

    private val name get() = compose.onNode(hasSetTextAction())

    private fun rename(to: String) {
        // Focused rather than tapped: a tap on text Robolectric already laid out opens a magnifier it can't draw.
        name.requestFocus()
        name.performTextReplacement(to)
        name.performImeAction()
        compose.waitForIdle()
    }

    /** The go-ahead of the confirmation titled [title]. */
    private fun confirm(title: String, action: String) = compose
        .onNode(hasText(action) and hasClickAction() and hasAnySibling(hasText(title, substring = true)))

    @Test
    fun `the owner renames where the name is shown, trimmed and with inner spaces collapsed`() {
        show(space)
        rename("  Deep   work ")
        assertEquals(listOf("Deep work" to "castle"), saved)
        name.assertTextEquals("Deep work")
    }

    @Test
    fun `an emptied or unchanged name is put back, unsaved`() {
        show(space)
        rename("   ")
        name.assertTextEquals("Work")
        rename(" Work ")
        assertEquals(emptyList(), saved)
    }

    @Test
    fun `a name that can't be saved says why and goes back`() {
        refusal = "Another space has this name."
        show(space)
        rename("Home")
        compose.onNodeWithText("Another space has this name.").assertExists()
        name.assertTextEquals("Work")
    }

    @Test
    fun `the icon opens its picker in place, and each pick is saved as it's made`() {
        show(space)
        compose.onNodeWithContentDescription("Dragon").assertDoesNotExist()
        compose.onNodeWithContentDescription("Change icon").performClick()
        compose.onNodeWithContentDescription("Dragon").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("Work" to "dragon"), saved)
        assertEquals("dragon", space.icon)
        // Still open for another try, and no dialog to confirm it.
        compose.onNodeWithContentDescription("Comet").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("Work" to "dragon", "Work" to "comet"), saved)
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `a member sees the name and icon, and can't change or delete them`() {
        show(space.copy(shared = SharedSpace("member", listOf(Member(2, "ana"), Member(1, "me")))))
        compose.onNodeWithText("Work").assertExists()
        name.assertDoesNotExist()
        compose.onNodeWithContentDescription("Change icon").assertDoesNotExist()
        compose.onNodeWithText("Delete space…").assertDoesNotExist()
    }

    @Test
    fun `delete is confirmed under its button, Cancel closing it and Delete deleting`() {
        show(space)
        compose.onNodeWithText("Delete space…").performScrollTo().performClick()
        compose.onNodeWithText("Delete Work?", substring = true).assertExists()
        compose.onNodeWithText("It has no notes: only the space itself goes.", substring = true).assertExists()
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        compose.waitUntil {
            compose.onAllNodes(hasText("Delete Work?", substring = true)).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(0, deletes)

        compose.onNodeWithText("Delete space…").performScrollTo().performClick()
        confirm("Delete Work?", "Delete").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, deletes)
    }

    @Test
    fun `Back closes only the confirmation`() {
        show(space)
        compose.onNodeWithText("Delete space…").performScrollTo().performClick()
        compose.onNodeWithText("Delete Work?", substring = true).assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil {
            compose.onAllNodes(hasText("Delete Work?", substring = true)).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(0, backs)
        assertEquals(0, deletes)
    }

    @Test
    fun `the default space, or one with notes, says why it can't be deleted`() {
        show(Space(Spaces.DEFAULT, "Notes", 0))
        compose.onNodeWithText("Delete space…").assertIsNotEnabled()
        compose.onNodeWithText("This space can be renamed, not deleted.").assertExists()
        space = Space("w", "Work", 2)
        compose.onNodeWithText("Move or delete its notes to delete it.").assertExists()
    }
}
