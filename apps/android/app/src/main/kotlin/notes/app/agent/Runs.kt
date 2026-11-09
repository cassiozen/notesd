package notes.app.agent

import notes.core.api.AgentRun
import notes.core.api.RunOverlays

// As the web's activity.tsx.

private val FAILURES = mapOf(
    "agent_off" to "the agent was turned off",
    "note_gone" to "the note was deleted",
    "note_moved" to "the note was moved",
    "note_locked" to "the note is locked",
    "daily_cap" to "today’s tokens were used up",
    "token_cap" to "it used too many tokens",
    "tool_cap" to "it took too many steps",
    "timeout" to "it took too long",
    "provider" to "the AI provider failed",
    "interrupted" to "the server stopped",
    "queue_full" to "too many runs were waiting",
    "unknown_trigger" to "this server doesn’t know the trigger",
    "editor_disconnected" to "the note was closed while the agent changed it",
    "note_closed" to "the note was closed"
)

private val OUTCOMES = mapOf(
    "edited" to "Changed the note",
    "created" to "Made a note",
    "no_change" to "No change",
    "undone" to "Undone",
    "partly_undone" to "Partly undone"
)

private val BLOCKED = mapOf(
    "undone" to "Already undone.",
    "running" to "The run hasn’t finished.",
    "deleted" to "The note was deleted since.",
    "locked" to "The note was locked since.",
    "changed" to "The note changed since, so undoing would lose those changes.",
    "not_saved" to "The change was made in the open note and hasn’t been saved on its own. Use Undo in the note."
)

/** Why a run failed or was skipped, in a few words. */
fun failureText(r: AgentRun): String = FAILURES[r.failure] ?: r.failure

/** Why a note's change can't be undone. */
fun blockedText(reason: String): String = BLOCKED[reason] ?: reason

private fun overlaysText(o: RunOverlays): String {
    val cards = when {
        o.cards == 1 -> "Showed a card"
        o.cards > 1 -> "Showed ${o.cards} cards"
        else -> null
    }
    val suggestions = when {
        o.suggestions == 1 -> "Suggested a change"
        o.suggestions > 1 -> "Suggested ${o.suggestions} changes"
        else -> null
    }
    val accepted = "${o.accepted} accepted".takeIf { o.suggestions > 0 && o.accepted > 0 }
    return listOfNotNull(cards, suggestions, accepted).joinToString(" · ")
}

/** What became of a run, in a few words. */
fun runStatus(r: AgentRun): String = when (r.status) {
    "queued" -> "Waiting"

    "running" -> "Running"

    "done" -> {
        val outcome = OUTCOMES[r.outcome] ?: "Done"
        val shown = r.overlays?.let(::overlaysText).orEmpty()
        when {
            shown.isEmpty() -> outcome

            // Showing isn't changing; an accepted suggestion is the change.
            r.outcome == "no_change" || (r.outcome == "edited" && (r.overlays?.accepted ?: 0) > 0) -> shown

            else -> "$outcome · $shown"
        }
    }

    "failed" -> "Failed: ${failureText(r)}"

    "skipped" -> "Skipped: ${failureText(r)}"

    else -> r.status
}

fun noteName(r: AgentRun): String = r.noteTitle.ifEmpty { if (r.noteDeleted) "Deleted note" else "Untitled" }

/** Still to finish: what's shown is fetched again until it does. */
fun going(r: AgentRun) = r.status == "queued" || r.status == "running"

/** After an undo: how much of it was, left-over notes an earlier undo put back not counted. */
fun undoneText(undone: Int, skipped: List<String>): String {
    val left = skipped.count { it != "undone" }
    return if (left > 0) "Undone $undone of ${undone + left}." else "Undone."
}
