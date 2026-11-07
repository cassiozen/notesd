package notes.app.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import notes.app.locks.FingerprintUnlock
import notes.app.locks.MIN_LOCK_PASSWORD
import notes.app.locks.UnlockDialog
import notes.app.locks.message
import notes.app.ui.ButtonKind
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.Type
import notes.core.crypto.LockSession

/**
 * Locked notes (§10.1): lock now, change the lock password, fingerprint unlock, or reset after forgetting it. A line
 * each; the forms open in dialogs.
 */
@Composable
internal fun LockedNotesCard(locks: LockSession, state: LockSession.State, fingerprint: FingerprintUnlock) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current as? FragmentActivity
    val fingerprintOn by fingerprint.enabled.collectAsStateWithLifecycle()
    val canFingerprint = remember { fingerprint.available() } && activity != null
    var askingPassword by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var turningOn by remember { mutableStateOf(false) }

    if (state == LockSession.State.None) {
        Card { Info("No lock password yet", "Choose Lock note from a note's menu to create one.") }
        return
    }

    fun turnOnFingerprint() {
        if (activity == null || turningOn) return
        // The session itself, not [state]: right after the password dialog, that lags a frame.
        if (locks.state.value != LockSession.State.Unlocked) {
            askingPassword = true
            return
        }
        turningOn = true
        message = null
        scope.launch {
            val outcome = fingerprint.enable(activity)
            turningOn = false
            message = when (outcome) {
                FingerprintUnlock.Outcome.Done -> "Fingerprint unlock is on for this phone."
                FingerprintUnlock.Outcome.Cancelled -> null
                else -> "Couldn't turn on fingerprint unlock. Try again."
            }
        }
    }

    Card {
        Row(
            Modifier.fillMaxWidth().heightIn(
                min = 64.dp
            ).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Lock password", style = Type.rowTitle, color = t.ink)
                Text(
                    if (state == LockSession.State.Unlocked) "Unlocked on this phone" else "Locked",
                    style = Type.rowMeta,
                    color = t.ink2
                )
            }
            if (state == LockSession.State.Unlocked) {
                NotesButton("Lock now", {
                    message = null
                    scope.launch {
                        if (!locks.lock()) {
                            message = "An open note couldn't be saved, so locked notes are still unlocked. Try again."
                        }
                    }
                }, kind = ButtonKind.Chip, small = true)
                Spacer(Modifier.width(6.dp))
            }
            NotesButton(
                "Change…",
                {
                    message = null
                    changing = true
                },
                Modifier.semantics { contentDescription = "Change lock password" },
                kind = ButtonKind.Chip,
                small = true
            )
        }
        if (canFingerprint) {
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(
                        value = fingerprintOn || turningOn,
                        enabled = !turningOn,
                        role = Role.Switch
                    ) { on ->
                        message = null
                        if (on) turnOnFingerprint() else fingerprint.turnOff()
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Unlock with fingerprint", style = Type.rowTitle, color = t.ink, modifier = Modifier.weight(1f))
                Switch(
                    checked = fingerprintOn || turningOn,
                    // The row toggles it, so the whole line is the target.
                    onCheckedChange = null,
                    enabled = !turningOn,
                    colors = SwitchDefaults.colors(checkedTrackColor = t.accentDeep, checkedThumbColor = t.bg)
                )
            }
        }
        Divider()
        Action("Forgot the lock password?", t.danger, enabled = true) {
            message = null
            resetting = true
        }
    }
    message?.let {
        Text(
            it,
            style = Type.small,
            color = t.ink2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).semantics {
                liveRegion = LiveRegionMode.Polite
            }
        )
    }

    if (changing) {
        ChangeLockPasswordDialog(locks, fingerprint, onDismiss = { changing = false }) {
            changing = false
            message = "Lock password changed. Other devices use the new one once they sync."
        }
    }
    if (resetting) {
        ResetLocksDialog(locks, fingerprint, onDismiss = { resetting = false }) { resetting = false }
    }
    if (askingPassword) {
        // Fingerprint unlock is turned on right after, so the dialog doesn't offer it too.
        UnlockDialog(locks, "Unlock locked notes", onDismiss = { askingPassword = false }) {
            askingPassword = false
            turnOnFingerprint()
        }
    }
}

