package notes.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.notes.SpaceIconImage
import notes.app.notes.SpaceIconPicker
import notes.app.ui.ButtonKind
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.Type
import notes.core.db.Space
import notes.core.db.Spaces

private fun notesIn(space: Space) = "${space.noteCount} ${if (space.noteCount == 1) "note" else "notes"}" +
    when {
        space.id == Spaces.DEFAULT -> " · Can be renamed, not deleted"
        space.noteCount > 0 -> " · Move or delete its notes to delete it"
        else -> ""
    }

/** Spaces (§7.7): create, rename and delete them. The sidebar's switcher can make one too. */
@Composable
internal fun SpacesCard(graph: AppGraph, onCreated: (String) -> Unit) {
    val t = LocalTokens.current
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Space?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    Card {
        for ((i, s) in spaces.withIndex()) {
            if (i > 0) Divider()
            Row(
                Modifier.fillMaxWidth().heightIn(
                    min = 64.dp
                ).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpaceIconImage(s.icon, 28.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = Type.rowTitle, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(notesIn(s), style = Type.rowMeta, color = t.ink2)
                }
                NotesButton(
                    "Edit",
                    { editing = s },
                    Modifier.semantics { contentDescription = "Edit ${s.name}" },
                    kind = ButtonKind.Ghost,
                    small = true
                )
                if (s.id != Spaces.DEFAULT) {
                    NotesButton(
                        "Delete",
                        {
                            error = null
                            scope.launch {
                                val deleted = runCatching { graph.sync.spaces.delete(s.id) }
                                if (deleted.getOrNull() == true) {
                                    graph.scheduler.request()
                                } else {
                                    error = if (deleted.isFailure) {
                                        "The space couldn't be deleted on this phone. Try again."
                                    } else {
                                        "Notes arrived in that space from another device. Move or delete them first."
                                    }
                                }
                            }
                        },
                        Modifier.semantics { contentDescription = "Delete ${s.name}" },
                        kind = ButtonKind.DangerText,
                        enabled = s.noteCount == 0,
                        small = true
                    )
                }
            }
        }
        Divider()
        Action("New space", t.link, enabled = spaces.isNotEmpty()) { creating = true }
    }
    error?.let {
        Text(
            it,
            style = Type.small,
            color = t.danger,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).semantics {
                liveRegion = LiveRegionMode.Polite
            }
        )
    }
    Text(
        "Keep separate contexts, like Work and Personal, apart. Each space has its own notes and tags, " +
            "and new notes go to the one you're in.",
        style = Type.small,
        color = t.ink3,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
    )

    editing?.let { space ->
        SpaceDialog(
            title = "Edit space",
            action = "Save",
            initial = space.name,
            initialIcon = space.icon,
            taken = { name -> spaces.any { it.id != space.id && it.name.equals(name, ignoreCase = true) } },
            onDismiss = { editing = null }
        ) { name, icon ->
            editing = null
            error = null
            scope.launch {
                if (runCatching { graph.sync.spaces.update(space.id, name, icon) }.isFailure) {
                    error = "${space.name} couldn't be saved on this phone. Try again."
                    return@launch
                }
                graph.scheduler.request()
            }
        }
    }
    if (creating) {
        NewSpaceDialog(graph, onDismiss = { creating = false }) { id ->
            creating = false
            onCreated(id)
        }
    }
}

/** A new space's name and icon; [onCreated] once it's saved on this device, to sync, and listed. */
@Composable
fun NewSpaceDialog(graph: AppGraph, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // One create per dialog: a second tap mustn't make a twin before the list shows the first.
    var saving by remember { mutableStateOf(false) }
    SpaceDialog(
        title = "New space",
        action = "Create",
        initial = "",
        initialIcon = null,
        taken = { name -> spaces.any { it.name.equals(name, ignoreCase = true) } },
        onDismiss = onDismiss
    ) { name, icon ->
        if (saving) return@SpaceDialog
        saving = true
        scope.launch {
            val id = graph.sync.spaces.create(name, icon)
            graph.scheduler.request()
            // Shown once the list has it: switched to sooner, a list read before the insert would
            // look as if it were deleted and send the switch back to the default.
            graph.spaces.first { list -> list.any { it.id == id } }
            onCreated(id)
        }
    }
}

/** A space's name and icon. */
@Composable
private fun SpaceDialog(
    title: String,
    action: String,
    initial: String,
    initialIcon: String?,
    taken: (String) -> Boolean,
    onDismiss: () -> Unit,
    onDone: (name: String, icon: String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    var icon by remember { mutableStateOf(initialIcon) }
    var iconError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    NotesDialog(onDismiss, title) {
        NotesField(name, {
            name = it
            error = null
        }, "Name", placeholder = "Work", error = error)
        Spacer(Modifier.height(12.dp))
        SpaceIconPicker(icon, {
            icon = it
            iconError = null
        }, iconError)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotesButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.Chip)
            NotesButton(action, {
                val n = Spaces.normalize(name)
                error = when {
                    n == null -> "Enter a name."
                    n.codePointCount(0, n.length) > Spaces.MAX_NAME -> "At most ${Spaces.MAX_NAME} characters."
                    taken(n) -> "Another space has this name."
                    else -> null
                }
                iconError = if (icon == null) "Choose an icon." else null
                val chosen = icon
                if (n != null && chosen != null && error == null) onDone(n, chosen)
            }, Modifier.weight(1f))
        }
    }
}
