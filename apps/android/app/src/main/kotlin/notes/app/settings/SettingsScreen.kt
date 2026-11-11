package notes.app.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.BuildConfig
import notes.app.account.Account
import notes.app.account.Avatars
import notes.app.ui.BackLink
import notes.app.ui.BrandMark
import notes.app.ui.ConfirmDialog
import notes.app.ui.Icons
import notes.app.ui.LocalReduceMotion
import notes.app.ui.LocalTokens
import notes.app.ui.Type
import notes.core.crypto.LockSession
import notes.core.db.Spaces
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private sealed interface Confirm {
    /** [pending]: notes with changes the server hasn't received. */
    data class SignOut(val pending: Int) : Confirm

    data object RemoveAll : Confirm
}

/**
 * Settings, laid out like the system's (docs/contributing/settings-guidelines.md): the account on top, then each
 * category with its state on the right, under a header that says where its settings are kept.
 */
@Composable
fun SettingsScreen(graph: AppGraph, backLabel: String?, onBack: () -> Unit, onOpen: (SettingsSection) -> Unit) {
    val theme by graph.appearance.theme.collectAsStateWithLifecycle()
    val account by graph.accounts.account.collectAsStateWithLifecycle()
    val lockState by graph.locks.state.collectAsStateWithLifecycle()
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val email by graph.email.settings.collectAsStateWithLifecycle()
    val ai by graph.ai.cache.value.collectAsStateWithLifecycle()
    var pending by remember { mutableIntStateOf(0) }
    val syncStatus by graph.sync.status.collectAsStateWithLifecycle()
    val signedIn = account is Account.SignedIn
    LaunchedEffect(signedIn) {
        if (signedIn) {
            launch { graph.ai.cache.refresh() }
            graph.email.refresh()
            graph.refreshAccount()
        }
    }
    LaunchedEffect(Unit) {
        graph.store.changes.onSubscription { emit(Unit) }.collect { pending = graph.store.pendingNotes() }
    }

    SettingsFrame(backLabel, onBack, "Settings") {
        Card {
            AccountRow(account, graph.avatars) { onOpen(SettingsSection.Server) }
            GroupHeader(if (account == Account.None) "Synced once you connect" else "Synced to your account")
            // With no lock password, creating one needs the server: signed out, there's nothing to offer.
            if (account is Account.SignedIn || lockState != LockSession.State.None) {
                val value = when (lockState) {
                    LockSession.State.None -> "Off"
                    LockSession.State.Locked -> "Locked"
                    LockSession.State.Unlocked -> "Unlocked"
                }
                CategoryRow(Icons.Lock, SettingsSection.Security, value, onOpen)
            }
            CategoryRow(Icons.Spaces, SettingsSection.Spaces, "${spaces.size}", onOpen)
            if (account is Account.SignedIn) {
                CategoryRow(Icons.Mail, SettingsSection.Integrations, integrationsValue(email, ai), onOpen)
            }
            GroupHeader("This phone only")
            CategoryRow(Icons.Appearance, SettingsSection.Appearance, theme.label, onOpen)
            CategoryRow(
                Icons.Cloud,
                SettingsSection.Device,
                syncSummary(shownStatus(account, syncStatus), pending),
                onOpen
            )
            CategoryRow(Icons.Info, SettingsSection.About, BuildConfig.VERSION_NAME, onOpen)
        }
    }
}