@Composable
private fun ChangeLockPasswordDialog(
    locks: LockSession,
    fingerprint: FingerprintUnlock,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }

    fun change() {
        errors = buildMap {
            if (current.isEmpty()) put("current", "Enter your current lock password.")
            if (next.length < MIN_LOCK_PASSWORD) {
                put("next", "Use at least $MIN_LOCK_PASSWORD characters.")
            } else if (confirm != next) {
                put("confirm", "The passwords don't match.")
            }
        }
        if (errors.isNotEmpty() || busy) return
        busy = true
        scope.launch {
            val rewrapped = locks.passwordEntry()
            val outcome = locks.changePassword(current, next)
            busy = false
            when (outcome) {
                LockSession.Outcome.Done -> {
                    // Same key, new entry: fingerprint unlock follows it.
                    fingerprint.passwordChanged(rewrapped)
                    onChanged()
                }

                LockSession.Outcome.WrongPassword -> errors = mapOf("current" to "That isn't your lock password.")

                else -> errors = mapOf("form" to (outcome.message() ?: "Something went wrong. Try again."))
            }
        }
    }

    NotesDialog({ if (!busy) onDismiss() }, "Change lock password") {
        ScrollingBody {
            Text(
                "Locked notes stay as they are; only the password that opens them changes.",
                style = Type.body,
                color = LocalTokens.current.ink2
            )
            Spacer(Modifier.height(16.dp))
            PasswordFields(
                listOf(
                    Triple("current", "Current lock password", current),
                    Triple("next", "New lock password", next),
                    Triple("confirm", "Confirm new lock password", confirm)
                ),
                errors,
                enabled = !busy,
                placeholder = mapOf("next" to "At least $MIN_LOCK_PASSWORD characters")
            ) { key, value ->
                when (key) {
                    "current" -> current = value
                    "next" -> next = value
                    else -> confirm = value
                }
                errors = errors - key - "form"
            }
            errors["form"]?.let { FormError(it) }
            DialogActions(onDismiss, if (busy) "Changing…" else "Change", ::change, busy)
        }
    }
}

@Composable
private fun ResetLocksDialog(
    locks: LockSession,
    fingerprint: FingerprintUnlock,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }

    fun reset() {
        if (account.isEmpty()) {
            errors = mapOf("account" to "Enter your account password.")
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            val outcome = locks.reset(account)
            busy = false
            when (outcome) {
                LockSession.Outcome.Done -> {
                    // The kept key went with the keyring.
                    fingerprint.disable()
                    onReset()
                }

                LockSession.Outcome.WrongPassword -> errors = mapOf("account" to "That isn't your account password.")

                else -> errors = mapOf("form" to (outcome.message() ?: "Something went wrong. Try again."))
            }
        }
    }

    NotesDialog({ if (!busy) onDismiss() }, "Reset locked notes?") {
        ScrollingBody {
            Text(
                "Resetting forgets the lock password. Notes locked with it stay encrypted and can never be opened " +
                    "again, on any device. You can then create a new lock password.",
                style = Type.body,
                color = LocalTokens.current.ink2
            )
            Spacer(Modifier.height(16.dp))
            PasswordFields(listOf(Triple("account", "Account password", account)), errors, enabled = !busy) { _, v ->
                account = v
                errors = emptyMap()
            }
            errors["form"]?.let { FormError(it) }
            DialogActions(onDismiss, "Reset", ::reset, busy, danger = true)
        }
    }
}

/** Secret fields, one per (key, label, value), each with its error under it. */
@Composable
private fun PasswordFields(
    fields: List<Triple<String, String, String>>,
    errors: Map<String, String>,
    enabled: Boolean,
    placeholder: Map<String, String> = emptyMap(),
    onChange: (key: String, value: String) -> Unit
) {
    for ((i, field) in fields.withIndex()) {
        val (key, label, value) = field
        if (i > 0) Spacer(Modifier.height(12.dp))
        NotesField(
            value = value,
            onValueChange = { onChange(key, it) },
            label = label,
            placeholder = placeholder[key],
            error = errors[key],
            secret = true,
            enabled = enabled
        )
    }
}
