package notes.app

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import notes.core.api.RemoteNote
import notes.core.api.SyncPage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Walks each screen's accessibility tree: everything tappable has something
 * for TalkBack to say, and a target of at least 48dp.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AccessibilityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        graph.shutDown()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun audit(screen: String) = compose.auditAccessibility(screen)

    private fun seed() = runBlocking {
        val now = System.currentTimeMillis()
        graph.sync.store.applyPage(
            SyncPage(
                listOf(
                    RemoteNote("a", "Groceries", "- Oat milk #errands\n", 1, 1, now, now),
                    RemoteNote("b", "Trip", "> Lisbon #travel/portugal\n\n- [ ] Book\n", 2, 2, now, now)
                ),
                2,
                false,
                "g"
            )
        )
    }

    @Test
    fun onboarding() {
        launch()
        audit("onboarding")
    }

    @Test
    fun list() {
        graph.setOnboardingDismissed(true)
        seed()
        launch()
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
        audit("list")
    }

    @Test
    fun listByTag() {
        graph.setOnboardingDismissed(true)
        seed()
        launch()
        compose.waitUntilExactlyOneExists(hasContentDescription("Sidebar"))
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Expand travel"))
        audit("tags")
        compose.onNode(hasContentDescription("Expand travel")).performClick()
        compose.onNode(hasContentDescription("#travel/portugal, 1 note")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Show all notes"))
        audit("list by tag")
    }

    @Test
    fun emptyList() {
        graph.setOnboardingDismissed(true)
        launch()
        compose.waitUntilExactlyOneExists(hasText("No notes yet"))
        audit("empty list")
    }

    @Test
    fun editor() {
        graph.setOnboardingDismissed(true)
        seed()
        launch()
        compose.waitUntilExactlyOneExists(hasText("Trip"))
        compose.onNode(hasText("Trip")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
        audit("editor")
    }

    @Test
    fun find() {
        graph.setOnboardingDismissed(true)
        seed()
        launch()
        compose.waitUntilExactlyOneExists(hasText("Trip"))
        compose.onNode(hasText("Trip")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("More"))
        compose.onNode(hasContentDescription("More")).performClick()
        compose.onNode(hasText("Find and replace")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Replace with"))
        compose.onNode(hasContentDescription("Find in note")).performTextInput("book")
        compose.waitUntilExactlyOneExists(hasContentDescription("Match 1 of 1"))
        val counter = compose.onNode(hasContentDescription("Match 1 of 1")).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, counter.config.getOrNull(SemanticsProperties.LiveRegion))
        audit("find")
    }

    @Test
    fun settings() {
        graph.setOnboardingDismissed(true)
        launch()
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Settings"))
        audit("settings")
        // A category's page.
        compose.onNode(hasText("Appearance")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Dark"))
        audit("settings-appearance")
    }
}
