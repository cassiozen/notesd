package notes.app.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.account.Account
import notes.app.ui.ButtonKind
import notes.app.ui.ConfirmDialog
import notes.app.ui.Icons
import notes.app.ui.Kicker
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.Sheet
import notes.app.ui.SheetItem
import notes.app.ui.Type
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.IssuedLink
import notes.core.api.Person
import notes.core.api.SetupLink

internal sealed interface Load {
    data object Loading : Load

    data object Failed : Load

    data class Ready(val people: List<Person>) : Load

    /** Not an admin, as the server says. */
    data object Hidden : Load
}

private sealed interface Open {
    data object Invite : Open

    data class Actions(val person: Person) : Open

    data class Link(val person: Person, val link: SetupLink) : Open

    data class Reset(val person: Person) : Open

    data class Remove(val person: Person) : Open
}

/**
 * Who else uses this server, under Server: admins only, so everyone else never sees it. The server decides who's
 * an admin; [Account.SignedIn.isAdmin] only says what to show until it has answered, or when it can't be reached.
 */
@Composable
internal fun PeopleSection(graph: AppGraph, account: Account.SignedIn) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    var open by remember { mutableStateOf<Open?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(account.server, account.username, attempt) {
        val r = graph.api()?.people() ?: ApiResult.Unauthenticated
        load = when {
            r is ApiResult.Ok -> Load.Ready(r.value).also { graph.accounts.adminChanged(true) }
            r.isAdminOnly() -> Load.Hidden.also { graph.accounts.adminChanged(false) }
            else -> Load.Failed
        }
    }

    if (!peopleShown(load, account.isAdmin)) return

    fun update(change: (List<Person>) -> List<Person>) {
        (load as? Load.Ready)?.let { load = Load.Ready(change(it.people)) }
    }
    fun replace(person: Person) = update { list -> list.map { if (it.id == person.id) person else it } }
    fun drop(id: Long) = update { list -> list.filter { it.id != id } }
    fun hide() {
        load = Load.Hidden
        scope.launch { graph.accounts.adminChanged(false) }
    }

    /** One action at a time; [done]'s message, if any, under the card. */
    fun <T> act(person: Person, block: suspend ApiClient.() -> ApiResult<T>, done: (T) -> String?) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            val r = graph.api()?.block() ?: ApiResult.Unauthenticated
            busy = false
            when {
                r is ApiResult.Ok -> status = done(r.value)?.let { it to false }
                r.isAdminOnly() -> hide()
                r is ApiResult.Rejected && r.status == 404 -> drop(person.id)
                else -> status = failureMessage(r) to true
            }
        }
    }

    // The account comes back as it is now: this list may be from before they chose a password.
    fun newLink(person: Person) = act(person, { newSetupLink(person.id) }) { issued ->
        replace(issued.user)
        open = Open.Link(issued.user, issued.link)
        null
    }

    // Fresh first: a stale "invited" would describe removing someone who has since joined as cancelling an invitation.
    fun confirmRemove(person: Person) = act(person, { people() }) { latest ->
        load = Load.Ready(latest)
        latest.find { it.id == person.id }?.let { open = Open.Remove(it) }
        null
    }

    Spacer(Modifier.height(20.dp))
    Kicker("People", Modifier.padding(start = 8.dp, bottom = 8.dp).semantics { heading() })
    Card {
        when (val l = load) {
            is Load.Ready -> {
                val sorted = l.people.sortedWith(
                    compareBy<Person> { it.username != account.username }.thenBy { it.id }
                )
                if (sorted.size > 1) {
                    for (listed in sorted) {
                        val you = listed.username == account.username
                        // Yours as just edited above, not as the list was loaded.
                        val p = if (you) {
                            listed.copy(displayName = account.profile.displayName, avatar = account.profile.avatar)
                        } else {
                            listed
                        }
                        PersonRow(graph, p, you, enabled = !busy) { open = Open.Actions(p) }
                        Divider()
                    }
                }
                Action("Invite someone", t.link, enabled = !busy) {
                    status = null
                    open = Open.Invite
                }
            }

            Load.Failed -> {
                Info("Couldn't reach the server", "People are managed on the server, so it needs to be reachable.")
                Divider()
                Action("Try again", t.link, enabled = true) {
                    load = Load.Loading
                    attempt++
                }
            }

            else -> Text(
                "Loading…",
                style = Type.rowMeta,
                color = t.ink3,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
        }
    }
    status?.let { (text, error) ->
        Text(
            text,
            style = Type.small,
            color = if (error) t.danger else t.ink3,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).semantics {
                liveRegion = LiveRegionMode.Polite
            }
        )
    }
    val ready = load as? Load.Ready
    if (ready != null && ready.people.size <= 1 && status == null) {
        Footnote(
            "Only you use this server. Invite family or friends: each gets their own notes, which no one else can see."
        )
    }

    when (val o = open) {
        Open.Invite -> InviteDialog(
            graph,
            onDismiss = { open = null },
            onForbidden = {
                open = null
                hide()
            }
        ) { issued ->
            update { it + issued.user }
            open = Open.Link(issued.user, issued.link)
        }

        is Open.Actions -> PersonActions(
            o.person,
            onDismiss = { open = null },
            onNewLink = {
                open = null
                newLink(o.person)
            },
            onReset = { open = Open.Reset(o.person) },
            onAdmin = {
                open = null
                val p = o.person
                act(p, { setAdmin(p.id, !p.isAdmin) }) { changed ->
                    replace(changed)
                    if (changed.isAdmin) "${who(p)} is now an admin." else "${who(p)} is no longer an admin."
                }
            },
            onRemove = {
                open = null
                confirmRemove(o.person)
            }
        )

        is Open.Link -> LinkDialog(account.server, o.person, o.link) { open = null }

        is Open.Reset -> ConfirmDialog(
            title = "Reset ${who(o.person)}'s password?",
            text = "You'll get a link to send them, where they choose a new one. Nothing changes until they use it.",
            action = "Get link",
            onDismiss = { open = null }
        ) { newLink(o.person) }

        is Open.Remove -> {
            val p = o.person
            ConfirmDialog(
                title = if (p.pending) "Cancel ${who(p)}'s invitation?" else "Remove ${who(p)}?",
                text = if (p.pending) {
                    "Their link stops working, and the username is free again."
                } else {
                    "They're signed out on every device and can't sign in again. Their notes stay on the " +
                        "server's disk, but no app can open them."
                },
                action = if (p.pending) "Cancel invitation" else "Remove",
                danger = true,
                onDismiss = { open = null }
            ) {
                act(p, { removePerson(p.id) }) {
                    drop(p.id)
                    if (p.pending) "${who(p)}'s invitation was cancelled." else "${who(p)} was removed."
                }
            }
        }

        null -> Unit
    }
}

