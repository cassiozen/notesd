package notes.app.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.BuildConfig
import notes.app.account.Account
import notes.app.locks.FingerprintUnlock
import notes.app.locks.MIN_LOCK_PASSWORD
import notes.app.locks.UnlockDialog
import notes.app.locks.message
import notes.app.notes.SpaceIconImage
import notes.app.notes.SpaceIconPicker
import notes.app.ui.BackLink
import notes.app.ui.BrandMark
import notes.app.ui.ButtonKind
import notes.app.ui.ConfirmDialog
import notes.app.ui.Icons
import notes.app.ui.Kicker
import notes.app.ui.LocalReduceMotion
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.Type
import notes.app.ui.pressScale
import notes.core.api.ApiResult
import notes.core.crypto.LockSession
import notes.core.db.Space
import notes.core.db.Spaces
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private sealed interface Confirm {
    /** [pending]: notes with changes the server hasn't received. */
    data class SignOut(val pending: Int) : Confirm

    data object RemoveAll : Confirm
}

@Composable
fun SettingsScreen(
    graph: AppGraph,
    backLabel: String?,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onSignIn: () -> Unit,
    /** A new space: the app switches to it (§7.7). */
    onSpaceCreated: (String) -> Unit = {}
) {
    val t = LocalTokens.current
    val theme by graph.appearance.theme.collectAsStateWithLifecycle()
    val account by graph.accounts.account.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun act(block: suspend () -> Unit) {
        busy = true
        // The app scope: leaving the screen mustn't cancel a sign-out halfway.
        graph.scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackLink(backLabel, onBack)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Text(
                    "Settings",
                    style = Type.display,
                    color = t.ink,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp).semantics { heading() }
                )

                Section("Server")
                Card {
                    when (val a = account) {
                        Account.None -> {
                            Info("Not connected", "Notes are kept on this phone only.")
                            Divider()
                            Action("Connect to a server", t.link, enabled = true, onClick = onConnect)
                        }

                        is Account.SignedIn -> {
                            Who(a.username, a.server, null)
                            Divider()
                            Action(if (busy) "Signing out…" else "Sign out", t.link, enabled = !busy) {
                                scope.launch { confirm = Confirm.SignOut(graph.store.pendingNotes()) }
                            }
                        }

                        is Account.SignedOut -> {
                            Who(a.username, a.server, "Signed out")
                            Divider()
                            Action("Sign in again", t.link, enabled = true, onClick = onSignIn)
                        }
                    }
                }

                if (account is Account.SignedIn) {
                    Section("Email")
                    EmailCard(graph)
                }

                Section("Spaces")
                SpacesCard(graph, onSpaceCreated)

                val lockState by graph.locks.state.collectAsStateWithLifecycle()
                if (account is Account.SignedIn || lockState != LockSession.State.None) {
                    Section("Locked notes")
                    LockedNotesCard(graph.locks, lockState, graph.fingerprint)
                }

                Section("Appearance")
                Segmented(Theme.entries, theme, { it.label }) { graph.appearance.set(it) }

                Section("Data")
                Card {
                    Action("Remove all data from this device", t.danger, enabled = !busy) {
                        confirm = Confirm.RemoveAll
                    }
                }
                Text(
                    "Deletes every note on this phone and signs you out. Notes on the server aren't touched.",
                    style = Type.small,
                    color = t.ink3,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )

                Section("About")
                Card {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BrandMark(24.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("notesd", style = Type.brand, color = t.ink, modifier = Modifier.weight(1f))
                        Text(
                            "Version ${BuildConfig.VERSION_NAME}",
                            style = Type.mono.copy(fontSize = 12.sp),
                            color = t.ink3
                        )
                    }
                }
            }
        }
    }

    when (val c = confirm) {
        is Confirm.SignOut -> ConfirmDialog(
            title = "Sign out?",
            text = if (c.pending == 0) {
                "Your notes stay on this phone."
            } else {
                "${c.pending} ${if (c.pending == 1) "note has" else "notes have"} changes the server hasn't " +
                    "received yet. They stay on this phone and are sent when you sign in again."
            },
            action = "Sign out",
            onDismiss = { confirm = null }
        ) { act(graph::signOut) }

        Confirm.RemoveAll -> ConfirmDialog(
            title = "Remove all data?",
            text = "Every note on this phone is deleted and you're signed out. Notes the server doesn't have " +
                "can't be recovered.",
            action = "Remove",
            danger = true,
            onDismiss = { confirm = null }
        ) { act(graph::removeAllData) }

        null -> Unit
    }
}

