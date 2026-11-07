package notes.app.notes

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import notes.app.editor.FindState
import notes.app.ui.ButtonKind
import notes.app.ui.IconAction
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.Reveal
import notes.app.ui.Type
import notes.app.ui.pressScale

enum class FindMode { Find, Replace }

/**
 * Find, and replace, in the open note: takes the place of the editor's top
 * bar. Searches as you type; the keyboard's search key (Enter) goes to the
 * next match, Shift+Enter the previous, Escape closes.
 */
@Composable
fun FindBar(
    mode: FindMode,
    query: TextFieldValue,
    onQuery: (TextFieldValue) -> Unit,
    replacement: TextFieldValue,
    onReplacement: (TextFieldValue) -> Unit,
    state: FindState,
    /** False hides replacing (the toggle and its row). */
    canReplace: Boolean,
    onMode: (FindMode) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    /** Changes each time find is asked for, so asking again while open refocuses the query. */
    opened: Int = 0
) {
    val findFocus = remember { FocusRequester() }
    val replaceFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val replacing = canReplace && mode == FindMode.Replace
    val found = state.count > 0
    LaunchedEffect(opened) {
        findFocus.requestFocus()
        keyboard?.show()
    }
    // After the toggle's recomposition: the replace field isn't there to focus until then.
    var focusReplace by remember { mutableStateOf(false) }
    LaunchedEffect(focusReplace) {
        if (focusReplace) {
            replaceFocus.requestFocus()
            focusReplace = false
        }
    }
    // Hardware keys; Enter is the field's own action, with Shift the previous match.
    fun keys(onEnter: () -> Unit) = Modifier.onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (it.key) {
            Key.Escape -> onClose()
            Key.Enter, Key.NumPadEnter -> if (it.isShiftPressed) onPrevious() else onEnter()
            else -> return@onPreviewKeyEvent false
        }
        true
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconAction(Icons.Close, "Close find", onClose)
            FindField(
                query,
                onQuery,
                placeholder = "Find in note",
                description = "Find in note",
                imeAction = ImeAction.Search,
                onImeAction = onNext,
                modifier = Modifier.weight(1f).focusRequester(findFocus).then(keys(onNext)),
                trailing = { Counter(query.text, state) }
            )
            Spacer(Modifier.width(4.dp))
            IconAction(Icons.ChevronUp, "Previous match", onPrevious, enabled = found)
            IconAction(Icons.ChevronDown, "Next match", onNext, enabled = found)
            if (canReplace) {
                ReplaceToggle(replacing) { on ->
                    onMode(if (on) FindMode.Replace else FindMode.Find)
                    // The field with focus is going (off), or there's something to replace (on).
                    if (!on) {
                        findFocus.requestFocus()
                    } else if (query.text.isNotEmpty()) {
                        focusReplace = true
                    }
                }
            }
        }
        Reveal(replacing) {
            val compact = LocalDensity.current.fontScale > 1.3f
            Row(
                Modifier.fillMaxWidth().padding(start = 52.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FindField(
                    replacement,
                    onReplacement,
                    placeholder = "Replace with",
                    description = "Replace with",
                    imeAction = ImeAction.Go,
                    onImeAction = onReplace,
                    modifier = Modifier.weight(1f).focusRequester(replaceFocus).then(keys(onReplace))
                )
                Spacer(Modifier.width(2.dp))
                NotesButton("Replace", onReplace, kind = ButtonKind.Chip, enabled = found, small = true)
                // At very large text both labels would leave the field no room.
                NotesButton(
                    if (compact) "All" else "Replace all",
                    onReplaceAll,
                    if (compact) Modifier.semantics { contentDescription = "Replace all" } else Modifier,
                    kind = ButtonKind.Chip,
                    enabled = found,
                    small = true
                )
            }
        }
    }
}

/** "3/12", "No results", or what Replace all did; read out as it changes. */
@Composable
private fun Counter(query: String, state: FindState) {
    val t = LocalTokens.current
    if (query.isEmpty()) return
    val (shown, spoken) = when {
        state.replaced != null -> "Replaced ${state.replaced}".let { it to it }
        state.count == 0 -> "No results".let { it to it }
        else -> "${state.index + 1}/${state.count}" to "Match ${state.index + 1} of ${state.count}"
    }
    Text(
        shown,
        style = Type.small,
        color = t.ink3,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 8.dp)
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            }
    )
}

/** A single-line field in the list search's style, with a neutral ring while it has focus. */
@Composable
private fun FindField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    description: String,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}
) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(if (focused) t.ink3 else t.hair, label = "border")
    val shape = RoundedCornerShape(14.dp)
    val style = Type.body.copy(fontSize = 16.sp, color = t.ink)
    Row(
        Modifier
            .then(modifier.height(44.dp))
            .clip(shape)
            .background(t.field)
            .border(1.dp, border, shape)
            .padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (value.text.isEmpty()) {
                Text(placeholder, style = style, color = t.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                // One line: a line break would match across blocks.
                onValueChange = { onValueChange(it.copy(text = it.text.replace('\n', ' '))) },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(t.tint),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions { onImeAction() },
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }
            )
        }
        trailing()
    }
}

/** Shows or hides the replace row; filled while it shows, like the formatting bar's toggles. */
@Composable
private fun ReplaceToggle(on: Boolean, onToggle: (Boolean) -> Unit) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (on) t.on else Color.Transparent, label = "replace")
    Box(
        Modifier
            .size(48.dp)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .background(bg)
            .toggleable(on, interaction, ripple(), role = Role.Switch, onValueChange = onToggle)
            .semantics { contentDescription = "Replace" },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Replace, null, Modifier.size(22.dp), tint = t.ink2)
    }
}
