package notes.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import notes.app.AppGraph
import notes.app.notes.TraitEntry
import notes.app.notes.TraitLine
import notes.app.notes.TraitRow
import notes.app.notes.traitRows
import notes.app.ui.Icons
import notes.app.ui.InlineConfirm
import notes.app.ui.LocalTokens
import notes.app.ui.SpaceTheme
import notes.app.ui.SpaceThemes
import notes.app.ui.Type

/**
 * A trait's page (§7.8), opened from its row in Space info as a screen of its own, so the back gesture (and its
 * preview) goes back to Space info. Its title and what it's about, then [content]; [actions] sit in the top bar,
 * in reach without scrolling. A section with changes not yet saved (`Unsaved`) is asked about before going back.
 */
@Composable
internal fun TraitPage(
    spaceName: String,
    spaceIcon: String,
    title: String,
    back: () -> Unit,
    lead: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val unsaved = remember { UnsavedSections() }
    var discarding by remember { mutableStateOf(false) }
    val changed = unsaved.unsaved
    val leave = { if (changed.isEmpty()) back() else discarding = true }
    BackHandler(enabled = changed.isNotEmpty() && !discarding) { discarding = true }
    val t = LocalTokens.current
    SpaceTheme(SpaceThemes.key(spaceIcon)) {
        SettingsFrame(
            spaceName,
            leave,
            null,
            top = {
                InlineConfirm(
                    visible = discarding && changed.isNotEmpty(),
                    title = "Discard your changes?",
                    text = "Your changes to ${changed.joinToString(" and ")} aren't saved.",
                    action = "Discard",
                    onConfirm = {
                        discarding = false
                        back()
                    },
                    onCancel = { discarding = false },
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                )
            },
            actions = actions
        ) {
            CompositionLocalProvider(LocalUnsaved provides unsaved) {
                Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp)) {
                    Text(title, style = Type.display, color = t.ink, modifier = Modifier.semantics { heading() })
                    if (lead != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(lead, style = Type.body, color = t.ink2)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
            }
        }
    }
}

/**
 * One row of Space info's list, the same for every trait: its glyph, name, and a line saying how it's set (or its
 * problem, in the danger colour). [onOpen], when the line opens, goes to its page.
 */
@Composable
internal fun TraitLineRow(glyph: Int, line: TraitLine, onOpen: (() -> Unit)?) {
    val t = LocalTokens.current
    val open = onOpen.takeIf { line.opens }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // On, the tile lights up, so what's set reads at a glance down the list.
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(if (line.on) t.on else t.hover),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(glyph), null, Modifier.size(18.dp), tint = if (line.on) t.ink else t.ink3)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(line.label, style = Type.rowTitle, color = t.ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (line.live && !line.problem) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(t.good))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    line.status,
                    style = Type.rowMeta,
                    color = when {
                        line.problem -> t.danger
                        line.on -> t.ink2
                        else -> t.ink3
                    },
                    // Two lines, not one: large text wraps a status rather than cutting it off.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        line.end?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
        if (open != null) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Chevron, null, Modifier.size(16.dp), tint = t.ink3)
        }
    }
}

/** Between two rows of Space info's list, from where the words start: the tiles read as one column. */
@Composable
internal fun TraitDivider() {
    Box(Modifier.padding(start = 62.dp).fillMaxWidth().height(1.dp).background(LocalTokens.current.hair))
}

/**
 * A trait's page for a space, as a screen. Goes back by itself once the page isn't offered (its trait off and no
 * longer turned on here, say, or handed on to a member with nothing to change), and leaves Space info too once
 * the space is gone, deleted or left.
 */
@Composable
fun TraitPageScreen(
    graph: AppGraph,
    id: String,
    key: String,
    onBack: () -> Unit,
    onGone: () -> Unit,
    onActivity: (String) -> Unit = {}
) {
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val registry = rememberSpaceInfoRegistry(graph, onActivity)
    val space = spaces.firstOrNull { it.id == id }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(space != null) {
        if (space != null) {
            seen = true
        } else if (seen) {
            onGone()
        }
    }
    if (space == null) {
        SettingsFrame(null, onBack, null) {}
        return
    }
    val entry = traitRows(space, registry).firstNotNullOfOrNull { it.entry()?.takeIf { e -> e.key == key } }
    val page = entry?.page
    val offered = page != null && entry.row?.invoke(space)?.opens == true
    LaunchedEffect(offered) { if (!offered) onBack() }
    if (page == null || !offered) {
        SettingsFrame(space.name, onBack, null) {}
        return
    }
    page(space, onBack)
}

internal fun TraitRow.entry(): TraitEntry? = when (this) {
    is TraitRow.On -> entry
    is TraitRow.Off -> entry
    is TraitRow.Unknown -> null
}
