package notes.app.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.account.Account
import notes.app.notes.SpaceIdentity
import notes.app.settings.Action
import notes.app.settings.Card
import notes.app.settings.Divider
import notes.app.settings.Footnote
import notes.app.settings.Pill
import notes.app.settings.SettingsFrame
import notes.app.settings.initial
import notes.app.ui.ButtonKind
import notes.app.ui.ConfirmDialog
import notes.app.ui.Icons
import notes.app.ui.Kicker
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.Sheet
import notes.app.ui.SheetItem
import notes.app.ui.Type
import notes.core.api.Invitee
import notes.core.api.Member
import notes.core.db.Space

/** What the page is asking about, over it. */
private sealed interface Ask {
    data object Invite : Ask

    data class Actions(val member: Member) : Ask

    data class Remove(val member: Member) : Ask

    data class MakeOwner(val member: Member) : Ask

    data object StopSharing : Ask

    /** [unsynced]: notes there whose changes would be lost. */
    data class Leave(val unsynced: Int) : Ask
}

/**
 * A shared space's people (§7.9), from its Space info: members, invitations, and what this account can do about
 * them. Goes back by itself once the space is gone (left, or this account removed).
 */
@Composable
fun SharingScreen(graph: AppGraph, id: String, backLabel: String?, onBack: () -> Unit) {
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val me by graph.userId.collectAsStateWithLifecycle()
    val space = spaces.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<Ask?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(space != null) {
        if (space != null) {
            seen = true
        } else if (seen) {
            onBack()
        }
    }
    if (space == null) {
        SettingsFrame(backLabel, onBack, null) {}
        return
    }

    /** One call at a time; [done]'s words under the cards once it went through. */
    fun act(done: String?, call: suspend Sharing.() -> Shared<*>) {
        if (busy) return
        busy = true
        status = null
        ask = null
        scope.launch {
            status = when (val r = graph.sharing.call()) {
                is Shared.Done -> done?.let { it to false }
                is Shared.Failed -> r.message to true
            }
            busy = false
        }
    }

    SharingPage(
        space,
        me,
        backLabel,
        onBack,
        busy = busy,
        status = status,
        onInvite = {
            status = null
            ask = Ask.Invite
        },
        onMember = { ask = Ask.Actions(it) },
        onCancel = { invite -> act("${invite.username}'s invitation was cancelled.") { cancelInvite(id, invite.id) } },
        onStopSharing = { ask = Ask.StopSharing },
        onLeave = {
            scope.launch { ask = Ask.Leave(graph.sharing.unsynced(id)) }
        }
    )

    when (val a = ask) {
        Ask.Invite -> PeoplePicker(graph, space, me, onDismiss = { ask = null }) { ask = null }

        is Ask.Actions -> Sheet({ ask = null }, title = a.member.username) {
            SheetItem(Icons.Shield, "Make owner…", { ask = Ask.MakeOwner(a.member) })
            SheetItem(Icons.Trash, "Remove from space…", { ask = Ask.Remove(a.member) }, danger = true)
        }

        is Ask.Remove -> ConfirmDialog(
            "Remove ${a.member.username}?",
            "${space.name} and its notes leave ${a.member.username}'s devices.",
            "Remove",
            onDismiss = { ask = null },
            danger = true
        ) { act("${a.member.username} was removed.") { remove(id, a.member.id) } }

        is Ask.MakeOwner -> ConfirmDialog(
            "Make ${a.member.username} the owner?",
            "Only the owner invites and removes people, renames ${space.name} and deletes it. You stay a member.",
            "Make owner",
            onDismiss = { ask = null }
        ) { act("${a.member.username} owns ${space.name} now.") { makeOwner(id, a.member.id) } }

        Ask.StopSharing -> ConfirmDialog(
            "Stop sharing ${space.name}?",
            "Everyone else leaves it, and its notes leave their devices. It stays here, yours alone.",
            "Stop sharing",
            onDismiss = { ask = null },
            danger = true
        ) { me?.let { act("${space.name} is yours alone again.") { stopSharing(space, it) } } }

        is Ask.Leave -> ConfirmDialog(
            "Leave ${space.name}?",
            leaveText(space.name, a.unsynced),
            "Leave",
            onDismiss = { ask = null },
            danger = true
        ) { me?.let { act(null) { leave(id, it) } } }

        null -> Unit
    }
}

