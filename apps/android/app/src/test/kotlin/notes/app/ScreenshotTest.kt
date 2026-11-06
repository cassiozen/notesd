package notes.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.coroutines.runBlocking
import notes.app.editor.SAMPLE_MARKDOWN
import notes.app.editor.SAMPLE_TITLE
import notes.app.ui.themes.Themes
import notes.core.api.RemoteNote
import notes.core.api.Session
import notes.core.api.SignIn
import notes.core.api.SyncPage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen to build/screenshots for design review, in light,
 * dark, 200% text and a wide (two-pane) window. Not a regression test:
 * it only runs with -Pnotes.screenshots (mise run screenshots:android).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestNotesApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val dir = File(System.getProperty("notes.screenshotsDir") ?: "build/screenshots")

    @Before
    fun only() {
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
    }

    @After
    fun tearDown() {
        scenario?.close()
        graph.shutDown()
    }

    private fun launch(qualifiers: String? = null) {
        if (qualifiers != null) RuntimeEnvironment.setQualifiers(qualifiers)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    /** The note loads off the main thread; wait until the editor shows text. */
    private fun waitForEditor() {
        var activity: MainActivity? = null
        scenario!!.onActivity { activity = it }
        fun find(v: android.view.View): notes.app.editor.NoteEditText? = v as? notes.app.editor.NoteEditText
            ?: (v as? android.view.ViewGroup)?.let { g ->
                (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) }
            }
        repeat(50) {
            compose.waitForIdle()
            var ready = false
            compose.runOnUiThread { ready = find(activity!!.window.decorView)?.text?.isNotEmpty() == true }
            if (ready) return
            Thread.sleep(100)
        }
        error("The note never loaded")
    }

    private fun shoot(name: String) {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    private fun seed() = runBlocking {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val notes = listOf(
            Triple(SAMPLE_TITLE, SAMPLE_MARKDOWN, now - 600_000),
            Triple("Groceries", "- Oat milk\n- Sourdough\n- Lemons\n\n#errands\n", now - 3_600_000),
            Triple("Reading list", "The Overstory, Piranesi, A Psalm for the Wild-Built #books\n", now - 2 * day),
            Triple("Lisbon trip", "Flights booked. Find a place near Alfama. #travel/portugal\n", now - 4 * day),
            Triple("Ideas", "A tiny garden on the balcony; herbs first. #home/garden\n", now - 12 * day),
            Triple("Meeting notes", "Ship the Android app. Then pinning.\n", now - 40 * day)
        )
        graph.sync.store.applyPage(
            SyncPage(
                notes.mapIndexed { i, (title, content, at) ->
                    RemoteNote("n$i", title, content, i + 1L, i + 1L, at, at)
                },
                notes.size.toLong(),
                false,
                "g"
            )
        )
    }

    private fun list(suffix: String, qualifiers: String?) {
        graph.setOnboardingDismissed(true)
        seed()
        launch(qualifiers)
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
        shoot("list-$suffix")
    }

    @Test fun listLight() = list("light", null)

    @Test fun listDark() = list("dark", "+night")

    @Test
    fun listLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        list("text200", null)
    }

    @Test
    fun editorLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        editor("text200", null)
    }

    @Test
    fun listByTag() {
        graph.setOnboardingDismissed(true)
        seed()
        launch()
        compose.waitUntilExactlyOneExists(hasContentDescription("Sidebar"))
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Expand travel"))
        compose.onNode(hasContentDescription("Expand travel")).performClick()
        shoot("tags")
        compose.onNode(hasContentDescription("#travel/portugal, 1 note")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Show all notes"))
        shoot("list-tag")
    }

    @Test
    fun listEmpty() {
        graph.setOnboardingDismissed(true)
        launch()
        shoot("list-empty")
    }

    @Test
    fun search() {
        graph.setOnboardingDismissed(true)
        seed()
        launch()
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
        compose.onNode(hasContentDescription("Search notes")).performClick()
        compose.onNode(hasContentDescription("Search notes")).performTextInput("lis")
        compose.waitUntilDoesNotExist(hasText("Groceries"))
        shoot("search")
    }

    private fun editor(suffix: String, qualifiers: String?) {
        graph.setOnboardingDismissed(true)
        seed()
        launch(qualifiers)
        compose.waitUntilExactlyOneExists(hasText(SAMPLE_TITLE))
        compose.onNode(hasText(SAMPLE_TITLE)).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
        waitForEditor()
        shoot("editor-$suffix")
    }

    private fun code(suffix: String, qualifiers: String?) = runBlocking {
        graph.setOnboardingDismissed(true)
        val md = "Two ways to say hello.\n\n```go\nfunc main() {\n\t// greet\n\tfmt.Println(\"hi\", 42)\n}\n```\n\n" +
            "```python\ndef greet(name):\n    return f\"hi {name}\"  # friendly\n```\n\n```\nplain, no language\n```\n"
        val at = System.currentTimeMillis()
        graph.sync.store.applyPage(SyncPage(listOf(RemoteNote("c", "Code", md, 1, 1, at, at)), 1, false, "g"))
        launch(qualifiers)
        compose.waitUntilExactlyOneExists(hasText("Code"))
        compose.onNode(hasText("Code")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
        waitForEditor()
        shoot("editor-code-$suffix")
    }

    /** The find bar over the sample note, replacing, with a match on screen and others below. */
    private fun find(suffix: String, qualifiers: String?) {
        graph.setOnboardingDismissed(true)
        seed()
        launch(qualifiers)
        compose.waitUntilExactlyOneExists(hasText(SAMPLE_TITLE))
        compose.onNode(hasText(SAMPLE_TITLE)).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("More"))
        waitForEditor()
        compose.onNode(hasContentDescription("More")).performClick()
        compose.onNode(hasText("Find and replace")).performClick()
        compose.onNode(hasContentDescription("Find in note")).performTextInput("the")
        compose.onNode(hasContentDescription("Next match")).performClick()
        compose.onNode(hasContentDescription("Replace with")).performTextInput("a")
        shoot("editor-find-$suffix")
    }

    @Test fun editorFindLight() = find("light", null)

    @Test fun editorFindDark() = find("dark", "+night")

    @Test
    fun editorFindLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        find("text200", null)
    }

    @Test fun editorCodeLight() = code("light", null)

    @Test fun editorCodeDark() = code("dark", "+night")

    @Test fun editorLight() = editor("light", null)

    @Test fun editorDark() = editor("dark", "+night")

    @Test
    fun twoPane() {
        graph.setOnboardingDismissed(true)
        seed()
        launch("w900dp-h700dp-land")
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
        shoot("twopane-empty")
        compose.onNode(hasText(SAMPLE_TITLE)).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
        waitForEditor()
        shoot("twopane-note")
    }

    @Test
    fun foldable() {
        graph.setOnboardingDismissed(true)
        seed()
        launch("w673dp-h841dp")
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
        compose.onNode(hasText(SAMPLE_TITLE)).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
        waitForEditor()
        shoot("fold-note")
    }

    @Test
    fun onboarding() {
        launch()
        shoot("onboarding-server")
    }

    @Test
    fun onboardingDark() {
        launch("+night")
        shoot("onboarding-server-dark")
    }

    @Test
    fun settings() {
        graph.setOnboardingDismissed(true)
        launch()
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Appearance", ignoreCase = true))
        shoot("settings")
    }

    @Test
    fun settingsSignedIn() {
        runBlocking {
            graph.accounts.signedIn(
                "https://notes.example.com/",
                SignIn(Session("s", "device", "P", 1, 1), "cassio", "a", Long.MAX_VALUE, "r", Long.MAX_VALUE)
            )
        }
        launch()
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Appearance", ignoreCase = true))
        shoot("settings-signed-in")
        // Each category's page; the account row opens Server.
        for ((row, name) in listOf(
            "cassio" to "server",
            "Security" to "security",
            "Spaces" to "spaces",
            "Integrations" to "integrations",
            "Appearance" to "appearance",
            "Sync & data" to "device",
            "About" to "about"
        )) {
            compose.onNode(hasText(row)).performClick()
            compose.waitUntilExactlyOneExists(hasContentDescription("Back") and hasText("Settings"))
            shoot("settings-$name")
            compose.onNode(hasContentDescription("Back")).performClick()
            compose.waitUntilExactlyOneExists(hasText(row))
        }
    }

    /** A theme other than Amber: the list, then the sample note. */
    private fun themed(id: String, suffix: String, qualifiers: String?) {
        graph.appearance.set(Themes.theme(id))
        graph.setOnboardingDismissed(true)
        seed()
        launch(qualifiers)
        compose.waitUntilExactlyOneExists(hasText(SAMPLE_TITLE))
        shoot("theme-$id-list-$suffix")
        compose.onNode(hasText(SAMPLE_TITLE)).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
        waitForEditor()
        shoot("theme-$id-editor-$suffix")
    }

    @Test fun nocturneLight() = themed("nocturne", "light", null)

    @Test fun nocturneDark() = themed("nocturne", "dark", "+night")

    @Test fun cartaLight() = themed("carta", "light", null)

    @Test fun cartaDark() = themed("carta", "dark", "+night")

    @Test fun denLight() = themed("den", "light", null)

    @Test fun denDark() = themed("den", "dark", "+night")

    @Test
    fun appearanceDark() {
        graph.setOnboardingDismissed(true)
        launch("+night")
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.onNode(hasText("Appearance")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Nocturne"))
        shoot("settings-appearance-dark")
    }

    @Test
    fun wide() {
        graph.setOnboardingDismissed(true)
        seed()
        launch("w1280dp-h800dp")
        compose.waitUntilExactlyOneExists(hasContentDescription("Expand travel"))
        shoot("wide")
    }
}
