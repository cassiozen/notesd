package notes.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.Type
import notes.app.ui.corners
import notes.app.ui.floating
import notes.core.db.TagCount

/** At most this many suggestions while typing a tag. */
private const val SUGGESTIONS = 8

/** Existing tags for what's typed after #: whole-name prefix first, then any level's prefix, then anywhere. */
fun suggestTags(tags: List<TagCount>, query: String): List<TagCount> {
    val q = query.lowercase()
    if (q.isEmpty()) return emptyList()
    fun rank(t: TagCount) = when {
        t.key.startsWith(q) -> 0
        t.key.split('/').any { it.startsWith(q) } -> 1
        q in t.key -> 2
        else -> 3
    }
    return tags
        .map { it to rank(it) }
        .filter { (t, r) -> r < 3 && t.key != q }
        .sortedWith(
            compareBy<Pair<TagCount, Int>> {
                it.second
            }.thenByDescending { it.first.count }.thenBy { it.first.key }
        )
        .take(SUGGESTIONS)
        .map { it.first }
}

/**
 * Above the formatting bar while the cursor is in a tag: existing tags to
 * complete what's being typed, or a way to the notes with this tag.
 */
@Composable
fun TagStrip(tag: TagAtCursor, tags: List<TagCount>, onPick: (String) -> Unit, onShow: (String) -> Unit) {
    val suggestions = tag.query?.let { suggestTags(tags, it) }.orEmpty()
    Row(
        Modifier
            .widthIn(max = 600.dp)
            .floating()
            .horizontalScroll(rememberScrollState())
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (suggestions.isNotEmpty()) {
            for (s in suggestions) Chip("#${s.name}", "Insert tag ${s.name}") { onPick(s.name) }
        } else {
            Chip("Notes tagged #${tag.name}", "Show notes tagged ${tag.name}", icon = true) { onShow(tag.name) }
        }
    }
}

@Composable
private fun Chip(label: String, description: String, icon: Boolean = false, onClick: () -> Unit) {
    val t = LocalTokens.current
    val shape = corners(12.dp)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(t.chip)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                onClick {
                    onClick()
                    true
                }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon) {
            Icon(Icons.Hash, null, Modifier.size(15.dp), tint = t.ink3)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = Type.body, color = t.ink, maxLines = 1)
    }
}