/** What leaving takes away (§7.9): the notes, and with them any change not yet synced. */
internal fun leaveText(name: String, unsynced: Int): String {
    val gone = "Its notes leave this phone; to see them again, you'd need a new invitation."
    return when (unsynced) {
        0 -> gone
        1 -> "$gone 1 note in $name has changes that haven't synced. They'll be lost."
        else -> "$gone $unsynced notes in $name have changes that haven't synced. They'll be lost."
    }
}

/** The page itself: the space, its people, its invitations, then what this account can do. */
@Composable
internal fun SharingPage(
    space: Space,
    me: Long?,
    backLabel: String?,
    onBack: () -> Unit,
    busy: Boolean,
    status: Pair<String, Boolean>?,
    onInvite: () -> Unit,
    onMember: (Member) -> Unit,
    onCancel: (Invitee) -> Unit,
    onStopSharing: () -> Unit,
    onLeave: () -> Unit
) {
    val t = LocalTokens.current
    val shared = space.shared
    val owner = shared?.isOwner == true
    val members = shared?.members.orEmpty()
    val invites = shared?.invites.orEmpty()
    val ownerId = members.firstOrNull()?.id

    SettingsFrame(backLabel, onBack, "Sharing") {
        SpaceIdentity(space.icon, space.name, space.traits, Modifier.padding(start = 4.dp, bottom = 16.dp))
        Kicker("People", Modifier.padding(start = 8.dp, bottom = 8.dp).semantics { heading() })
        Card {
            for ((i, m) in members.withIndex()) {
                if (i > 0) Divider()
                val you = m.id == me
                MemberRow(
                    m.username,
                    if (m.id == ownerId) "Owner" else "Member",
                    you = you,
                    onClick = if (owner && !you) ({ onMember(m) }) else null,
                    enabled = !busy
                )
            }
        }
        if (owner && invites.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Kicker("Invited", Modifier.padding(start = 8.dp, bottom = 8.dp).semantics { heading() })
            Card {
                for ((i, invite) in invites.withIndex()) {
                    if (i > 0) Divider()
                    MemberRow(invite.username, "Hasn't joined yet") {
                        NotesButton(
                            "Cancel",
                            { onCancel(invite) },
                            Modifier.semantics { contentDescription = "Cancel ${invite.username}'s invitation" },
                            kind = ButtonKind.Chip,
                            enabled = !busy,
                            small = true
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Card {
            if (owner) {
                Action("Invite someone", t.link, enabled = !busy, onClick = onInvite)
                Divider()
                Action(
                    "Stop sharing",
                    t.danger,
                    enabled = !busy && me != null && (members.size > 1 || invites.isNotEmpty()),
                    onClick = onStopSharing
                )
            } else {
                Action("Leave space", t.danger, enabled = !busy && me != null, onClick = onLeave)
            }
        }
        Footnote(
            if (owner) {
                "Everyone here can read and edit every note in ${space.name}."
            } else {
                val name = members.firstOrNull()?.username
                if (name != null) "Only its owner, $name, can invite people." else "Only its owner can invite people."
            }
        )
        status?.let { (text, error) ->
            Text(
                text,
                style = Type.small,
                color = if (error) t.danger else t.ink2,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                }
            )
        }
    }
}

/** A person: initial, username and role; "You" for this account, else [onClick]'s actions or [trailing]. */
@Composable
private fun MemberRow(
    username: String,
    role: String,
    you: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val t = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClickLabel = "Actions for $username",
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = trailing == null) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(username)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(username, style = Type.rowTitle, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(role, style = Type.rowMeta, color = t.ink2)
        }
        when {
            you -> Pill("You")
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.More, null, Modifier.size(22.dp), tint = t.ink3)
        }
    }
}

@Composable
private fun Avatar(username: String) {
    val t = LocalTokens.current
    Box(Modifier.size(40.dp).clip(CircleShape).background(t.bg), contentAlignment = Alignment.Center) {
        Text(initial(username), style = Type.rowTitle.copy(fontSize = 16.sp), color = t.ink2)
    }
}

/** Loading the server's accounts, for [PeoplePicker]. */
private sealed interface People {
    data object Loading : People

    data class Ready(val people: List<Member>) : People

    data class Failed(val message: String) : People
}

/**
 * Picks an account on the server to invite into [space] (§7.9), leaving out this account and everyone already
 * in it or invited. The first invitation shares the space. [onInvited] once it went through.
 */
@Composable
fun PeoplePicker(graph: AppGraph, space: Space, me: Long?, onDismiss: () -> Unit, onInvited: () -> Unit) {
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<People>(People.Loading) }
    var busy by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val account by graph.accounts.account.collectAsStateWithLifecycle()
    LaunchedEffect(attempt) {
        people = when (val r = graph.sharing.accounts()) {
            is Shared.Done -> People.Ready(r.value)
            is Shared.Failed -> People.Failed(r.message)
        }
    }
    val taken = space.shared?.members.orEmpty().map { it.id }.toSet() +
        space.shared?.invites.orEmpty().map { it.userId }
    val username = (account as? Account.SignedIn)?.username
    PeoplePickerSheet(
        space,
        people,
        isMe = { it.id == me || it.username == username },
        taken = { it.id in taken },
        busy = busy,
        error = error,
        onDismiss = { if (busy == null) onDismiss() },
        onRetry = {
            people = People.Loading
            attempt++
        }
    ) { person ->
        if (busy != null) return@PeoplePickerSheet
        busy = person.id
        error = null
        scope.launch {
            when (val r = graph.sharing.invite(space, person.id)) {
                is Shared.Done -> onInvited()
                is Shared.Failed -> error = r.message
            }
            busy = null
        }
    }
}

