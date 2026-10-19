package notes.app.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.Sheet
import notes.app.ui.Type
import notes.core.db.Space
import notes.core.db.Spaces

/** The space's name, or the default's while the list loads. */
fun List<Space>.nameOf(id: String): String = firstOrNull { it.id == id }?.name ?: Spaces.DEFAULT_NAME

/**
 * The switcher between spaces (§7.7): the current space's name, opening a
 * sheet of every space. Only shown once there is more than one.
 */
@Composable
fun SpaceSwitcher(
    spaces: List<Space>,
    current: String,
    onSwitch: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.title
) {
    val t = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    val name = spaces.nameOf(current)
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = "Space: $name" }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            style = style,
            color = t.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(4.dp))
        // The chevron points right; turned down, it reads as a menu.
        Icon(Icons.Chevron, null, Modifier.size(14.dp).rotate(90f), tint = t.ink3)
    }
    if (open) {
        SpacePicker("Spaces", spaces, current, onDismiss = { open = false }) { id ->
            open = false
            onSwitch(id)
        }
    }
}

/** A sheet listing [spaces] to pick one, [current] checked. */
@Composable
fun SpacePicker(title: String, spaces: List<Space>, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val t = LocalTokens.current
    Sheet(onDismiss = onDismiss, title = title) {
        for (s in spaces) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.RadioButton) { onPick(s.id) }
                    .semantics { selected = s.id == current }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    s.name,
                    style = Type.body.copy(fontSize = 16.sp),
                    color = t.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (s.id == current) Icon(Icons.Check, null, Modifier.size(20.dp), tint = t.tint)
            }
        }
    }
}
