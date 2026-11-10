package notes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Snackbars drawn as the web's toast: a dark rounded pill with an optional action chip. */
@Composable
fun ToastHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> Toast(data) }
}

@Composable
private fun Toast(data: SnackbarData) {
    val t = LocalTokens.current
    val action = data.visuals.actionLabel
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = 560.dp)
            .shadow(24.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(16.dp))
            .background(t.solid)
            .heightIn(min = 52.dp)
            .padding(start = 18.dp, end = if (action != null || data.visuals.withDismissAction) 6.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            data.visuals.message,
            style = Type.body.copy(fontSize = 14.5.sp),
            color = t.onSolid,
            modifier = Modifier.weight(1f, fill = false).padding(vertical = 14.dp)
        )
        Spacer(Modifier.width(12.dp))
        if (action != null) {
            Text(
                action,
                style = Type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = t.onSolid,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x38808080))
                    .clickable(role = Role.Button) { data.performAction() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        } else if (data.visuals.withDismissAction) {
            IconAction(Icons.Close, "Dismiss", {
                data.dismiss()
            }, tint = t.onSolid.copy(alpha = 0.7f), iconSize = 18.dp)
        }
    }
}

/** A bottom sheet on the glass colour with a short handle, for a row's or a link's actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, title: String? = null, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.glassHi.compositeOverBg(t),
        contentColor = t.ink,
        scrimColor = t.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier.padding(
                    top = 10.dp,
                    bottom = 6.dp
                ).size(36.dp, 4.dp).clip(CircleShape).background(t.hairStrong)
                    // The sheet makes its handle a control (dismiss, expand); only Material's own handle names it.
                    .semantics { contentDescription = "Drag handle" }
            )
        }
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = Type.small,
                    color = t.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            content()
        }
    }
}

@Composable
fun SheetItem(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val t = LocalTokens.current
    val color = if (danger) t.danger else t.ink
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (danger) t.danger else t.ink2)
        Text(label, style = Type.body.copy(fontSize = 16.sp), color = color)
    }
}

/** A confirmation in the app's own style: serif title, plain text, two full-width buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    action: String,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    onConfirm: () -> Unit
) {
    NotesDialog(onDismiss, title) {
        Text(text, style = Type.body, color = LocalTokens.current.ink2)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotesButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.Chip)
            NotesButton(
                action,
                {
                    onDismiss()
                    onConfirm()
                },
                Modifier.weight(1f),
                kind = if (danger) ButtonKind.Danger else ButtonKind.Solid
            )
        }
    }
}

/** The panel every dialog shares: rounded 28, the paper colour, a serif title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesDialog(onDismiss: () -> Unit, title: String, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .shadow(40.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(t.bg)
                .padding(24.dp)
                .semantics { paneTitle = title }
        ) {
            Heading(title, style = Type.title.copy(fontSize = 28.sp, lineHeight = 32.sp))
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
