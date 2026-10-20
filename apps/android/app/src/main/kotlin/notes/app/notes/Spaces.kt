package notes.app.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import notes.app.R
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.Sheet
import notes.app.ui.Type
import notes.core.db.Space
import notes.core.db.Spaces

/** The space's name, or the default's while the list loads. */
fun List<Space>.nameOf(id: String): String = firstOrNull { it.id == id }?.name ?: Spaces.DEFAULT_NAME

/** The space's icon key, "" (the app's icon) while the list loads. */
fun List<Space>.iconOf(id: String): String = firstOrNull { it.id == id }?.icon.orEmpty()

// The app's own icon leads the picker: "" is the default space's to begin with.
private val choices = listOf(SpaceIcon("", "App icon", R.drawable.brand_mark)) + SPACE_ICONS
private val iconsByKey = choices.associateBy { it.key }

/** A space's icon: the app's own for "" or a key this app doesn't have. Decorative. */
@Composable
fun SpaceIconImage(icon: String, size: Dp, modifier: Modifier = Modifier) {
    Picture(iconsByKey[icon] ?: iconsByKey.getValue(""), size, modifier)
}

@Composable
private fun Picture(icon: SpaceIcon, size: Dp, modifier: Modifier = Modifier) {
    // The app's mark fills its square; the other icons leave a margin.
    val inset = if (icon.key.isEmpty()) size * 0.08f else 0.dp
    Image(painterResource(icon.drawable), null, modifier.size(size).padding(inset))
}

/** Every icon to choose from, [selected] ringed, in a grid that scrolls inside a dialog. */
@Composable
fun SpaceIconPicker(selected: String?, onSelect: (String) -> Unit, error: String? = null) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(14.dp)
    Column {
        Text("Icon", style = Type.label, color = t.ink2)
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(58.dp),
            Modifier
                .fillMaxWidth()
                .height(256.dp)
                .clip(shape)
                .background(t.field)
                .border(1.dp, if (error != null) t.danger else t.hair, shape)
                .selectableGroup(),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(choices, key = { it.key }) { icon ->
                val on = icon.key == selected
                val cell = RoundedCornerShape(10.dp)
                Box(
                    Modifier
                        .height(58.dp)
                        .clip(cell)
                        .then(if (on) Modifier.background(t.chip).border(2.dp, t.tint, cell) else Modifier)
                        .selectable(on, role = Role.RadioButton) { onSelect(icon.key) }
                        .semantics { contentDescription = icon.name },
                    contentAlignment = Alignment.Center
                ) {
                    Picture(icon, 40.dp)
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, style = Type.small, color = t.danger)
        }
    }
}

/**
 * The switcher between spaces (§7.7): the current space's icon and name,
 * opening a sheet of every space and, last, New space.
 */
@Composable
fun SpaceSwitcher(
    spaces: List<Space>,
    current: String,
    onSwitch: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.title,
    iconSize: Dp = 24.dp
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
        SpaceIconImage(spaces.iconOf(current), iconSize)
        Spacer(Modifier.width(10.dp))
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
        SpacePicker(
            "Spaces",
            spaces,
            current,
            onDismiss = { open = false },
            onCreate = {
                open = false
                onCreate()
            }
        ) { id ->
            open = false
            onSwitch(id)
        }
    }
}

/** A sheet listing [spaces] to pick one, [current] checked; with [onCreate], New space last. */
@Composable
fun SpacePicker(
    title: String,
    spaces: List<Space>,
    current: String?,
    onDismiss: () -> Unit,
    onCreate: (() -> Unit)? = null,
    onPick: (String) -> Unit
) {
    val t = LocalTokens.current
    Sheet(onDismiss = onDismiss, title = title) {
        // Scrolls once there are more spaces than the sheet's height shows.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SpaceRows(spaces, current, onPick)
            if (onCreate != null) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(1.dp).background(t.edge)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClick = onCreate)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Plus, null, Modifier.size(18.dp), tint = t.ink2)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text("New space", style = Type.body.copy(fontSize = 16.sp), color = t.ink)
                }
            }
        }
    }
}

@Composable
private fun SpaceRows(spaces: List<Space>, current: String?, onPick: (String) -> Unit) {
    val t = LocalTokens.current
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
            SpaceIconImage(s.icon, 24.dp)
            Spacer(Modifier.width(14.dp))
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