/** The server's Resend key and sender, for emailing notes from the share sheet. */
@Composable
private fun EmailCard(graph: AppGraph) {
    val t = LocalTokens.current
    val saved by graph.email.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var from by remember { mutableStateOf<String?>(null) }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fromValue = from ?: saved?.from.orEmpty()
    val configured = saved?.configured == true

    LaunchedEffect(Unit) { graph.email.refresh() }

    fun failed(r: ApiResult<*>) {
        val field = (r as? ApiResult.Rejected)?.takeIf { it.status == 400 }?.field
        if (r is ApiResult.Rejected && (field == "apiKey" || field == "from")) {
            val text = r.message.replaceFirstChar { it.uppercase() }
            errors = mapOf(field to if (text.endsWith('.')) text else "$text.")
        } else {
            message = if (r is ApiResult.Unreachable) "Can't reach the server." else "Something went wrong. Try again."
        }
    }

    fun save() {
        val found = buildMap {
            if (!configured && apiKey.isBlank()) put("apiKey", "Paste your Resend API key.")
            if (fromValue.isBlank()) put("from", "Enter the address emails come from.")
        }
        errors = found
        message = null
        if (found.isNotEmpty()) return
        busy = true
        scope.launch {
            val r = graph.email.save(fromValue.trim(), apiKey.trim().ifEmpty { null })
            busy = false
            if (r is ApiResult.Ok) {
                apiKey = ""
                from = null
                message = "Saved. Share a note and choose Email."
            } else {
                failed(r)
            }
        }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Email notes through Resend. Use an API key with sending access and an address on a verified domain.",
                style = Type.small,
                color = t.ink2
            )
            NotesField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    errors = errors - "apiKey"
                },
                label = "Resend API key",
                placeholder = saved?.takeIf { it.configured }?.let { "Saved · …${it.keyHint}" } ?: "re_…",
                error = errors["apiKey"],
                secret = true,
                mono = true,
                enabled = !busy
            )
            NotesField(
                value = fromValue,
                onValueChange = {
                    from = it
                    errors = errors - "from"
                },
                label = "From",
                placeholder = "Your Name <notes@example.com>",
                error = errors["from"],
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            message?.let {
                Text(
                    it,
                    style = Type.small,
                    color = t.ink2,
                    modifier = Modifier.semantics {
                        liveRegion =
                            LiveRegionMode.Polite
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NotesButton(if (busy) "Saving…" else "Save", ::save, busy = busy, small = true)
                if (configured) {
                    NotesButton("Remove", {
                        busy = true
                        message = null
                        scope.launch {
                            val r = graph.email.remove()
                            busy = false
                            if (r is ApiResult.Ok) {
                                apiKey = ""
                                from = null
                                errors = emptyMap()
                            } else {
                                failed(r)
                            }
                        }
                    }, kind = ButtonKind.DangerText, enabled = !busy, small = true)
                }
            }
        }
    }
}

