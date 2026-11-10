package notes.app.agent

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AgentRulesTest {
    @Test
    fun `starting from an example takes its purpose, rules and command name, keeping the switch and notes read`() {
        val tidy = EXAMPLES.first { it.name == "Tidy tags" }
        val d = AgentDraft(enabled = true, referenceNotes = listOf("Glossary"))
        assertTrue(isBlank(d))
        val taken = withExample(d, tidy)
        assertEquals(AgentDraft(true, tidy.prompt, "Fix typos", tidy.rules, listOf("Glossary")), taken)
        assertFalse(isBlank(taken))
    }

    @Test
    fun `an example isn't offered over a purpose or a rule`() {
        assertTrue(isBlank(AgentDraft(prompt = " ")))
        assertFalse(isBlank(AgentDraft(prompt = "Mine.")))
        assertFalse(isBlank(AgentDraft(triggers = mapOf("menu" to ""))))
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
    fun `a title is what the server keeps, trimmed and one space at a time`() {
        assertEquals("Weekly digest", titleOf("  Weekly \t  digest "))
    }
}
