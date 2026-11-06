package notes.app.editor

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import notes.app.ui.Icons
import notes.app.ui.InstrumentSerif
import notes.app.ui.LocalReduceMotion
import notes.app.ui.LocalTokens
import notes.app.ui.Sans
import notes.app.ui.Type
import notes.app.ui.floating
import notes.app.ui.pressScale
import notes.core.doc.ListKind

/**
 * The formatting bar on top of the keyboard. While writing it offers blocks
 * (indent only where it applies); with text selected, marks and link, so the
 * row stays short. Every button reflects the state at the selection and is
 * announced as on or off. Glyphs follow the web toolbar: a bold B, a serif
 * italic I, serif heading sizes.
 */
@Composable
fun FormattingBar(
    state: FormatState,
    editor: () -> NoteEditText?,
    onLink: () -> Unit,
    modifier: Modifier = Modifier,
    /** Add a picture (opens the photo picker); null hides the button. */
    onImage: (() -> Unit)? = null
) {
    val reduce = LocalReduceMotion.current
    Crossfade(
        targetState = state.selecting,
        modifier = modifier.widthIn(max = 600.dp).floating(),
        animationSpec = if (reduce) snap() else tween(150),
        label = "bar"
    ) { selecting ->
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (selecting) SelectionTools(state, editor, onLink) else WritingTools(state, editor, onImage)
        }
    }
}

@Composable
private fun WritingTools(state: FormatState, editor: () -> NoteEditText?, onImage: (() -> Unit)?) {
    val body = !state.inTitle
    for (level in 1..3) {
        val style = when (level) {
            1 -> Type.titleAt(21f).copy(lineHeight = TextUnit.Unspecified, lineBreak = LineBreak.Unspecified)
            2 -> Type.titleAt(18f).copy(lineHeight = TextUnit.Unspecified, lineBreak = LineBreak.Unspecified)
            else -> TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Glyph("Heading $level", "H$level", style, state.heading == level, body) { editor()?.toggleHeading(level) }
    }
    Separator()
    Toggle("Bulleted list", Icons.Bullets, state.list == ListKind.Bullet, body) {
        editor()?.toggleList(ListKind.Bullet)
    }
    Toggle("Numbered list", Icons.Numbers, state.list == ListKind.Ordered, body) {
        editor()?.toggleList(ListKind.Ordered)
    }
    Toggle("Checklist", Icons.Checklist, state.list == ListKind.Task, body) { editor()?.toggleList(ListKind.Task) }
    Toggle("Quote", Icons.Quote, state.quote, body) { editor()?.toggleQuote() }
    if (onImage != null) Action("Add picture", Icons.Image, enabled = true, onClick = onImage)
    if (state.canIndent || state.canOutdent) {
        Separator()
        Action("Outdent", Icons.Outdent, state.canOutdent) { editor()?.outdent() }
        Action("Indent", Icons.Indent, state.canIndent) { editor()?.indent() }
    }
}

@Composable
private fun SelectionTools(state: FormatState, editor: () -> NoteEditText?, onLink: () -> Unit) {
    val body = !state.inTitle
    Glyph(
        "Bold",
        "B",
        TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 17.sp),
        state.bold,
        body
    ) { editor()?.toggleBold() }
    Glyph(
        "Italic",
        "I",
        TextStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 21.sp),
        state.italic,
        body
    ) { editor()?.toggleItalic() }
    Glyph(
        "Strikethrough",
        "S",
        TextStyle(fontFamily = Sans, fontSize = 17.sp, textDecoration = TextDecoration.LineThrough),
        state.strike,
        body
    ) { editor()?.toggleStrike() }
    Separator()
    Toggle("Link", Icons.Link, state.link, body) { onLink() }
}

@Composable
private fun Glyph(
    description: String,
    label: String,
    style: TextStyle,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) = Tool(description, checked, enabled, onToggle) { color ->
    Text(label, style = style, color = color)
}

@Composable
private fun Toggle(description: String, icon: ImageVector, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) =
    Tool(description, checked, enabled, onToggle) { color -> Icon(icon, null, Modifier.size(20.dp), tint = color) }

/** A 48×48 toggle, filled while on (web: .tool[aria-pressed]). */
@Composable
private fun Tool(
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    content: @Composable (Color) -> Unit
) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (checked && enabled) t.on else Color.Transparent, label = "tool")
    Box(
        Modifier
            .size(48.dp)
            .pressScale(interaction, 0.88f)
            .clip(CircleShape)
            .background(bg)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interaction,
                indication = ripple(),
                onValueChange = { onToggle() }
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        content(if (enabled) t.ink else t.ink3.copy(alpha = 0.5f))
    }
}

@Composable
private fun Action(description: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .pressScale(interaction, 0.88f)
            .clip(CircleShape)
            .clickable(interaction, ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) t.ink else t.ink3.copy(alpha = 0.5f))
    }
}

@Composable
private fun Separator() {
    val t = LocalTokens.current
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(t.hair))
}