/** Locked notes (§10.1): change the lock password, lock now, or reset after forgetting it. */
@Composable
private fun LockedNotesCard(locks: LockSession, state: LockSession.State, fingerprint: FingerprintUnlock) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current as? FragmentActivity
    val fingerprintOn by fingerprint.enabled.collectAsStateWithLifecycle()
    val canFingerprint = remember { fingerprint.available() } && activity != null
    var askingPassword by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf("") }

    if (state == LockSession.State.None) {
        Card { Info("No lock password yet", "Choose Lock note from a note's menu to create one.") }
        return
    }

    fun turnOnFingerprint() {
        if (activity == null || busy) return
        // The session itself, not [state]: right after the password dialog, that lags a frame.
        if (locks.state.value != LockSession.State.Unlocked) {
            askingPassword = true
            return
        }
        busy = true
        message = null
        scope.launch {
            val outcome = fingerprint.enable(activity)
            busy = false
            message = when (outcome) {
                FingerprintUnlock.Outcome.Done -> "Fingerprint unlock is on for this phone."
                FingerprintUnlock.Outcome.Cancelled -> null
                else -> "Couldn't turn on fingerprint unlock. Try again."
            }
        }
    }

    fun change() {
        errors = buildMap {
            if (current.isEmpty()) put("current", "Enter your current lock password.")
            if (next.length < MIN_LOCK_PASSWORD) {
                put("next", "Use at least $MIN_LOCK_PASSWORD characters.")
            } else if (confirm != next) {
                put("confirm", "The passwords don't match.")
            }
        }
        message = null
        if (errors.isNotEmpty() || busy) return
        busy = true
        scope.launch {
            val rewrapped = locks.passwordEntry()
            val outcome = locks.changePassword(current, next)
            busy = false
            when (outcome) {
                LockSession.Outcome.Done -> {
                    fingerprint.passwordChanged(rewrapped)
                    current = ""
                    next = ""
                    confirm = ""
                    message = "Lock password changed. Other devices use the new one once they sync."
                }

                LockSession.Outcome.WrongPassword -> errors = mapOf("current" to "That isn't your lock password.")

                else -> message = outcome.message()
            }
        }
    }

    fun reset() {
        if (busy) return
        busy = true
        scope.launch {
            val outcome = locks.reset(account)
            busy = false
            if (outcome == LockSession.Outcome.Done) fingerprint.disable()
            if (outcome == LockSession.Outcome.WrongPassword) {
                errors = mapOf("account" to "That isn't your account password.")
            } else if (outcome != LockSession.Outcome.Done) {
                message = outcome.message()
            }
        }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (resetting) {
                Text(
                    "Resetting forgets the lock password. Notes locked with it stay encrypted and can never be " +
                        "opened again, on any device. You can then create a new lock password.",
                    style = Type.small,
                    color = t.ink2
                )
                NotesField(
                    value = account,
                    onValueChange = {
                        account = it
                        errors = emptyMap()
                    },
                    label = "Account password",
                    error = errors["account"],
                    secret = true,
                    enabled = !busy
                )
            } else {
                NotesField(
                    value = current,
                    onValueChange = {
                        current = it
                        errors = errors - "current"
                    },
                    label = "Current lock password",
                    error = errors["current"],
                    secret = true,
                    enabled = !busy
                )
                NotesField(
                    value = next,
                    onValueChange = {
                        next = it
                        errors = errors - "next"
                    },
                    label = "New lock password",
                    placeholder = "At least $MIN_LOCK_PASSWORD characters",
                    error = errors["next"],
                    secret = true,
                    enabled = !busy
                )
                NotesField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        errors = errors - "confirm"
                    },
                    label = "Confirm new lock password",
                    error = errors["confirm"],
                    secret = true,
                    enabled = !busy
                )
            }
            message?.let {
                Text(
                    it,
                    style = Type.small,
                    color = t.ink2,
                    modifier = Modifier.semantics {
                        liveRegion =
                            LiveRegionMode.Polite
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (resetting) {
                    NotesButton("Cancel", { resetting = false }, kind = ButtonKind.Chip, enabled = !busy, small = true)
                    NotesButton("Reset locked notes", ::reset, kind = ButtonKind.Danger, busy = busy, small = true)
                } else {
                    NotesButton(if (busy) "Changing…" else "Change", ::change, busy = busy, small = true)
                }
            }
        }
        if (!resetting) {
            if (state == LockSession.State.Unlocked) {
                Divider()
                Action("Lock now", t.link, enabled = !busy) {
                    message = null
                    scope.launch {
                        if (!locks.lock()) {
                            message =
                                "An open note couldn't be saved, so locked notes are still unlocked. Try again."
                        }
                    }
                }
            }
            if (canFingerprint) {
                Divider()
                if (fingerprintOn) {
                    Action("Turn off fingerprint unlock", t.link, enabled = !busy) { fingerprint.turnOff() }
                } else {
                    Action("Unlock with fingerprint", t.link, enabled = !busy, onClick = ::turnOnFingerprint)
                }
            }
            Divider()
            Action("Forgot it? Reset locked notes…", t.danger, enabled = !busy) {
                message = null
                resetting = true
            }
        }
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
private fun Section(label: String) {
    Kicker(label, Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp).semantics { heading() })
}

/** A rounded group on the chip colour, like the web's settings sheet. */
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
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
private fun Divider() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(LocalTokens.current.hair))
}

/** The server's address, then the account on it; [note] flags a problem, such as being signed out. */
@Composable
private fun Who(username: String, server: String, note: String?) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                server.toHttpUrlOrNull()?.let { it.host + if (it.port != 80 && it.port != 443) ":${it.port}" else "" }
                    ?: server,
                style = Type.mono.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (note != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    note,
                    style = Type.label.copy(fontSize = 12.sp),
                    color = t.warnInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(t.warnBg)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            if (note ==
                null
            ) {
                "Signed in as $username"
            } else {
                "Account: $username"
            },
            style = Type.rowMeta,
            color = t.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Info(label: String, value: String) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(label, style = Type.rowTitle, color = t.ink)
        Text(value, style = Type.rowMeta, color = t.ink2)
    }
}

