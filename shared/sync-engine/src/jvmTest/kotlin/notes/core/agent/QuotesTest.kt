package notes.core.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import notes.core.doc.Document
import notes.core.markdown.Markdown

/** shared/agent-fixtures/quote.json, the cases the web app passes too. */
class QuotesTest {
    @Serializable
    private data class Case(
        val name: String,
        val title: String,
        val content: String,
        val quote: String,
        val block: Int?
    )

    @Serializable
    private data class Suite(val cases: List<Case>)

    private fun note(title: String, content: String) = Document.forNote(title, Markdown.parse(content))

    @Test
    fun `the shared cases`() {
        val dir = System.getProperty("notes.sharedDir") ?: error("notes.sharedDir not set (run through Gradle)")
        val json = Json { ignoreUnknownKeys = true }
        val suite = json.decodeFromString<Suite>(File(dir, "agent-fixtures/quote.json").readText())
        assertTrue(suite.cases.isNotEmpty())
        for (c in suite.cases) assertEquals(c.block, Quotes.find(note(c.title, c.content), c.quote), c.name)
    }

    @Test
    fun `a block still holds a quote as written or as shown, and not once it changed there`() {
        val doc = note("T", "Teh plan\n\nSet my_var to *x*")
        val (plan, set) = Quotes.blocks(doc)
        assertTrue(Quotes.holds(plan, "Teh"))
        assertTrue(Quotes.holds(set, "my_var to x"))
        val fixed = Quotes.blocks(note("T", "The plan\n\nOther Teh"))
        assertFalse(Quotes.holds(fixed[0], "Teh"))
    }
}
