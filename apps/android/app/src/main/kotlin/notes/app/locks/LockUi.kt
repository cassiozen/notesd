package notes.app.locks

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import notes.app.ui.ButtonKind
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.PasswordMeter
import notes.app.ui.Type
import notes.app.ui.corners
import notes.core.crypto.LockSession
import notes.core.crypto.UnsupportedKdfException
import notes.core.crypto.UnsupportedVersionException

/** For call sites without fingerprint unlock. */
private val Off = MutableStateFlow(false)

/** Shortest lock password accepted; it's the only thing between the server and the notes. */
const val MIN_LOCK_PASSWORD = 8

/** What a keyring change that needs the server says when it can't finish. */
fun LockSession.Outcome.message(): String? = when (this) {
    LockSession.Outcome.Done -> null
    LockSession.Outcome.WrongPassword -> "Wrong password."
    LockSession.Outcome.UnsupportedKdf -> UnsupportedKdfException.UNSUPPORTED_KDF_MESSAGE
    LockSession.Outcome.MalformedKey -> "The lock password on your server is damaged, so it can't unlock here."
    LockSession.Outcome.NotConnected -> "Connect to your server first: it keeps the lock password for every device."
    LockSession.Outcome.Offline -> "Can't reach the server. Try again when you're online."
    LockSession.Outcome.Failed -> "Something went wrong. Try again."
}

/** The lock password field and its button ([action], "Unlock" by default); the placeholder and the dialog share it. */
@Composable
fun UnlockFields(
    locks: LockSession,
    onUnlocked: () -> Unit,
    onCancel: (() -> Unit)? = null,
    action: String = "Unlock",
    busyAction: String = "Unlocking…",
    fingerprint: FingerprintUnlock? = null,
    /** Ask for the fingerprint as soon as this shows, as opening a locked note does. */
    promptFingerprint: Boolean = false
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val activity = LocalActivity.current as? FragmentActivity
    val fingerprintOn by (fingerprint?.enabled ?: Off).collectAsStateWithLifecycle()
    val available = remember(fingerprint) { fingerprint?.available() == true }
    val canFingerprint = activity != null && fingerprintOn && available

    fun useFingerprint() {
        if (fingerprint == null || activity == null || busy) return
        busy = true
        error = null
        scope.launch {
            val outcome = fingerprint.unlock(activity)
            busy = false
            when (outcome) {
                FingerprintUnlock.Outcome.Done -> onUnlocked()

                FingerprintUnlock.Outcome.Stale ->
                    error =
                        "Fingerprint unlock is off: the lock password or your fingerprints changed. Use the password."

                FingerprintUnlock.Outcome.Failed -> error = "Fingerprint unlock didn't work. Use the password."

                else -> Unit
            }
        }
    }

    LaunchedEffect(Unit) { if (promptFingerprint && canFingerprint) useFingerprint() }

    fun submit() {
        if (password.isEmpty() || busy) return
        busy = true
        scope.launch {
            val outcome = locks.unlock(password)
            busy = false
            if (outcome == LockSession.Outcome.Done) {
                if (activity != null) fingerprint?.offer(activity)
                onUnlocked()
            } else {
                error = outcome.message()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NotesField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            label = "Lock password",
            error = error,
            secret = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() })
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onCancel != null) {
                NotesButton("Cancel", onCancel, Modifier.weight(1f), kind = ButtonKind.Chip, enabled = !busy)
            }
            NotesButton(if (busy) busyAction else action, ::submit, Modifier.weight(1f), busy = busy)
        }
        if (canFingerprint) {
            NotesButton(
                "Use fingerprint",
                ::useFingerprint,
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Chip,
                enabled = !busy
            )
        }
    }
}