/**
 * Until the server answers, what the account last knew decides. Not known (signed in before the app kept it), the
 * card stays out of the way while loading, but shows a failure: an admin offline then still has Try again.
 */
internal fun peopleShown(load: Load, isAdmin: Boolean?): Boolean = when (load) {
    is Load.Ready -> true
    Load.Hidden -> false
    Load.Loading -> isAdmin == true
    Load.Failed -> isAdmin != false
}

private fun ApiResult<*>.isAdminOnly() = this is ApiResult.Rejected && status == 403 && error == "admin_only"

private fun failureMessage(r: ApiResult<*>): String = when {
    r is ApiResult.Conflict && r.code == "last_admin" ->
        "The server needs an admin who can sign in. Make someone else an admin first."

    r is ApiResult.Conflict && r.code == "cannot_change_self" -> "You can't change your own account here."

    r is ApiResult.RateLimited -> "Too many attempts. Wait a moment and try again."

    r is ApiResult.Unreachable -> "Can't reach the server."

    else -> "Something went wrong. Try again."
}

/** How a person is named here: their chosen name, or else their username. */
internal fun who(p: Person): String = p.displayName.ifEmpty { p.username }

private fun describe(p: Person): String {
    if (p.pending) return "Invited · ${linkExpiry(p.linkExpiresAt)}"
    val joined = "Joined ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.createdAt))}"
    return if (p.isAdmin) "Admin · $joined" else joined
}

@Composable
private fun PersonRow(graph: AppGraph, person: Person, you: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (you) {
                    Modifier
                } else {
                    Modifier.clickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClickLabel = "Actions for ${who(person)}",
                        onClick = onClick
                    )
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(
            graph.avatars,
            person.id,
            person.avatar,
            who(person),
            Modifier.size(40.dp).clipCircle(t.bg),
            Type.rowTitle.copy(fontSize = 16.sp),
            t.ink2
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(who(person), style = Type.rowTitle, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (person.displayName.isEmpty()) describe(person) else "${person.username} · ${describe(person)}",
                style = Type.rowMeta,
                color = t.ink2,
                maxLines = 2
            )
        }
        if (you) {
            Pill("You")
        } else {
            Icon(Icons.More, null, Modifier.size(22.dp), tint = t.ink3)
        }
    }
}

