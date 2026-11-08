package notes.app.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import notes.app.ui.ButtonKind
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.Sheet
import notes.app.ui.SpaceThemes
import notes.app.ui.Type
import notes.core.api.SpaceTrait
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
    val accents = accents()
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
                IconChoice(icon, icon.key == selected, accents.getValue(icon.key), Modifier) { onSelect(icon.key) }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, style = Type.small, color = t.danger)
        }
    }
}

/**
 * Every icon to choose from, [selected] ringed, all shown at once: for a page that scrolls, where a grid
 * scrolling inside it would fight the page.
 */
@Composable
fun SpaceIconGrid(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val accents = accents()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxWidth / 58.dp).toInt().coerceAtLeast(1)
        val cell = maxWidth / columns
        Column(Modifier.selectableGroup()) {
            for (row in choices.chunked(columns)) {
                Row {
                    for (icon in row) {
                        IconChoice(icon, icon.key == selected, accents.getValue(icon.key), Modifier.width(cell)) {
                            onSelect(icon.key)
                        }
                    }
                }
            }
        }
    }
}

/** An icon comes with its colours: each choice shows its theme's accent. */
@Composable
private fun accents(): Map<String, Color> {
    val dark = LocalTokens.current.dark
    return remember(dark) { choices.associate { it.key to SpaceThemes.colors(it.key, dark).accent } }
}

@Composable
private fun IconChoice(icon: SpaceIcon, on: Boolean, accent: Color, modifier: Modifier, onSelect: () -> Unit) {
    val t = LocalTokens.current
    val cell = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(58.dp)
            .clip(cell)
            .then(if (on) Modifier.background(t.chip).border(2.dp, t.ink2, cell) else Modifier)
            .selectable(on, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = icon.name },
        contentAlignment = Alignment.Center
    ) {
        Picture(icon, 40.dp)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(t.bg)
                .padding(2.dp)
                .clip(CircleShape)
                .background(accent)
        )
    }
}

/**
 * A space's icon, name and trait glyphs (§7.8), together wherever a space is named; with no traits, the icon
 * and name alone. [nameArea] decorates the icon and name (a button, say), [afterName] ends it (a chevron), and
 * with [onTraits] the glyphs are grouped in a [TraitGroup]. A [subtitle] goes under the name, beside the icon.
 */
@Composable
fun SpaceIdentity(
    icon: String,
    name: String,
    traits: List<SpaceTrait>,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body.copy(fontSize = 16.sp),
    color: Color = LocalTokens.current.ink,
    iconSize: Dp = 24.dp,
    iconGap: Dp = 14.dp,
    glyphSize: Dp = 16.dp,
    nameArea: Modifier = Modifier,
    afterName: @Composable () -> Unit = {},
    onTraits: (() -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null
) {
    // With a subtitle the glyphs stay on the name's line, so they can't be a button apart from it.
    val inline = subtitle != null
    val grouped = !inline && onTraits != null && traits.isNotEmpty()
    // Grouped, the row is as tall as the name's area, so the trait button's fill matches its highlight.
    Row(
        if (grouped) modifier.height(IntrinsicSize.Min) else modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(nameArea.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            SpaceIconImage(icon, iconSize)
            Spacer(Modifier.width(iconGap))
            Column(Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = style,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    afterName()
                    if (inline && traits.isNotEmpty()) TraitGlyphs(traits, glyphSize)
                }
                subtitle?.invoke()
            }
        }
        if (grouped) {
            // Apart, so the name's highlight and the button's fill never touch.
            Spacer(Modifier.width(6.dp))
            TraitGroup(traits, onTraits, Modifier.fillMaxHeight(), glyphSize)
        } else if (!inline && traits.isNotEmpty()) {
            TraitGlyphs(traits, glyphSize)
        }
    }
}

/** Trait glyphs after a space's name, packed in the server's order. */
@Composable
fun TraitGlyphs(traits: List<SpaceTrait>, size: Dp, modifier: Modifier = Modifier) {
    val description = describeTraits(traits)
    Glyphs(traits, size, 4.dp, modifier.padding(start = 6.dp).semantics { contentDescription = description })
}

/**
 * Beside the switcher and the list's title: the space's trait glyphs in one filled button opening Space info,
 * as tall as [modifier] makes it. Nothing without traits.
 */
@Composable
fun TraitGroup(traits: List<SpaceTrait>, onClick: () -> Unit, modifier: Modifier = Modifier, glyphSize: Dp = 16.dp) {
    if (traits.isEmpty()) return
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val description = "Space info: ${describeTraits(traits)}"
    // No minimumInteractiveComponentSize: padding one glyph out to 48dp wide would widen the gap beside it. Hit
    // testing extends a smaller target to 48dp anyway.
    Glyphs(
        traits,
        glyphSize,
        9.dp,
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) t.on else t.hover)
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 9.dp)
    )
}

/** The glyphs alone, in the tertiary ink; one whose trait has a problem in the danger colour. */
@Composable
internal fun Glyphs(traits: List<SpaceTrait>, size: Dp, gap: Dp, modifier: Modifier) {
    val t = LocalTokens.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        for (trait in traits) {
            Icon(
                painterResource(SpaceTraits.glyph(trait.key)),
                null,
                Modifier.size(size),
                tint = if (trait.problem != null) t.danger else t.ink3
            )
        }
    }
}

/** A sheet listing [spaces] to pick one, [current] checked. */
@Composable
fun SpacePicker(title: String, spaces: List<Space>, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Sheet(onDismiss = onDismiss, title = title) {
        // Scrolls once there are more spaces than the sheet's height shows.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SpaceRows(spaces, current, onPick)
        }
    }
}

@Composable
internal fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = t.ink2)
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = Type.body.copy(fontSize = 16.sp), color = t.ink)
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
                .semantics(mergeDescendants = true) { selected = s.id == current }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpaceIdentity(s.icon, s.name, s.traits, Modifier.weight(1f))
            if (s.id == current) Icon(Icons.Check, null, Modifier.size(20.dp), tint = t.ink)
        }
    }
}

/**
 * Before a move that changes who sees the note (§7.8): who will see it in [to], one [exposures] sentence a line
 * beside its trait's glyph, and who no longer will, the [lost] sentences of the space it leaves.
 */
@Composable
fun MoveAcrossDialog(
    title: String,
    to: Space,
    exposures: List<SpaceTrait>,
    onDismiss: () -> Unit,
    lost: List<SpaceTrait> = emptyList(),
    onMove: () -> Unit
) {
    val t = LocalTokens.current
    NotesDialog(onDismiss, "Move “$title” to ${to.name}?") {
        if (exposures.isNotEmpty()) {
            Text("In ${to.name}, this note will be:", style = Type.body, color = t.ink2)
            Spacer(Modifier.height(12.dp))
            Exposures(exposures)
        }
        if (lost.isNotEmpty()) {
            if (exposures.isNotEmpty()) Spacer(Modifier.height(16.dp))
            Text("It will no longer be:", style = Type.body, color = t.ink2)
            Spacer(Modifier.height(12.dp))
            Exposures(lost)
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotesButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.Chip)
            NotesButton("Move", onMove, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Exposures(traits: List<SpaceTrait>) {
    val t = LocalTokens.current
    for (exposure in traits) {
        Row(
            Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(SpaceTraits.glyph(exposure.key)), null, Modifier.size(18.dp), tint = t.ink3)
            Spacer(Modifier.width(10.dp))
            Text(exposure.exposure.orEmpty(), style = Type.body, color = t.ink)
        }
    }
}