/** One category's page, opened from [SettingsScreen]. */
@Composable
fun SettingsSectionScreen(
    graph: AppGraph,
    section: SettingsSection,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onSignIn: () -> Unit,
    /** A new space: the app switches to it (§7.7). */
    onSpaceCreated: (String) -> Unit = {},
    /** A space's row: its Space info (§7.8). */
    onSpaceInfo: (String) -> Unit = {}
) {
    val t = LocalTokens.current
    val theme by graph.appearance.theme.collectAsStateWithLifecycle()
    val account by graph.accounts.account.collectAsStateWithLifecycle()
    val lockState by graph.locks.state.collectAsStateWithLifecycle()
    val canFingerprint = remember { graph.fingerprint.available() }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var busy by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    var passwordChanged by remember { mutableStateOf(false) }
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

    SettingsFrame("Settings", onBack, section.title) {
        when (section) {
            SettingsSection.Server -> {
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
                            ProfileLine(graph, a)
                            Divider()
                            Action("Change password", t.link, enabled = !busy) {
                                passwordChanged = false
                                changingPassword = true
                            }
                            Divider()
                            Action(if (busy) "Signing out…" else "Sign out", t.danger, enabled = !busy) {
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
                if (passwordChanged) {
                    Footnote("Password changed. Every other browser and device was signed out and needs the new one.")
                }
                (account as? Account.SignedIn)?.let {
                    PeopleSection(graph, it)
                    TimeZoneCard(graph)
                }
            }

            SettingsSection.Security -> {
                LockedNotesCard(graph.locks, lockState, graph.fingerprint)
                if (lockState != LockSession.State.None && canFingerprint) {
                    Footnote("Fingerprint unlock is only on this phone.")
                }
            }

            SettingsSection.Spaces -> SpacesCard(graph, onSpaceCreated, onSpaceInfo)

            SettingsSection.Integrations -> IntegrationsPage(graph)

            SettingsSection.Appearance -> {
                Segmented(Theme.entries, theme, { it.label }) { graph.appearance.set(it) }
                Footnote("Only on this phone. Your other devices keep their own theme.")
            }

            SettingsSection.Device -> {
                if (account != Account.None) {
                    SyncCard(graph)
                    Spacer(Modifier.height(16.dp))
                }
                Card {
                    Action("Remove all data from this device", t.danger, enabled = !busy) {
                        confirm = Confirm.RemoveAll
                    }
                }
                Footnote("Deletes every note on this phone and signs you out. Notes on the server aren't touched.")
            }

            SettingsSection.About -> Card {
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

    if (changingPassword) {
        AccountPasswordDialog(graph, onDismiss = { changingPassword = false }) {
            changingPassword = false
            passwordChanged = true
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

/**
 * The list and each category's page: a back link, the title, then the content in a readable column.
 * Without [title], the content heads the page itself.
 */
@Composable
internal fun SettingsFrame(
    backLabel: String?,
    onBack: () -> Unit,
    title: String?,
    /** Under the back link, outside the scroll: a question it asks before going back. */
    top: @Composable () -> Unit = {},
    /** At the end of the back link's bar: the page's own Save, say, in reach without scrolling. */
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackLink(backLabel, onBack)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
        top()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                if (title != null) {
                    Text(
                        title,
                        style = Type.display,
                        color = t.ink,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp).semantics { heading() }
                    )
                }
                content()
            }
        }
    }
}

/** Who's signed in, and where: opens Server. */
@Composable
private fun AccountRow(account: Account, avatars: Avatars, onClick: () -> Unit) {
    val t = LocalTokens.current
    val (name, detail) = when (account) {
        Account.None -> "Not connected" to "Notes are on this phone only"
        is Account.SignedIn -> account.profile.name(account.username) to hostOf(account.server)
        is Account.SignedOut -> account.profile.name(account.username) to "Signed out · ${hostOf(account.server)}"
    }
    val profile = (account as? Account.SignedIn)?.profile ?: (account as? Account.SignedOut)?.profile
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val circle = Modifier.size(42.dp).clipCircle(t.hover, t.edge)
        if (profile == null) {
            Box(circle, contentAlignment = Alignment.Center) {
                Icon(Icons.CloudOff, null, Modifier.size(20.dp), tint = t.ink2)
            }
        } else {
            Avatar(avatars, profile.userId, profile.avatar, name, circle, Type.title, t.ink)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.rowTitle, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = Type.rowMeta, color = t.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Chevron, null, Modifier.size(16.dp), tint = t.ink3)
    }
}

/** A group's one line, saying where its settings are kept. */
@Composable
private fun GroupHeader(label: String) {
    Text(
        label,
        style = Type.label,
        color = LocalTokens.current.ink3,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp).semantics { heading() }
    )
}

/** A category: its icon and title, and its state as a short value on the right. */
@Composable
private fun CategoryRow(icon: ImageVector, section: SettingsSection, value: String, onOpen: (SettingsSection) -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) { onOpen(section) }
            .semantics(mergeDescendants = true) {}
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(t.hover),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = t.ink2)
        }
        Spacer(Modifier.width(14.dp))
        Text(section.title, style = Type.rowTitle, color = t.ink, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, style = Type.rowMeta, color = t.ink3, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Chevron, null, Modifier.size(16.dp), tint = t.ink3)
    }
}

internal fun hostOf(server: String) =
    server.toHttpUrlOrNull()?.let { it.host + if (it.port != 80 && it.port != 443) ":${it.port}" else "" } ?: server

/** The server's address, then the account on it; [note] flags a problem, such as being signed out. */
@Composable
private fun Who(username: String, server: String, note: String?) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hostOf(server),
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