/** A person's actions, from tapping their row: a sheet, as a note's are. */
@Composable
private fun PersonActions(
    person: Person,
    onDismiss: () -> Unit,
    onNewLink: () -> Unit,
    onReset: () -> Unit,
    onAdmin: () -> Unit,
    onRemove: () -> Unit
) {
    Sheet(onDismiss, title = who(person)) {
        if (person.pending) {
            SheetItem(Icons.Share, "Share new link", onNewLink)
            SheetItem(Icons.Close, "Cancel invitation…", onRemove, danger = true)
        } else {
            SheetItem(Icons.Lock, "Reset password…", onReset)
            SheetItem(Icons.Shield, if (person.isAdmin) "Remove admin" else "Make admin", onAdmin)
            SheetItem(Icons.Trash, "Remove from server…", onRemove, danger = true)
        }
    }
}

@Composable
private fun InviteDialog(
    graph: AppGraph,
    onDismiss: () -> Unit,
    onForbidden: () -> Unit,
    onInvited: (IssuedLink) -> Unit
) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun invite() {
        if (busy) return
        formError = null
        if (name.isBlank()) {
            error = "Enter the username they'll sign in with."
            return
        }
        busy = true
        scope.launch {
            val r = graph.api()?.invite(name.trim(), displayName.trim()) ?: ApiResult.Unauthenticated
            busy = false
            when {
                r is ApiResult.Ok -> onInvited(r.value)

                r.isAdminOnly() -> onForbidden()

                r is ApiResult.Conflict && r.code == "username_taken" -> error = "Someone already has that username."

                r is ApiResult.Rejected && r.field == "username" -> error = "Use 1 to 64 characters, on one line."

                r is ApiResult.Rejected && r.field == "displayName" ->
                    nameError = "Use at most 64 characters, on one line."

                else -> formError = failureMessage(r)
            }
        }
    }

    NotesDialog({ if (!busy) onDismiss() }, "Invite someone") {
        ScrollingBody {
            Text(
                "They get their own notes, which no one else can see. Next, you'll get a link to send them, " +
                    "where they choose their password.",
                style = Type.body,
                color = t.ink2
            )
            Spacer(Modifier.height(16.dp))
            NotesField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                label = "Username",
                error = error,
                hint = "What they'll sign in with.",
                enabled = !busy
            )
            Spacer(Modifier.height(12.dp))
            NotesField(
                value = displayName,
                onValueChange = {
                    displayName = it.take(64)
                    nameError = null
                },
                label = "Name (optional)",
                error = nameError,
                hint = "Shown instead of their username. They can change it when they open the link.",
                enabled = !busy
            )
            formError?.let { FormError(it) }
            DialogActions(onDismiss, "Invite", ::invite, busy)
        }
    }
}

/** The link to send, once: only its hash is kept, so it can't be shown again later. */
@Composable
private fun LinkDialog(server: String, person: Person, link: SetupLink, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    val context = LocalContext.current
    val url = setupLinkUrl(server, link.token)
    var copied by remember { mutableStateOf(false) }

    NotesDialog(onDismiss, "Send ${who(person)} this link") {
        ScrollingBody {
            Text(
                (
                    if (person.pending) {
                        "They open it to choose a password, and they're signed in."
                    } else {
                        "They open it to choose a new password, which signs them out everywhere. Until then, their " +
                            "current password still works."
                    }
                    ) + " It works once, for 7 days. Send it privately: whoever has it can set the password.",
                style = Type.body,
                color = t.ink2
            )
            Spacer(Modifier.height(16.dp))
            SelectionContainer {
                Text(
                    url,
                    style = Type.mono.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = t.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(t.field)
                        .padding(12.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotesButton(
                    if (copied) "Copied" else "Copy",
                    {
                        copySecret(context, url)
                        copied = true
                    },
                    Modifier.weight(1f),
                    kind = ButtonKind.Chip
                )
                NotesButton(
                    "Share…",
                    { share(context, person, hostOf(server), url) },
                    Modifier.weight(1f),
                    icon = Icons.Share
                )
            }
            Spacer(Modifier.height(8.dp))
            NotesButton("Done", onDismiss, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
        }
    }
}

/** Marked sensitive, so Android 13+ doesn't preview a link that sets someone's password. */
private fun copySecret(context: Context, text: String) {
    val clip = ClipData.newPlainText("notesd link", text)
    clip.description.extras = PersistableBundle().apply {
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            "android.content.extra.IS_SENSITIVE"
        }
        putBoolean(key, true)
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private fun share(context: Context, person: Person, host: String, url: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, if (person.pending) "Your notesd invitation" else "Your new notesd password")
        .putExtra(Intent.EXTRA_TEXT, shareText(host, person.pending, url))
    context.startActivity(Intent.createChooser(send, null))
}
