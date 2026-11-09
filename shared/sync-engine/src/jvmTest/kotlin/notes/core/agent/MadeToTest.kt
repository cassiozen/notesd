package notes.core.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** shared/agent-fixtures/made-to.json, the cases the server and the web app pass too. */
class MadeToTest {
    @Serializable
    private data class Case(
        val name: String,
        val before: Fields,
        val after: Fields,
        val op: AgentOp,
        val now: Fields,
        val cursor: Int? = null,
        val want: Fields? = null
    )

    @Serializable
    private data class Suite(val cases: List<Case>)

    @Test
    fun `the shared cases`() {
        val dir = System.getProperty("notes.sharedDir") ?: error("notes.sharedDir not set (run through Gradle)")
        val json = Json { ignoreUnknownKeys = true }
        val suite = json.decodeFromString<Suite>(File(dir, "agent-fixtures/made-to.json").readText())
        assertTrue(suite.cases.isNotEmpty())
        for (c in suite.cases) {
            assertEquals(c.want, madeTo(AgentChange(c.before, c.after, c.op), c.now, c.cursor), c.name)
        }
    }
}
