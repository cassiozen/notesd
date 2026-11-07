package notes.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import notes.app.ui.ButtonKind
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.Type

/** Below a card: what it applies to. */
@Composable
internal fun Footnote(text: String) {
    Text(
        text,
        style = Type.small,
        color = LocalTokens.current.ink3,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
    )
}

/** A rounded group on the chip colour, like the web's settings sheet. */
@Composable
internal fun Card(content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.chip)
            .border(1.dp, t.edge, shape),
        content = content
    )
}

@Composable
internal fun Divider() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(LocalTokens.current.hair))
}

@Composable
internal fun Info(label: String, value: String) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(label, style = Type.rowTitle, color = t.ink)
        Text(value, style = Type.rowMeta, color = t.ink2)
    }
}

@Composable
internal fun Action(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        color = if (enabled) color else LocalTokens.current.ink3,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 17.dp)
    )
}

/** A dialog form's error, under its fields; read out when it appears. */
@Composable
internal fun FormError(text: String) {
    Text(
        text,
        style = Type.small,
        color = LocalTokens.current.danger,
        modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** A dialog's Cancel and its action, side by side under the form. */
@Composable
internal fun DialogActions(
    onCancel: () -> Unit,
    action: String,
    onAction: () -> Unit,
    busy: Boolean,
    danger: Boolean = false
) {
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NotesButton("Cancel", onCancel, Modifier.weight(1f), kind = ButtonKind.Chip, enabled = !busy)
        NotesButton(
            action,
            onAction,
            Modifier.weight(1f),
            kind = if (danger) ButtonKind.Danger else ButtonKind.Solid,
            busy = busy
        )
    }
}

/** A short state on the good colour, such as "On" or "You". */
@Composable
internal fun Pill(text: String) {
    val t = LocalTokens.current
    Text(
        text,
        style = Type.label.copy(fontSize = 12.sp),
        color = t.good,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(t.goodBg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