@Composable
private fun Action(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
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

/** A segmented control whose highlight slides to the chosen option. */
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val t = LocalTokens.current
    val reduce = LocalReduceMotion.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(t.hover)
    ) {
        val segment = maxWidth / options.size
        val x by animateDpAsState(
            segment * options.indexOf(selected),
            if (reduce) spring(stiffness = 100_000f) else spring(dampingRatio = 0.8f, stiffness = 600f),
            label = "segment"
        )
        Box(
            Modifier
                .offset { IntOffset(x.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(t.bg)
                .border(1.dp, t.edge, RoundedCornerShape(11.dp))
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            for (option in options) {
                val on = option == selected
                val color by animateColorAsState(if (on) t.ink else t.ink2, label = "segColor")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(on, role = Role.RadioButton, interactionSource = null, indication = null) {
                            onSelect(option)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label(option),
                        style = Type.label.copy(
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = color
                    )
                }
            }
        }
    }
}

private fun notesIn(space: Space) = "${space.noteCount} ${if (space.noteCount == 1) "note" else "notes"}" +
    when {
        space.id == Spaces.DEFAULT -> " · Can be renamed, not deleted"
        space.noteCount > 0 -> " · Move or delete its notes to delete it"
        else -> ""
    }

/** Spaces (§7.7): create, rename and delete them. The sidebar's switcher can make one too. */
@Composable
private fun SpacesCard(graph: AppGraph, onCreated: (String) -> Unit) {
    val t = LocalTokens.current
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Space?>(null) }
    var creating by remember { mutableStateOf(false) }

    Card {
        for ((i, s) in spaces.withIndex()) {
            if (i > 0) Divider()
            Row(
                Modifier.fillMaxWidth().heightIn(
                    min = 64.dp
                ).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpaceIconImage(s.icon, 28.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = Type.rowTitle, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(notesIn(s), style = Type.rowMeta, color = t.ink2)
                }
                NotesButton(
                    "Edit",
                    { editing = s },
                    Modifier.semantics { contentDescription = "Edit ${s.name}" },
                    kind = ButtonKind.Ghost,
                    small = true
                )
                if (s.id != Spaces.DEFAULT) {
                    NotesButton(
                        "Delete",
                        {
                            scope.launch {
                                if (graph.sync.spaces.delete(s.id)) graph.scheduler.request()
                            }
                        },
                        Modifier.semantics { contentDescription = "Delete ${s.name}" },
                        kind = ButtonKind.DangerText,
                        enabled = s.noteCount == 0,
                        small = true
                    )
                }
            }
        }
        Divider()
        Action("New space", t.link, enabled = spaces.isNotEmpty()) { creating = true }
    }
    Text(
        "Keep separate contexts, like Work and Personal, apart. Each space has its own notes and tags, " +
            "and new notes go to the one you're in.",
        style = Type.small,
        color = t.ink3,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
    )

    editing?.let { space ->
        SpaceDialog(
            title = "Edit space",
            action = "Save",
            initial = space.name,
            initialIcon = space.icon,
            taken = { name -> spaces.any { it.id != space.id && it.name.equals(name, ignoreCase = true) } },
            onDismiss = { editing = null }
        ) { name, icon ->
            editing = null
            scope.launch {
                graph.sync.spaces.update(space.id, name, icon)
                graph.scheduler.request()
            }
        }
    }
    if (creating) {
        NewSpaceDialog(graph, onDismiss = { creating = false }) { id ->
            creating = false
            onCreated(id)
        }
    }
}

/** A new space's name and icon; [onCreated] once it's saved on this device, to sync, and listed. */
@Composable
fun NewSpaceDialog(graph: AppGraph, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // One create per dialog: a second tap mustn't make a twin before the list shows the first.
    var saving by remember { mutableStateOf(false) }
    SpaceDialog(
        title = "New space",
        action = "Create",
        initial = "",
        initialIcon = null,
        taken = { name -> spaces.any { it.name.equals(name, ignoreCase = true) } },
        onDismiss = onDismiss
    ) { name, icon ->
        if (saving) return@SpaceDialog
        saving = true
        scope.launch {
            val id = graph.sync.spaces.create(name, icon)
            graph.scheduler.request()
            // Shown once the list has it: switched to sooner, a list read before the insert would
            // look as if it were deleted and send the switch back to the default.
            graph.spaces.first { list -> list.any { it.id == id } }
            onCreated(id)
        }
    }
}

/** A space's name and icon. */
@Composable
private fun SpaceDialog(
    title: String,
    action: String,
    initial: String,
    initialIcon: String?,
    taken: (String) -> Boolean,
    onDismiss: () -> Unit,
    onDone: (name: String, icon: String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    var icon by remember { mutableStateOf(initialIcon) }
    var iconError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    NotesDialog(onDismiss, title) {
        NotesField(name, {
            name = it
            error = null
        }, "Name", placeholder = "Work", error = error)
        Spacer(Modifier.height(12.dp))
        SpaceIconPicker(icon, {
            icon = it
            iconError = null
        }, iconError)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotesButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.Chip)
            NotesButton(action, {
                val n = Spaces.normalize(name)
                error = when {
                    n == null -> "Enter a name."
                    n.codePointCount(0, n.length) > Spaces.MAX_NAME -> "At most ${Spaces.MAX_NAME} characters."
                    taken(n) -> "Another space has this name."
                    else -> null
                }
                iconError = if (icon == null) "Choose an icon." else null
                val chosen = icon
                if (n != null && chosen != null && error == null) onDone(n, chosen)
            }, Modifier.weight(1f))
        }
    }
}
