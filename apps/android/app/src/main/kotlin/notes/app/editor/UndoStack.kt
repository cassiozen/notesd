package notes.app.editor

import android.text.SpannableStringBuilder
import android.text.Spanned

/**
 * Undo by snapshot: the text with the editor's spans, and the selection. The
 * platform's undo records text only, so undoing a block change or a deletion
 * across blocks would bring the text back with the wrong structure.
 *
 * Typing coalesces into one step until a pause, a switch between typing and
 * deleting, or a command (formatting, checkbox, Enter).
 */
class UndoStack(private val limit: Int = 200, private val pauseMillis: Long = 1000) {
    class State(val text: Spanned, val selStart: Int, val selEnd: Int)

    enum class Kind { Typing, Deleting, Command }

    private val undo = ArrayDeque<State>()
    private val redo = ArrayDeque<State>()
    private var lastKind: Kind? = null
    private var lastAt = 0L

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** Call before a change, with the state before it. */
    fun record(kind: Kind, now: Long, current: () -> State) {
        val coalesce = kind != Kind.Command && kind == lastKind && now - lastAt < pauseMillis
        if (!coalesce) {
            undo.addLast(current())
            if (undo.size > limit) undo.removeFirst()
        }
        redo.clear()
        lastKind = kind
        lastAt = now
    }

    /** [state] as a step of its own, before a change that isn't typing: what comes after starts afresh. */
    fun push(state: State) {
        undo.addLast(state)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
        lastKind = null
    }

    fun breakCoalescing() {
        lastKind = null
    }

    fun undo(current: State): State? {
        val state = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        lastKind = null
        return state
    }

    fun redo(current: State): State? {
        val state = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        lastKind = null
        return state
    }

    fun clear() {
        undo.clear()
        redo.clear()
        lastKind = null
    }

    companion object {
        /** A copy of [text] holding only the editor's spans (no IME or selection spans). */
        fun snapshot(text: Spanned, selStart: Int, selEnd: Int): State {
            val copy = SpannableStringBuilder(text.toString())
            for (span in text.getSpans(0, text.length, EditorSpan::class.java)) {
                copy.setSpan(span, text.getSpanStart(span), text.getSpanEnd(span), text.getSpanFlags(span))
            }
            return State(copy, selStart, selEnd)
        }
    }
}
