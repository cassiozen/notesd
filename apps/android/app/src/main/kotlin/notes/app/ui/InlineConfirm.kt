package notes.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A destructive action's confirmation, opened under the button that asked for it rather than in a dialog over
 * the page: what it will do stays beside where it was asked. [title] in bold, then [text], then Cancel and
 * [action]. Focus goes to Cancel; Back or Escape closes only this. [busy] while [action] runs: nothing closes it.
 */
@Composable
fun InlineConfirm(
    visible: Boolean,
    title: String,
    text: String,
    action: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false
) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible,
        modifier,
        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(180)) + expandVertically(springPop()),
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(120)) + shrinkVertically(springSlide(reduce = false))
    ) {
        Strip(title, text, action, busy, onConfirm) { if (!busy) onCancel() }
    }
    // Outside the animation: once closing starts, Back belongs to the page again.
    BackHandler(enabled = visible) { if (!busy) onCancel() }
}

@Composable
private fun Strip(
    title: String,
    text: String,
    action: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val t = LocalTokens.current
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.dangerBg)
            .onKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                    onCancel()
                    true
                } else {
                    false
                }
            }
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = t.ink, fontWeight = FontWeight.SemiBold)) { append(title) }
                append(" ")
                append(text)
            },
            style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = t.ink2,
            // Focus can't always follow to Cancel (touch mode): the words are read out as they appear.
            modifier = Modifier.padding(end = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NotesButton(
                "Cancel",
                onCancel,
                Modifier.focusRequester(cancel),
                kind = ButtonKind.Chip,
                enabled = !busy,
                small = true
            )
            NotesButton(action, onConfirm, kind = ButtonKind.DangerSolid, busy = busy, small = true)
        }
    }
}
