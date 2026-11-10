package notes.core.agent

import kotlinx.serialization.Serializable
import notes.core.text.codePointArray

/** A note as an agent reads and writes it: its title and its Markdown content. */
@Serializable
data class Fields(val title: String, val content: String)

/**
 * A change as the agent asked for it (the server's agent.edit). [kind]: `title`, `replace`, `insert` or
 * `content`; [at] for an insert: `start`, `end` or `cursor`.
 */
@Serializable
data class AgentOp(
    val kind: String,
    val title: String = "",
    val find: String = "",
    val replace: String = "",
    val at: String = "",
    val text: String = ""
)

/** A run's change: [after] for [before], or [op] made to what the editor shows instead (agent.EditorChange). */
@Serializable
data class AgentChange(val before: Fields, val after: Fields, val op: AgentOp)

/**
 * [now] with the change made to it: [AgentChange.after] if [now] is its before, else its op made to [now], as
 * the server's EditorChange.MadeTo does, so typing meanwhile doesn't make the run read the note again. Null when
 * the op no longer fits: its find isn't there exactly once, or an insert at the cursor has none. [cursor]: code
 * points into [now]'s content. Checked against shared/agent-fixtures/made-to.json.
 */
fun madeTo(change: AgentChange, now: Fields, cursor: Int?): Fields? {
    if (now == change.before) return change.after
    return madeTo(change.op, now, cursor)
}

/** [op] made to [now], as a suggestion the user accepts is: null when it no longer fits. */
fun madeTo(op: AgentOp, now: Fields, cursor: Int?): Fields? {
    return when (op.kind) {
        "title" -> now.copy(title = op.title)

        "replace" -> {
            val at = now.content.indexOf(op.find)
            // Overlapping matches count too, as on the server: "aa" is twice in "aaa".
            if (op.find.isEmpty() || at < 0 || now.content.indexOf(op.find, at + 1) >= 0) return null
            now.copy(content = now.content.replaceRange(at, at + op.find.length, op.replace))
        }

        "insert" -> when {
            op.at != "cursor" -> now.copy(content = insertText(now.content, op.text, atStart = op.at == "start"))
            cursor == null -> null
            else -> now.copy(content = insertAt(now.content, cursor, op.text))
        }

        // Made again, the whole content would wipe out what the user typed.
        else -> null
    }
}

/** [text] as its own block at the start or end of [content], as the server's insertText adds it. */
private fun insertText(content: String, text: String, atStart: Boolean): String = when {
    content.trim('\n').isEmpty() -> "$text\n"
    atStart -> "$text\n\n${content.trimStart('\n')}"
    else -> "${content.trimEnd('\n')}\n\n$text\n"
}

/** [text] at a code point offset into [content], clamped to it. */
private fun insertAt(content: String, at: Int, text: String): String {
    val points = content.codePointArray()
    val n = at.coerceIn(0, points.size)
    val split = points.take(n).sumOf { if (it >= 0x10000) 2 else 1 }
    return content.substring(0, split) + text + content.substring(split)
}
