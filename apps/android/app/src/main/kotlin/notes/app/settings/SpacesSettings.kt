package notes.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.notes.SpaceIconPicker
import notes.app.notes.SpaceIdentity
import notes.app.ui.ButtonKind
import notes.app.ui.Icons
import notes.app.ui.LocalSpaceTheme
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.SpaceTheme
import notes.app.ui.SpaceThemes
import notes.app.ui.Type
import notes.core.db.Space
import notes.core.db.Spaces

/** How many notes a space has here, "1 note" or "3 notes". */
internal fun noteCount(space: Space) = "${space.noteCount} ${if (space.noteCount == 1) "note" else "notes"}"

/**
 * Spaces (§7.7): each opens its Space info, where it's renamed and deleted (§7.8); New space last. The sidebar's
 * switcher can make one too.
 */
@Composable
internal fun SpacesCard(graph: AppGraph, onCreated: (String) -> Unit, onOpen: (String) -> Unit) {
    val t = LocalTokens.current
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    Card {
        for ((i, s) in spaces.withIndex()) {
            if (i > 0) Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(role = Role.Button) { onOpen(s.id) }
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpaceIdentity(
                    s.icon,
                    s.name,
                    s.traits,
                    Modifier.weight(1f),
                    style = Type.rowTitle,
                    iconSize = 28.dp,
                    subtitle = { Text(noteCount(s), style = Type.rowMeta, color = t.ink2) }
                )
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Chevron, null, Modifier.size(16.dp), tint = t.ink3)
            }
        }
        Divider()
        Action("New space", t.link, enabled = spaces.isNotEmpty()) { creating = true }
    }
    Text(
        "Keep separate contexts, like Work and Personal, apart. Each space has its own notes and tags, " +
            "and new notes go to the one you're in.",
        style = Type.small,
        color = t.ink3,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
    )

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
    var failure by remember { mutableStateOf<String?>(null) }
    SpaceDialog(
        title = "New space",
        action = "Create",
        initial = "",
        initialIcon = null,
        taken = { name -> spaces.any { it.name.equals(name, ignoreCase = true) } },
        onDismiss = onDismiss,
        failure = failure
    ) { name, icon ->
        if (saving) return@SpaceDialog
        saving = true
        failure = null
        scope.launch {
            val id = runCatching { graph.sync.spaces.create(name, icon) }.getOrElse {
                failure = "The space couldn't be saved on this phone. Try again."
                saving = false
                return@launch
            }
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
internal fun SpaceDialog(
    title: String,
    action: String,
    initial: String,
    initialIcon: String?,
    taken: (String) -> Boolean,
    onDismiss: () -> Unit,
    /** Why the last [onDone] didn't save, shown under the buttons; the dialog stays, with what was chosen. */
    failure: String? = null,
    onDone: (name: String, icon: String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    var icon by remember { mutableStateOf(initialIcon) }
    var iconError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // The icon picked brings its colours: the dialog shows them before the space has them.
    SpaceTheme(icon?.let(SpaceThemes::key) ?: LocalSpaceTheme.current) {
        NotesDialog(onDismiss, title) {
            ScrollingBody {
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

                            // The server refuses them, so the store does too; a paste can bring them in.
                            n.any { Character.isISOControl(it) } -> "Remove the special characters from the name."

                            else -> null
                        }
                        iconError = if (icon == null) "Choose an icon." else null
                        val chosen = icon
                        if (n != null && chosen != null && error == null) onDone(n, chosen)
                    }, Modifier.weight(1f))
                }
                failure?.let {
                    Text(
                        it,
                        style = Type.small,
                        color = LocalTokens.current.danger,
                        modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
    }
}