/** A locked note while locked notes are locked: its title, and the way in. */
@Composable
fun LockedNote(
    title: String,
    unreadable: Boolean,
    locks: LockSession,
    modifier: Modifier = Modifier,
    fingerprint: FingerprintUnlock? = null,
    /** Ask for the fingerprint at once: the note was just opened, not just locked. */
    promptFingerprint: Boolean = true,
    /** Locked by a newer version of notesd: no unlocking here at all. */
    unsupported: Boolean = false
) {
    val t = LocalTokens.current
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.size(56.dp).clip(corners(18.dp)).background(t.chip),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Lock, null, Modifier.size(28.dp), tint = t.ink2)
            }
            Text(title.ifBlank { "Locked note" }, style = Type.title, color = t.ink, textAlign = TextAlign.Center)
            if (unsupported) {
                Text(
                    UnsupportedVersionException.UNSUPPORTED_VERSION_MESSAGE,
                    style = Type.small,
                    color = t.ink3,
                    textAlign = TextAlign.Center
                )
            } else if (unreadable) {
                Text(
                    "This note was locked with a password that was reset. It can't be opened.",
                    style = Type.small,
                    color = t.ink3,
                    textAlign = TextAlign.Center
                )
            } else {
                Text("This note is locked.", style = Type.small, color = t.ink3)
                Spacer(Modifier.height(6.dp))
                UnlockFields(locks, onUnlocked = {}, fingerprint = fingerprint, promptFingerprint = promptFingerprint)
            }
        }
    }
}

/**
 * Lock note, when a lock password already exists (made on any device) but
 * isn't entered yet: every locked note shares it, so it's asked for, not
 * created.
 */
@Composable
fun LockNoteDialog(
    locks: LockSession,
    onDismiss: () -> Unit,
    fingerprint: FingerprintUnlock? = null,
    onUnlocked: () -> Unit
) {
    val t = LocalTokens.current
    NotesDialog(onDismiss, "Lock this note") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Locked notes share one lock password, made the first time a note was locked on any of your " +
                    "devices. Enter it to lock this note.",
                style = Type.small,
                color = t.ink2
            )
            UnlockFields(
                locks,
                onUnlocked,
                onCancel = onDismiss,
                action = "Lock",
                busyAction = "Locking…",
                fingerprint = fingerprint
            )
        }
    }
}

/** The lock password, asked for before something that needs locked notes unlocked (turning on fingerprint unlock). */
@Composable
fun UnlockDialog(locks: LockSession, title: String, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    NotesDialog(onDismiss, title) {
        UnlockFields(locks, onUnlocked, onCancel = onDismiss)
    }
}

/** First lock: choose the password every locked note will share; fingerprint unlock is offered right after. */
@Composable
fun LockSetupDialog(
    locks: LockSession,
    onDismiss: () -> Unit,
    fingerprint: FingerprintUnlock? = null,
    onDone: () -> Unit
) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current as? FragmentActivity
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        errors = buildMap {
            if (password.length < MIN_LOCK_PASSWORD) {
                put("password", "Use at least $MIN_LOCK_PASSWORD characters.")
            } else if (confirm != password) {
                put("confirm", "The passwords don't match.")
            }
        }
        message = null
        if (errors.isNotEmpty() || busy) return
        busy = true
        scope.launch {
            val outcome = locks.setup(password)
            busy = false
            if (outcome == LockSession.Outcome.Done) {
                if (activity != null) fingerprint?.offer(activity)
                onDone()
            } else {
                message = outcome.message()
            }
        }
    }

    NotesDialog(onDismiss, "Create a lock password") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Locked notes are encrypted on your devices; the server can't read them. Every locked note uses " +
                    "this password. If you forget it, those notes can't be recovered.",
                style = Type.small,
                color = t.ink2
            )
            NotesField(
                value = password,
                onValueChange = {
                    password = it
                    errors = errors - "password"
                },
                label = "Lock password",
                error = errors["password"],
                secret = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)
            )
            PasswordMeter(password)
            NotesField(
                value = confirm,
                onValueChange = {
                    confirm = it
                    errors = errors - "confirm"
                },
                label = "Confirm",
                error = errors["confirm"],
                secret = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() })
            )
            message?.let {
                Text(
                    it,
                    style = Type.small,
                    color = t.danger,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotesButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.Chip, enabled = !busy)
                NotesButton(if (busy) "Creating…" else "Create and lock", ::submit, Modifier.weight(1f), busy = busy)
            }
        }
    }
}
