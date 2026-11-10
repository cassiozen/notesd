package notes.app.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import notes.core.agent.DroppedRun

/** What the menu command says when its run didn't go: AgentTriggersTest shows it, for the one it can reach. */
class WhyNotTest {
    @Test
    fun `off, unreachable while online, and offline each say why`() {
        assertEquals("The agent didn't run: it isn't on for this space.", AgentLink.whyNot(DroppedRun.Why.Off, true))
        assertEquals(
            "The agent didn't run: the server can't be reached.",
            AgentLink.whyNot(DroppedRun.Why.Unreachable, true)
        )
        assertEquals("The agent didn't run: you're offline.", AgentLink.whyNot(DroppedRun.Why.Unreachable, false))
    }
}
