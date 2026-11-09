package notes.app.notes

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import notes.app.editor.NoteEditText

/**
 * The notes open in an editor here, for an agent to read and change (docs/contributing/agent-clients.md). Opened
 * and closed are counted by [EditorViewModel], which outlives a rotation or a screen pushed over the note, and is
 * cleared by every way out of it: Back, the two-pane swap, switching space, a new note from the launcher, delete
 * and move all take the note's screen off the back stack.
 */
class OpenNotes {
    private val open = HashMap<String, MutableList<EditorViewModel>>()

    private val _ids = MutableStateFlow<Set<String>>(emptySet())

    /** The ids of the notes open now. */
    val ids: StateFlow<Set<String>> = _ids

    private val _closed = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** A note's last editor went: it's closed. */
    val closed: SharedFlow<String> = _closed

    @Synchronized
    internal fun opened(vm: EditorViewModel) {
        open.getOrPut(vm.id) { ArrayList() } += vm
        _ids.value = open.keys.toSet()
    }

    @Synchronized
    internal fun closed(vm: EditorViewModel) {
        val vms = open[vm.id] ?: return
        vms.remove(vm)
        if (vms.isNotEmpty()) return
        open.remove(vm.id)
        _ids.value = open.keys.toSet()
        _closed.tryEmit(vm.id)
    }

    /** The open note's view model, the latest when it's open twice; null when it isn't open. */
    @Synchronized
    fun note(id: String): EditorViewModel? = open[id]?.lastOrNull()

    /**
     * The open note's editor. Its view can be missing a moment while the note stays open (a rotation), or for as
     * long as it's locked (a placeholder shows instead): waits up to [waitMs] for it. Null: none.
     */
    suspend fun editor(id: String, waitMs: Long = VIEW_WAIT_MS): NoteEditText? {
        val vm = note(id) ?: return null
        vm.view.value?.let { return it }
        return withTimeoutOrNull(waitMs) { vm.view.first { it != null } }
    }

    private companion object {
        const val VIEW_WAIT_MS = 2_000L
    }
}
