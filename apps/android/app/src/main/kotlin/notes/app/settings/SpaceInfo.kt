package notes.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.notes.SpaceIconImage
import notes.app.notes.SpaceTraits
import notes.app.notes.TraitEntry
import notes.app.notes.TraitRow
import notes.app.notes.name
import notes.app.notes.traitRows
import notes.app.sharing.PeoplePicker
import notes.app.ui.ButtonKind
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.Type
import notes.core.db.Space
import notes.core.db.Spaces

/**
 * A space's Space info (§7.8), from the list of spaces. Goes back by itself once the space is gone, deleted
 * here or elsewhere.
 */
@Composable
fun SpaceInfoScreen(
    graph: AppGraph,
    id: String,
    backLabel: String?,
    onBack: () -> Unit,
    /** Sharing's own page, for this space. */
    onSharing: () -> Unit = {}
) {
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val registry = remember(graph, onSharing) { sharingRegistry(graph, onSharing) }
    val space = spaces.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // The list starts empty while it loads: only a space seen here and then missing is gone.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(space != null) {
        if (space != null) {
            seen = true
        } else if (seen) {
            onBack()
        }
    }

    if (space == null) {
        SettingsFrame(backLabel, onBack, null) {}
        return
    }
    SpaceInfoPage(
        space,
        backLabel,
        onBack,
        error = error,
        registry = registry,
        onEdit = { editing = true },
        onDelete = {
            error = null
            scope.launch {
                val deleted = runCatching { graph.sync.spaces.delete(space.id) }
                if (deleted.getOrNull() == true) {
                    graph.scheduler.request()
                } else {
                    error = if (deleted.isFailure) {
                        "The space couldn't be deleted on this phone. Try again."
                    } else {
                        "Notes arrived in this space from another device. Move or delete them first."
                    }
                }
            }
        }
    )
    if (editing) {
        SpaceDialog(
            title = "Name and icon",
            action = "Save",
            initial = space.name,
            initialIcon = space.icon,
            taken = { name -> spaces.any { it.id != space.id && it.name.equals(name, ignoreCase = true) } },
            onDismiss = {
                editing = false
                saveError = null
            },
            failure = saveError
        ) { name, icon ->
            saveError = null
            scope.launch {
                if (runCatching { graph.sync.spaces.update(space.id, name, icon) }.isFailure) {
                    saveError = "Couldn't save on this phone. Try again."
                    return@launch
                }
                editing = false
                graph.scheduler.request()
            }
        }
    }
}

/**
 * The app's traits with sharing's ways in (§7.9): on, its row opens the Sharing page; off, Turn on picks someone
 * to invite. Offered for the account's own spaces but the default, and for a shared one its owner stopped sharing.
 */
internal fun sharingRegistry(graph: AppGraph, onSharing: () -> Unit): List<TraitEntry> = SpaceTraits.entries.map {
    if (it.key != "shared") return@map it
    it.with(
        settings = { _, onClose ->
            // A page of its own rather than a sheet over Space info: it has a lot to show.
            LaunchedEffect(Unit) {
                onClose()
                onSharing()
            }
        },
        turnOn = { space, onClose ->
            val me by graph.userId.collectAsStateWithLifecycle()
            PeoplePicker(graph, space, me, onDismiss = onClose, onInvited = onClose)
        },
        offeredFor = { space -> space.id != Spaces.DEFAULT && space.shared?.isOwner != false }
    )
}

/** The page itself: the space, its traits, then Name and icon, and Delete. */
@Composable
internal fun SpaceInfoPage(
    space: Space,
    backLabel: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    error: String? = null,
    registry: List<TraitEntry> = SpaceTraits.entries
) {
    val t = LocalTokens.current
    // A trait's settings, or its turn-on flow, over the page.
    var opened by remember { mutableStateOf<(@Composable (Space, () -> Unit) -> Unit)?>(null) }
    val rows = traitRows(space.traits, registry, space)
    // A member's view of someone else's space: only its owner renames or deletes it (§7.9).
    val owner = space.shared?.let { if (it.isOwner) null else it.members.firstOrNull()?.username.orEmpty() }
    val deletable = space.id != Spaces.DEFAULT && space.noteCount == 0

    SettingsFrame(backLabel, onBack, null) {
        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SpaceIconImage(space.icon, 72.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                space.name,
                style = Type.display,
                color = t.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Text(noteCount(space), style = Type.rowMeta, color = t.ink3)
        }
        if (rows.isNotEmpty()) {
            Card {
                for ((i, row) in rows.withIndex()) {
                    if (i > 0) Divider()
                    when (row) {
                        is TraitRow.On -> TraitLine(
                            row.entry.glyph,
                            row.trait.name(),
                            row.trait.problem ?: row.trait.summary,
                            problem = row.trait.problem != null,
                            onClick = row.entry.settings?.let { { opened = it } }
                        )

                        is TraitRow.Off -> TraitLine(
                            row.entry.glyph,
                            row.entry.label,
                            "Off",
                            dimmed = true,
                            action = { NotesButton("Turn on", { opened = row.entry.turnOn }, small = true) }
                        )

                        is TraitRow.Unknown -> TraitLine(
                            SpaceTraits.unknownGlyph,
                            row.trait.name(),
                            row.trait.problem ?: row.trait.summary,
                            problem = row.trait.problem != null,
                            note = "Update the app to change this."
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        if (owner != null) {
            val who = if (owner.isEmpty()) "its owner" else "its owner, $owner,"
            Footnote("Only $who can rename or delete it.")
        } else {
            Card {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(role = Role.Button, onClick = onEdit)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Name and icon", style = Type.rowTitle, color = t.ink, modifier = Modifier.weight(1f))
                    Icon(Icons.Chevron, null, Modifier.size(16.dp), tint = t.ink3)
                }
                Divider()
                Action("Delete space", t.danger, enabled = deletable, onClick = onDelete)
            }
            when {
                space.id == Spaces.DEFAULT -> Footnote("This space can be renamed, not deleted.")
                space.noteCount > 0 -> Footnote("Move or delete its notes to delete it.")
            }
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
    }
    opened?.invoke(space) { opened = null }
}

/**
 * A trait's row: glyph, label, and its summary (a problem in the danger colour). With [onClick], it opens
 * the trait's settings; [dimmed] while off; [action] at the end; [note] under the summary.
 */
@Composable
private fun TraitLine(
    glyph: Int,
    label: String,
    summary: String,
    problem: Boolean = false,
    dimmed: Boolean = false,
    note: String? = null,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
) {
    val t = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // Off: the glyph dims; the words stay at a readable contrast.
            Icon(
                painterResource(glyph),
                null,
                Modifier.size(22.dp).alpha(if (dimmed) 0.5f else 1f),
                tint = if (problem) t.danger else t.ink3
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(label, style = Type.rowTitle, color = if (dimmed) t.ink2 else t.ink)
                if (summary.isNotEmpty()) {
                    Text(summary, style = Type.rowMeta, color = if (problem) t.danger else t.ink2)
                }
                if (note != null) Text(note, style = Type.small, color = t.ink3)
            }
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Chevron, null, Modifier.size(16.dp), tint = t.ink3)
        }
    }
}