@Composable
private fun PeoplePickerSheet(
    space: Space,
    people: People,
    isMe: (Member) -> Boolean,
    taken: (Member) -> Boolean,
    busy: Long?,
    error: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPick: (Member) -> Unit
) {
    val t = LocalTokens.current
    Sheet(onDismiss, title = if (space.shared == null) "Share ${space.name}" else "Invite to ${space.name}") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                "Whoever you invite can read and edit every note in ${space.name} once they join.",
                style = Type.body,
                color = t.ink2,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            when (people) {
                People.Loading -> Text(
                    "Loading…",
                    style = Type.rowMeta,
                    color = t.ink3,
                    modifier = Modifier.padding(16.dp)
                )

                is People.Failed -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(people.message, style = Type.body, color = t.danger)
                    Spacer(Modifier.height(12.dp))
                    NotesButton("Try again", onRetry, kind = ButtonKind.Chip, small = true)
                }

                is People.Ready -> {
                    val others = people.people.filterNot(isMe)
                    val shown = others.filterNot(taken)
                    if (shown.isEmpty()) {
                        Text(
                            if (others.isEmpty()) {
                                "No one else has an account on this server yet."
                            } else {
                                "Everyone on this server is already in ${space.name}."
                            },
                            style = Type.body,
                            color = t.ink2,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    for (p in shown) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = busy == null, role = Role.Button, onClickLabel = "Invite") {
                                    onPick(p)
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(p.username)
                            Spacer(Modifier.width(14.dp))
                            Text(
                                p.username,
                                style = Type.rowTitle,
                                color = t.ink,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (busy == p.id) Text("Inviting…", style = Type.rowMeta, color = t.ink3)
                        }
                    }
                }
            }
            error?.let {
                Text(
                    it,
                    style = Type.small,
                    color = t.danger,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
                )
            }
        }
    }
}
