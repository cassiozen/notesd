package notes.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
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
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import notes.app.editor.PROSE_MARKDOWN
import notes.app.editor.PROSE_TITLE
import notes.app.editor.SAMPLE_MARKDOWN
import notes.app.editor.SAMPLE_TITLE
import notes.core.api.Member
import notes.core.api.RemoteNote
import notes.core.api.Session
import notes.core.api.SharedSpace
import notes.core.api.SignIn
import notes.core.api.SpaceTrait
import notes.core.api.SyncPage
import notes.core.db.Spaces
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

    /** A space theme (§7.7): the default space given [icon], its list and then its sample note. */
    private fun themed(icon: String, qualifiers: String?) {
        val mode = if (qualifiers == null) "light" else "dark"
        graph.setOnboardingDismissed(true)
        seed()
        runBlocking { graph.sync.spaces.update(Spaces.DEFAULT, Spaces.DEFAULT_NAME, icon) }
        launch(qualifiers)
        compose.waitUntilExactlyOneExists(hasText(SAMPLE_TITLE))
        shoot("theme-$icon-list-$mode")
        compose.onNode(hasText(SAMPLE_TITLE)).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
        waitForEditor()
        shoot("theme-$icon-editor-$mode")
    }

    @Test fun themeDragonLight() = themed("dragon", null)

    @Test fun themeDragonDark() = themed("dragon", "+night")

    @Test fun themeIdeaLight() = themed("idea", null)

    @Test fun themeIdeaDark() = themed("idea", "+night")

    @Test fun themeFlamingoLight() = themed("flamingo", null)

    @Test fun themeFlamingoDark() = themed("flamingo", "+night")

    @Test fun themeCometLight() = themed("comet", null)

    @Test fun themeCometDark() = themed("comet", "+night")

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

    /** [PROSE_MARKDOWN]: paragraphs, a list, a quote and a picture, to judge spacing and line height by. */
    private fun prose(suffix: String, qualifiers: String?) = runBlocking {
        graph.setOnboardingDismissed(true)
        val at = System.currentTimeMillis()
        graph.sync.store.applyPage(
            SyncPage(listOf(RemoteNote("p", PROSE_TITLE, PROSE_MARKDOWN, 1, 1, at, at)), 1, false, "g")
        )
        launch(qualifiers)
        compose.waitUntilExactlyOneExists(hasText(PROSE_TITLE))
        compose.onNode(hasText(PROSE_TITLE)).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Undo"))
        waitForEditor()
        shoot("editor-prose-$suffix")
    }

    @Test fun editorProseLight() = prose("light", null)

    @Test fun editorProseDark() = prose("dark", "+night")

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

    /** Server's People, as an admin: the list and a person's actions. (Its dialogs never go idle here.) */
    @Test
    fun settingsPeople() {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        val people = """{"users":[
            {"id":1,"username":"cassio","isAdmin":true,"createdAt":${now - 90 * day}},
            {"id":2,"username":"sam","createdAt":${now - 30 * day}},
            {"id":3,"username":"alex","pending":true,"createdAt":$now,"linkExpiresAt":${now + 6 * day}}]}"""
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body(
                    when ("${request.method} ${request.url.encodedPath}") {
                        "GET /api/v1/users" -> people
                        else -> """{"configured":false}"""
                    }
                )
                .build()
        }
        server.start()
        try {
            runBlocking {
                graph.accounts.signedIn(
                    server.url("/").toString(),
                    SignIn(Session("s", "device", "P", 1, 1), "cassio", "a", Long.MAX_VALUE, "r", Long.MAX_VALUE, true)
                )
            }
            launch()
            compose.onNode(hasContentDescription("Sidebar")).performClick()
            compose.onNode(hasContentDescription("Settings")).performClick()
            compose.waitUntilAtLeastOneExists(hasText("cassio"))
            compose.onNode(hasText("cassio")).performClick()
            compose.waitUntilExactlyOneExists(hasText("sam"))
            shoot("settings-people")
            compose.onNode(hasText("sam") and hasClickAction()).performClick()
            compose.waitUntilExactlyOneExists(hasText("Make admin"))
            shoot("settings-people-actions")
        } finally {
            server.close()
        }
    }

    @Test
    fun wide() {
        graph.setOnboardingDismissed(true)
        seed()
        launch("w1280dp-h800dp")
        compose.waitUntilExactlyOneExists(hasContentDescription("Expand travel"))
        shoot("wide")
    }

    /** A space with traits (§7.8): the list's title, the space menu, then Space info, one trait failing. */
    private fun traits(suffix: String, qualifiers: String?) {
        graph.setOnboardingDismissed(true)
        seed()
        runBlocking {
            graph.sync.spaces.create("Work", "ledger")
            graph.setTraits(
                Spaces.DEFAULT,
                SpaceTrait("shared", "Shared", "With Ana and Ben", exposure = "Visible to Ana and Ben"),
                SpaceTrait(
                    "published",
                    "Published",
                    "family.example.com",
                    exposure = "Public at family.example.com",
                    problem = "Publishing failed. Check the address."
                ),
                SpaceTrait("archived", "Archived", "Read only")
            )
        }
        launch(qualifiers)
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
        val glyphs = hasContentDescription(
            "Space info: Shared, Published (Publishing failed. Check the address.), Archived"
        )
        compose.waitUntilExactlyOneExists(glyphs)
        shoot("list-traits-$suffix")
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Space: Notes"))
        compose.onNode(hasContentDescription("Space: Notes")).performClick()
        compose.waitUntilExactlyOneExists(hasText("New space…"))
        shoot("space-menu-$suffix")
        compose.onNode(hasContentDescription("Space info")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Name and icon"))
        shoot("space-info-$suffix")
    }

    @Test fun traitsLight() = traits("light", null)

    /** Docked sidebar: the switcher's traits button and the list title's, side by side. */
    @Test
    fun traitsWide() {
        graph.setOnboardingDismissed(true)
        seed()
        runBlocking {
            graph.setTraits(
                Spaces.DEFAULT,
                SpaceTrait("shared", "Shared", "With Ana and Ben", exposure = "Visible to Ana and Ben"),
                SpaceTrait("published", "Published", "family.example.com", exposure = "Public at family.example.com")
            )
        }
        launch("w1280dp-h800dp")
        compose.waitUntilExactlyOneExists(hasContentDescription("Space: Notes"))
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
        shoot("wide-traits")
    }

    @Test fun traitsDark() = traits("dark", "+night")

    /** The switcher with an invitation and a change line (§7.9). */
    private fun switcherSeed() {
        graph.setOnboardingDismissed(true)
        seed()
        val ids = runBlocking {
            val family = graph.sync.spaces.create("Family", "sunset")
            val journal = graph.sync.spaces.create("Journal", "journal")
            val work = graph.sync.spaces.create("Work", "ledger")
            graph.sync.spaces.create("Health", "health")
            graph.setTraits(
                family,
                SpaceTrait("shared", "Shared", "With Ana and Ben", exposure = "Visible to Ana and Ben"),
                SpaceTrait("published", "Published", "family.example.com", problem = "Publishing failed")
            )
            graph.setTraits(journal, SpaceTrait("agent", "Agent", "Local model"))
            graph.setTraits(work, SpaceTrait("shared", "Shared", "With Ben", exposure = "Visible to Ben"))
            graph.setShared(work, SharedSpace("member", listOf(Member(3, "Ben"), Member(1, "me"))))
            graph.addInvite("i1", "club", "Book club", "open-book", Member(2, "Ana"))
            graph.sync.store.setUserId(1)
            val at = System.currentTimeMillis() - 2 * 3_600_000L
            graph.sync.store.applyPage(
                SyncPage(
                    (1..3).map {
                        RemoteNote("w$it", "Standup $it", "", 1, 10L + it, at, at, spaceId = work, updatedBy = 3)
                    },
                    13,
                    false,
                    "g"
                )
            )
            family to work
        }
        graph.switchSpace(ids.first)
    }

    private fun switcherPhone(suffix: String, qualifiers: String?) {
        switcherSeed()
        launch(qualifiers)
        compose.waitUntilExactlyOneExists(hasContentDescription("Sidebar"))
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Space: Family"))
        shoot("tags-traits-$suffix")
        compose.onNode(hasContentDescription("Space: Family")).performClick()
        compose.waitUntilExactlyOneExists(hasText("New space…"))
        shoot("switcher-phone-$suffix")
    }

    @Test fun switcherPhoneLight() = switcherPhone("light", null)

    @Test fun switcherPhoneDark() = switcherPhone("dark", "+night")

    @Test
    fun switcherWide() {
        switcherSeed()
        launch("w1280dp-h800dp")
        compose.waitUntilExactlyOneExists(hasContentDescription("Space: Family"))
        compose.onNode(hasContentDescription("Space: Family")).performClick()
        compose.waitUntilExactlyOneExists(hasText("New space…"))
        shoot("switcher-wide")
    }

    /**
     * Shared spaces (§7.9) as a fake notesd syncs them: Family, someone else's, with notes others changed; Work,
     * this account's, shared with Ben and Cy invited; and an invitation into Book club.
     */
    private fun sharedSeed(): FakeNotesd {
        graph.setOnboardingDismissed(true)
        seed()
        val ana = Member(2, "ana")
        val ben = Member(3, "ben")
        val now = System.currentTimeMillis()
        fun note(id: String, title: String, content: String, by: Long, ago: Long, seq: Long) =
            RemoteNote(id, title, content, 1, seq, now - ago, now - ago, spaceId = "fam", updatedBy = by)
        val notesd = FakeNotesd()
        notesd.people = listOf(notesd.me, ana, ben, Member(4, "cy"), Member(5, "dee"), Member(6, "eli"))
        notesd.memberOf("fam", "Family", ana, ben, icon = "sunset")
        notesd.feeds["fam"] = listOf(
            note("f1", "Picnic", "Blanket, lemonade, the good frisbee.\n", 2, 2 * 3_600_000L, 1),
            note("f2", "Packing list", "- Sunscreen\n- Hats\n- Snacks\n", 3, 5 * 60_000L, 2),
            note("f3", "Recipes", "Grandma's plum cake, the real one.\n", 1, 26 * 3_600_000L, 3)
        )
        notesd.owned("work", "Work", listOf(ben), invitees = listOf(Member(4, "cy")))
        notesd.invite("i1", "club", "Book club", ana, icon = "open-book")
        notesd.start()
        notesd.signIn(graph)
        runBlocking {
            graph.sync.store.setUserId(1)
            graph.sync.sync()
            // Joined before those changes: they're news.
            graph.lastShown("fam", now - 3 * 3_600_000L)
        }
        return notesd
    }

    private fun sharedList(suffix: String, qualifiers: String?) = sharedSeed().use {
        launch(qualifiers)
        graph.switchSpace("fam")
        compose.waitUntilExactlyOneExists(hasText("ana · 2h"))
        shoot("shared-list-$suffix")
        compose.onNode(hasText("Picnic")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Edited by ana · 2h"))
        waitForEditor()
        shoot("shared-editor-$suffix")
        compose.onNode(hasContentDescription("More")).performClick()
        compose.onNode(hasText("Move to space…")).performClick()
        val notes = hasText("Notes") and hasClickAction() and !hasContentDescription("Back")
        compose.waitUntilExactlyOneExists(notes)
        compose.onNode(notes).performClick()
        compose.waitUntilExactlyOneExists(hasText("It will no longer be:"))
        shoot("shared-move-$suffix")
    }

    @Test fun sharedListLight() = sharedList("light", null)

    @Test fun sharedListDark() = sharedList("dark", "+night")

    private fun sharedSwitcher(suffix: String, qualifiers: String?) = sharedSeed().use {
        launch(qualifiers)
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Space: Notes"))
        compose.onNode(hasContentDescription("Space: Notes")).performClick()
        compose.waitUntilExactlyOneExists(hasText("ana invited you to Book club"))
        compose.waitUntilExactlyOneExists(hasText("ben and ana edited 2 notes · 5m"))
        shoot("shared-switcher-$suffix")
    }

    @Test fun sharedSwitcherLight() = sharedSwitcher("light", null)

    @Test fun sharedSwitcherDark() = sharedSwitcher("dark", "+night")

    private fun sharedManage(suffix: String, qualifiers: String?) = sharedSeed().use {
        launch(qualifiers)
        graph.switchSpace("work")
        compose.waitUntilExactlyOneExists(hasContentDescription("Space info: Shared"))
        compose.onNode(hasContentDescription("Space info: Shared")).performClick()
        compose.waitUntilExactlyOneExists(hasText("With ben · invited cy"))
        shoot("shared-info-$suffix")
        compose.onNode(hasText("With ben · invited cy")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Sharing"))
        shoot("shared-manage-$suffix")
        compose.onNode(hasText("Invite someone") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("dee"))
        shoot("shared-picker-$suffix")
    }

    @Test fun sharedManageLight() = sharedManage("light", null)

    @Test fun sharedManageDark() = sharedManage("dark", "+night")
}
