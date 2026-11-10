package notes.app.agent

import kotlin.test.assertEquals
import notes.core.api.AgentRun
import notes.core.api.RunOverlays
import org.junit.Test

class RunsTest {
    private fun run(status: String, outcome: String = "", failure: String = "", overlays: RunOverlays? = null) =
        AgentRun("r", "menu", status, outcome = outcome, failure = failure, overlays = overlays)

    @Test
    fun `what became of a run, in a few words`() {
        assertEquals("Waiting", runStatus(run("queued")))
        assertEquals("Changed the note", runStatus(run("done", "edited")))
        assertEquals("Failed: it took too long", runStatus(run("failed", failure = "timeout")))
        assertEquals("Skipped: something new", runStatus(run("skipped", failure = "something new")))
    }

    @Test
    fun `showing isn't changing, and an accepted suggestion is the change`() {
        assertEquals("Showed 2 cards", runStatus(run("done", "no_change", overlays = RunOverlays(cards = 2))))
        assertEquals(
            "Suggested 3 changes · 1 accepted",
            runStatus(run("done", "edited", overlays = RunOverlays(suggestions = 3, accepted = 1)))
        )
        assertEquals(
            "Made a note · Showed a card",
            runStatus(run("done", "created", overlays = RunOverlays(cards = 1)))
        )
    }

    @Test
    fun `an undo counts what's left, not what an earlier one put back`() {
        assertEquals("Undone.", undoneText(2, listOf("undone")))
        assertEquals("Undone 1 of 3.", undoneText(1, listOf("changed", "locked", "undone")))
    }

    @Test
    fun `a run's note by title, else what's left of it`() {
        assertEquals("Untitled", noteName(run("done")))
        assertEquals("Deleted note", noteName(run("done").copy(noteDeleted = true)))
    }
}
