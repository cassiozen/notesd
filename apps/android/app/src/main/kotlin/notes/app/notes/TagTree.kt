package notes.app.notes

import android.content.SharedPreferences
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import notes.app.ui.Backdrop
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.Type
import notes.app.ui.pressScale
import notes.core.db.NoteFilter
import notes.core.db.NoteStore
import notes.core.db.Space
import notes.core.db.Spaces
import notes.core.db.TagCount

/** Which parent tags are expanded, kept in [prefs]. */
class TagTreeState(private val prefs: SharedPreferences) {
    private val _expanded = MutableStateFlow(prefs.getStringSet(EXPANDED, emptySet()).orEmpty().toSet())
    val expanded: StateFlow<Set<String>> = _expanded

    fun setExpanded(key: String, expanded: Boolean) {
        val next = if (expanded) _expanded.value + key else _expanded.value - key
        _expanded.value = next
        prefs.edit { putStringSet(EXPANDED, next) }
    }

    private companion object {
        const val EXPANDED = "tags_expanded"
    }
}

class TagNode(val tag: TagCount, val label: String, val children: List<TagNode>)

/** Nests the store's flat list (parents included) by key; a tag whose parent is missing goes at the top. */
fun buildTagTree(tags: List<TagCount>): List<TagNode> {
    val sorted = tags.sortedBy { it.key }
    val keys = sorted.map { it.key }.toSet()
    fun parentOf(key: String) = key.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && it in keys }
    val byParent = sorted.groupBy { parentOf(it.key) }
    fun build(parent: String?): List<TagNode> = byParent[parent].orEmpty().map { t ->
        val label = if (parent == null) t.name else t.name.substringAfterLast('/')
        TagNode(t, label, build(t.key))
    }
    return build(null)
}

/** The rows the open section shows: each node, then its children if expanded. */
private fun visibleRows(nodes: List<TagNode>, expanded: Set<String>, depth: Int = 0): List<Pair<TagNode, Int>> =
    nodes.flatMap { n ->
        listOf(n to depth) + if (n.tag.key in expanded) visibleRows(n.children, expanded, depth + 1) else emptyList()
    }

