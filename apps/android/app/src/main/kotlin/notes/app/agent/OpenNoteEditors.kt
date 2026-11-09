package notes.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import notes.app.editor.NoteEditText
import notes.app.notes.EditorViewModel
import notes.app.notes.OpenNotes
import notes.core.agent.AgentChange
import notes.core.agent.AgentEditors
import notes.core.agent.EditorReply
import notes.core.agent.madeTo
import notes.core.doc.Document
import notes.core.markdown.Markdown

/**
 * Runs read and change the notes open here in their editors (docs/contributing/agent-clients.md), where the
 * user sees the change land and can undo it. A locked note is never read or changed.
 */
class OpenNoteEditors(private val openNotes: OpenNotes) : AgentEditors {
    override suspend fun read(noteId: String): EditorReply = withEditor(noteId) { vm, editor ->
        val shown = editor.readForAgent()
        EditorReply.Shown(vm.spaceId(), shown.fields, shown.cursor, vm.shownRevision(shown.fields))
    }

    override suspend fun apply(noteId: String, runId: String, change: AgentChange): EditorReply =
        withEditor(noteId) { vm, editor ->
            // Found before the change waits out any typing; it holds only if the text the change is made to is this.
            val guess = editor.readForAgent().fields
            val guessRevision = vm.shownRevision(guess)
            var before = guess
            var fits = true
            editor.applyOutside(byAgent = true) { now ->
                before = now.fields
                val after = madeTo(change, now.fields, now.cursor)
                fits = after != null
                after?.let { Document.forNote(it.title, Markdown.parse(it.content)) }
            }
            if (!fits) return@withEditor CHANGED
            EditorReply.Applied(before, guessRevision.takeIf { before == guess }, editor.readForAgent().fields)
        }

    private suspend fun withEditor(
        noteId: String,
        answer: suspend (EditorViewModel, NoteEditText) -> EditorReply
    ): EditorReply = withContext(Dispatchers.Main) {
        val vm = openNotes.note(noteId) ?: return@withContext NOT_OPEN
        if (vm.isLocked()) return@withContext LOCKED
        // Missing a moment (a rotation), or for good: the note was locked meanwhile.
        val editor = openNotes.editor(noteId) ?: return@withContext if (vm.isLocked()) LOCKED else NOT_OPEN
        answer(vm, editor)
    }

    private fun EditorViewModel.isLocked() = locked.value || unsupported.value

    private companion object {
        val NOT_OPEN = EditorReply.Refused("not_open")
        val LOCKED = EditorReply.Refused("locked")
        val CHANGED = EditorReply.Refused("changed")
    }
}
