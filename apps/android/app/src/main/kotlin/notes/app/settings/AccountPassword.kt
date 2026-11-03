package notes.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.ui.ButtonKind
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.Type
import notes.core.api.ApiResult

/** The server's own minimum, checked here first. */
private const val MIN_ACCOUNT_PASSWORD = 8

/** The account's password. The server signs out every other session; this phone stays signed in. */
@Composable
internal fun AccountPasswordDialog(graph: AppGraph, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }

    fun change() {
        errors = buildMap {
            if (current.isEmpty()) put("current", "Enter your current password.")
            if (next.length < MIN_ACCOUNT_PASSWORD) {
                put("next", "Use at least $MIN_ACCOUNT_PASSWORD characters.")
            } else if (confirm != next) {
                put("confirm", "The passwords don't match.")
            }
        }
        if (errors.isNotEmpty() || busy) return
        busy = true
        scope.launch {
            val r = graph.api()?.changePassword(current, next) ?: ApiResult.Unauthenticated
            busy = false
            errors = when {
                r is ApiResult.Ok -> return@launch onChanged()

                r is ApiResult.Rejected && r.error == "invalid_credentials" ->
                    mapOf("current" to "That isn't your current password.")

                r is ApiResult.Rejected && r.status == 400 && r.field == "password" ->
                    mapOf("next" to r.message.replaceFirstChar { it.uppercase() } + ".")

                // The server limits attempts, as at sign-in.
                r is ApiResult.RateLimited -> mapOf("form" to "Too many attempts. Wait a moment and try again.")

                r is ApiResult.Unreachable -> mapOf("form" to "Can't reach the server.")

                else -> mapOf("form" to "Something went wrong. Try again.")
            }
        }
    }

    NotesDialog({ if (!busy) onDismiss() }, "Change password") {
        ScrollingBody {
            NotesField(
                value = current,
                onValueChange = {
                    current = it
                    errors = errors - "current"
                },
                label = "Current password",
                error = errors["current"],
                secret = true,
                enabled = !busy
            )
            Spacer(Modifier.height(12.dp))
            NotesField(
                value = next,
                onValueChange = {
                    next = it
                    errors = errors - "next"
                },
                label = "New password",
                placeholder = "At least $MIN_ACCOUNT_PASSWORD characters",
                error = errors["next"],
                secret = true,
                enabled = !busy
            )
            Spacer(Modifier.height(12.dp))
            NotesField(
                value = confirm,
                onValueChange = {
                    confirm = it
                    errors = errors - "confirm"
                },
                label = "Confirm new password",
                error = errors["confirm"],
                hint = "Every other browser and device is signed out and needs the new password.",
                secret = true,
                enabled = !busy
            )
            errors["form"]?.let {
                Text(
                    it,
                    style = Type.small,
                    color = t.danger,
                    modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotesButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.Chip, enabled = !busy)
                NotesButton(if (busy) "Changing…" else "Change", ::change, Modifier.weight(1f), busy = busy)
            }
        }
    }
}
