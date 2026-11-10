package notes.app.agent

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AgentRulesTest {
    private val tidy = EXAMPLES.first { it.name == "Tidy tags" }
    private val journal = EXAMPLES.first { it.name == "Journal" }

    @Test
    fun `taking an example's rule keeps a command name of mine, and counts as taken then`() {
        val menu = tidy.rules.getValue("menu")
        val mine = AgentDraft(menuLabel = "Typos!", triggers = mapOf("menu" to "Other."))
        val taken = withIdea(mine, "menu", menu)
        assertEquals("Typos!", taken.menuLabel)
        assertTrue(hasIdea(taken, "menu", menu))
        // Another example's name gives way to this one's.
        assertEquals("Fix typos", withIdea(mine.copy(menuLabel = "Scale to 2 servings"), "menu", menu).menuLabel)
    }

    @Test
    fun `a rule taken from an example needs the notes it reads, until it's edited`() {
        val opened = journal.rules.getValue("noteCreated")
        val d = AgentDraft(triggers = mapOf("noteCreated" to opened.prompt))
        assertEquals(listOf("Digest"), readsNeeded(d))
        assertEquals(emptyList(), readsNeeded(d.copy(triggers = mapOf("noteCreated" to opened.prompt + " Briefly."))))
        // Moved to another moment, it still needs them.
        assertEquals(listOf("Digest"), readsNeeded(d.copy(triggers = mapOf("editStarted" to opened.prompt))))
    }

    @Test
    fun `all of an example is its purpose, its rules over mine at the same moments, and the notes they read`() {
        val mine = AgentDraft(triggers = mapOf("editDone" to "Mine.", "noteDeleted" to "Mine too."))
        val all = withExample(mine, journal)
        assertTrue(hasExample(all, journal))
        assertEquals("Mine.", all.triggers["editDone"])
        assertEquals(journal.rules.getValue("noteDeleted").prompt, all.triggers["noteDeleted"])
        assertEquals(listOf("Digest"), all.referenceNotes)
        assertFalse(all.enabled)
    }

    @Test
    fun `an empty rule is a change, and one that can't be saved`() {
        val saved = AgentDraft(prompt = "Notes.")
        val added = saved.copy(triggers = mapOf("menu" to ""))
        assertFalse(sameAgent(saved, added))
        assertEquals(listOf("menu"), emptyRules(added))
        assertTrue(sameAgent(added, added.copy(triggers = LinkedHashMap(added.triggers))))
    }

    @Test
    fun `ideas are the examples', once each, then the moment's own`() {
        assertEquals(
            listOf(
                "Update the Digest" to "Journal",
                "Metric checklist, tagged" to "Recipes",
                "Tag from a small set" to "Tidy tags",
                "Title and tags" to null
            ),
            ideasFor("newNoteDone").map { it.idea.title to it.from }
        )
        assertEquals(
            listOf("Keep the Digest current" to "Journal"),
            ideasFor("noteDeleted").map { it.idea.title to it.from }
        )
    }

    @Test
    fun `a title is what the server keeps, trimmed and one space at a time`() {
        assertEquals("Weekly digest", titleOf("  Weekly \t  digest "))
    }
}