/** Every tag, for the tags screen and sidebar; the list's filter is shared with the notes list. */
@OptIn(ExperimentalCoroutinesApi::class)
class TagsViewModel(store: NoteStore, private val filter: MutableStateFlow<NoteFilter?>, space: StateFlow<String>) :
    ViewModel() {
    /** The space's tags (§7.7). */
    val tags: StateFlow<List<TagCount>> =
        combine(store.changes.onSubscription { emit(Unit) }, space) { _, s -> s }.mapLatest { store.tags(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Hides the Locked row while there are none. */
    val lockedCount: StateFlow<Int> =
        combine(store.changes.onSubscription { emit(Unit) }, space) { _, s -> s }.mapLatest { store.lockedCount(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val selected: StateFlow<NoteFilter?> = filter

    fun show(f: NoteFilter?) {
        filter.value = f
    }
}

/**
 * Where the list can point: All notes, Untagged, Locked and the tag tree, with
 * settings in the header. A screen of its own on narrower windows; [docked]
 * beside the list on wide ones.
 */
@Composable
fun TagsPane(
    vm: TagsViewModel,
    state: TagTreeState,
    docked: Boolean,
    onShow: (NoteFilter?) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** Every space, and the one shown: the header switches between them and makes new ones (§7.7). */
    spaces: List<Space> = emptyList(),
    space: String = Spaces.DEFAULT,
    onSpace: (String) -> Unit = {},
    onNewSpace: () -> Unit = {}
) {
    val t = LocalTokens.current
    val tags by vm.tags.collectAsStateWithLifecycle()
    val lockedCount by vm.lockedCount.collectAsStateWithLifecycle()
    val filter by vm.selected.collectAsStateWithLifecycle()
    val expanded by state.expanded.collectAsStateWithLifecycle()
    val tree = remember(tags) { buildTagTree(tags) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier
            .fillMaxHeight()
            .background(t.bg)
            .then(
                if (docked) {
                    Modifier.drawWithContent {
                        drawContent()
                        drawRect(t.hairStrong, Offset(size.width - 1f, 0f), Size(1f, size.height))
                    }
                } else {
                    Modifier
                }
            )
    ) {
        if (!docked) Backdrop()
        LazyColumn(
            contentPadding = PaddingValues(bottom = bottom + 24.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding()
        ) {
            item(key = "tags:title", contentType = "title") {
                Row(
                    Modifier.fillMaxWidth().padding(
                        start = 20.dp,
                        end = 8.dp,
                        top = 12.dp,
                        bottom = 12.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The space, always, the whole row before Settings opening its sheet. Before the
                    // list loads, the default: the switcher keeps its place.
                    SpaceSwitcher(
                        spaces.ifEmpty { listOf(Space(Spaces.DEFAULT, "Notes", 0)) },
                        space,
                        onSpace,
                        onNewSpace,
                        Modifier.weight(1f).offset(x = (-6).dp).semantics { heading() },
                        style = Type.title.copy(fontSize = 26.sp, lineHeight = 30.sp),
                        iconSize = 30.dp
                    )
                    SettingsButton(onSettings)
                }
            }
            // Kept while selected, so the last note unlocked doesn't pull the row from under the list.
            tagRows(
                tree,
                filter,
                showLocked = lockedCount > 0 || filter == NoteFilter.Locked,
                expanded = expanded,
                onExpand = state::setExpanded,
                onFilter = onShow
            )
            if (tree.isEmpty()) {
                item(key = "tags:hint", contentType = "hint") {
                    Text(
                        "Type #tag in a note to group it here. #parent/child nests.",
                        style = Type.small,
                        color = t.ink3,
                        modifier = Modifier.padding(start = 72.dp, end = 20.dp, top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .pressScale(interaction, 0.9f)
            .clickable(interaction, ripple(bounded = false, radius = 22.dp), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Settings" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(t.chip)
                .border(1.dp, t.edge, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Settings, null, Modifier.size(20.dp), tint = t.ink)
        }
    }
}

/** All notes, Untagged, Locked and the tag tree, as list items. */
private fun LazyListScope.tagRows(
    tree: List<TagNode>,
    filter: NoteFilter?,
    showLocked: Boolean,
    expanded: Set<String>,
    onExpand: (String, Boolean) -> Unit,
    onFilter: (NoteFilter?) -> Unit
) {
    item(key = "tags:all", contentType = "tag") {
        TagRow("All notes", null, 0, filter == null, null, { onFilter(null) }, null, Modifier.animateItem())
    }
    item(key = "tags:untagged", contentType = "tag") {
        TagRow(
            "Untagged",
            null,
            0,
            filter == NoteFilter.Untagged,
            null,
            { onFilter(NoteFilter.Untagged) },
            null,
            Modifier.animateItem()
        )
    }
    if (showLocked) {
        item(key = "tags:locked", contentType = "tag") {
            TagRow(
                "Locked",
                null,
                0,
                filter == NoteFilter.Locked,
                null,
                { onFilter(NoteFilter.Locked) },
                null,
                Modifier.animateItem(),
                icon = Icons.Lock
            )
        }
    }
    item(key = "tags:section", contentType = "section") {
        Text(
            "Tags",
            style = Type.small,
            fontWeight = FontWeight.SemiBold,
            color = LocalTokens.current.ink3,
            modifier = Modifier.animateItem().padding(start = 62.dp, top = 16.dp, bottom = 4.dp).semantics { heading() }
        )
    }
    for ((node, depth) in visibleRows(tree, expanded)) {
        val key = node.tag.key
        item(key = "tag:$key", contentType = "tag") {
            val isOpen = key in expanded
            TagRow(
                label = node.label,
                count = node.tag.count,
                depth = depth,
                selected = (filter as? NoteFilter.Tag)?.key == key,
                expandedState = if (node.children.isEmpty()) null else isOpen,
                onClick = { onFilter(NoteFilter.Tag(key, node.tag.name)) },
                onExpand = { onExpand(key, !isOpen) },
                modifier = Modifier.animateItem(),
                description = "#${node.tag.name}, ${notesCount(node.tag.count)}"
            )
        }
    }
}

private fun notesCount(n: Int) = if (n == 1) "1 note" else "$n notes"

/**
 * One row: a 48dp expander for parents (or the same space empty), then the
 * name and its count. [expandedState] is null for rows without children.
 */
@Composable
private fun TagRow(
    label: String,
    count: Int?,
    depth: Int,
    selected: Boolean,
    expandedState: Boolean?,
    onClick: () -> Unit,
    onExpand: (() -> Unit)?,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp + (depth * 16).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (expandedState != null && onExpand != null) {
            val turn by animateFloatAsState(if (expandedState) 90f else 0f, label = "tagChevron")
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button, onClick = onExpand)
                    .semantics { contentDescription = "${if (expandedState) "Collapse" else "Expand"} $label" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Chevron, null, Modifier.size(12.dp).rotate(turn), tint = t.ink3)
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(if (selected) t.on else Color.Transparent)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp)
                .then(
                    if (description != null) {
                        Modifier.clearAndSetSemantics {
                            contentDescription = description
                            this.selected = selected
                        }
                    } else {
                        Modifier.semantics { this.selected = selected }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val glyph = icon ?: if (count != null) Icons.Hash else null
            if (glyph != null) {
                Icon(glyph, null, Modifier.size(15.dp), tint = t.ink3)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                style = Type.body,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (count != null) Text(count.toString(), style = Type.small, color = t.ink3)
        }
    }
}
