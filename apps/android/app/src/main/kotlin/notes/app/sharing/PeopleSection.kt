package notes.app.sharing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.account.Account
import notes.app.account.Avatars
import notes.app.notes.TraitLine
import notes.app.notes.names
import notes.app.settings.Action
import notes.app.settings.Card
import notes.app.settings.Divider
import notes.app.settings.TraitPage
import notes.app.settings.initial
import notes.app.settings.rememberPhoto
import notes.app.ui.ButtonKind
import notes.app.ui.EaseOut
import notes.app.ui.Icons
import notes.app.ui.InlineConfirm
import notes.app.ui.Kicker
import notes.app.ui.LocalReduceMotion
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.Type
import notes.app.ui.springPop
import notes.core.api.Invitee
import notes.core.api.Member
import notes.core.db.Space

/** What's being confirmed, under the button that asked. */
private sealed interface Confirm {
    data class Remove(val member: Member) : Confirm

    data class MakeOwner(val member: Member) : Confirm

    data object StopSharing : Confirm

    data object Leave : Confirm
}

/** The server's accounts, read for the owner to invite. */
private sealed interface People {
    data object Loading : People

    data class Ready(val people: List<Member>) : People

    data class Failed(val message: String) : People
}

/**
 * Sharing's row in Space info (§7.9): who's in the space ("You, Ana and Ben", with their faces) and how many are
 * invited, or "Only you"; the trait's problem instead, when it has one.
 */
@Composable
fun peopleLine(graph: AppGraph, space: Space): TraitLine {
    val me by graph.userId.collectAsStateWithLifecycle()
    val members = space.shared?.members.orEmpty()
    val invites = space.shared?.invites.orEmpty()
    val others = members.filter { it.id != me }
    val problem = space.traits.firstOrNull { it.key == "shared" }?.problem
    val status = problem ?: listOfNotNull(
        if (others.isEmpty()) "Only you" else names(listOf("You") + others.map { it.name }),
        "${invites.size} invited".takeIf { invites.isNotEmpty() }
    ).joinToString(" · ")
    val faces = members.sortedBy { it.id != me }.take(FACES)
    return TraitLine(
        "People",
        status,
        on = others.isNotEmpty() || invites.isNotEmpty(),
        problem = problem != null,
        end = if (others.isEmpty()) null else ({ Faces(graph.avatars, faces) })
    )
}

private const val FACES = 3

/** The first few in the space, overlapping: who's there at a glance, before the page names them. */
@Composable
private fun Faces(avatars: Avatars, people: List<Member>) {
    val t = LocalTokens.current
    Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        for (person in people) {
            // Ringed in the page's colour, so the edges read where they overlap.
            Box(Modifier.clip(CircleShape).background(t.bg).padding(2.dp)) {
                Avatar(avatars, person, base = 22.dp, solid = true)
            }
        }
    }
}

/**
 * Sharing's page (§7.9): who's in the space, who's invited, and everyone else on the server to invite with one tap
 * (the first invitation shares it), all changed in place. The owner cancels invitations, removes people, hands the
 * space on and stops sharing; a member leaves.
 */
@Composable
fun PeoplePage(graph: AppGraph, space: Space, back: () -> Unit) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val me by graph.userId.collectAsStateWithLifecycle()
    val account by graph.accounts.account.collectAsStateWithLifecycle()
    val username = (account as? Account.SignedIn)?.username
    val owner = space.shared?.isOwner != false
    val members = space.shared?.members.orEmpty()
    val invites = space.shared?.invites.orEmpty()
    val others = members.filter { it.id != me }
    val shared = others.isNotEmpty() || invites.isNotEmpty()

    var confirm by remember { mutableStateOf<Confirm?>(null) }
    // One call at a time: "invite:<id>", "cancel:<id>", "confirm" or "leave".
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Just invited: their row arrives with a highlight, so it's seen where it landed.
    var fresh by remember { mutableStateOf<Long?>(null) }
    // Notes in the space with changes not yet synced, said when leaving.
    var unsynced by remember { mutableIntStateOf(0) }

    // Read as the page opens, so the people to invite are there by the time they're looked for.
    var people by remember { mutableStateOf<People>(People.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(owner, attempt) {
        if (!owner) return@LaunchedEffect
        people = People.Loading
        people = when (val r = graph.sharing.accounts()) {
            is Shared.Done -> People.Ready(r.value)
            is Shared.Failed -> People.Failed(r.message)
        }
    }
    val taken = members.mapTo(HashSet()) { it.id } + invites.map { it.userId }
    val candidates = (people as? People.Ready)?.people.orEmpty()
        .filter { it.id != me && it.username != username && it.id !in taken }

    fun act(key: String, call: suspend Sharing.() -> Shared<*>, done: () -> Unit = {}) {
        if (busy != null) return
        busy = key
        error = null
        scope.launch {
            when (val r = graph.sharing.call()) {
                is Shared.Done -> done()
                is Shared.Failed -> error = r.message
            }
            busy = null
        }
    }

    fun confirmed(c: Confirm) {
        val after = { confirm = null }
        when (c) {
            is Confirm.Remove -> act("confirm", { remove(space.id, c.member.id) }, after)
            is Confirm.MakeOwner -> act("confirm", { makeOwner(space.id, c.member.id) }, after)
            Confirm.StopSharing -> me?.let { id -> act("confirm", { stopSharing(space, id) }, after) }
            Confirm.Leave -> me?.let { id -> act("confirm", { leave(space.id, id) }, after) }
        }
    }

    fun invite(person: Member) {
        fresh = person.id
        act("invite:${person.id}", { invite(space, person.id) })
    }

    TraitPage(
        space.name,
        space.icon,
        "People",
        back,
        lead = if (shared) {
            "Everyone here can read and edit every note in ${space.name}, and add their own."
        } else {
            "Invite people to read and edit every note in ${space.name}, and add their own."
        }
    ) {
        Column {
            Card {
                Column(Modifier.animateContentSize(if (LocalReduceMotion.current) snap() else springPop())) {
                    if (shared) {
                        for ((i, m) in members.withIndex()) {
                            if (i > 0) Divider()
                            key("m${m.id}") {
                                MemberRow(
                                    graph.avatars,
                                    space,
                                    m,
                                    role = listOfNotNull("Owner".takeIf { i == 0 }, "You".takeIf { m.id == me })
                                        .joinToString(" · "),
                                    manage = owner && m.id != me,
                                    busy = busy != null,
                                    confirm = confirm,
                                    onAsk = {
                                        error = null
                                        confirm = it
                                    },
                                    onCancel = { confirm = null },
                                    onConfirm = ::confirmed,
                                    working = busy == "confirm"
                                )
                            }
                        }
                        if (owner) {
                            for (invite in invites) {
                                Divider()
                                key("i${invite.id}") {
                                    InvitedRow(
                                        graph.avatars,
                                        invite,
                                        arriving = invite.userId == fresh,
                                        cancelling = busy == "cancel:${invite.id}",
                                        enabled = busy == null
                                    ) { act("cancel:${invite.id}", { cancelInvite(space.id, invite.id) }) }
                                }
                            }
                        }
                    }
                    if (owner) {
                        Invite(
                            graph.avatars,
                            people,
                            candidates,
                            shared,
                            busy,
                            onRetry = { attempt++ },
                            onInvite = ::invite
                        )
                    }
                }
            }
            error?.let {
                Text(
                    it,
                    style = Type.small,
                    color = t.danger,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
                )
            }
            if (!owner || shared) {
                Spacer(Modifier.height(12.dp))
                Card {
                    Column(Modifier.animateContentSize()) {
                        if (owner) {
                            Action("Stop sharing…", t.danger, enabled = busy == null && me != null) {
                                error = null
                                confirm = Confirm.StopSharing
                            }
                            InlineConfirm(
                                visible = confirm == Confirm.StopSharing,
                                title = "Stop sharing ${space.name}?",
                                text = "Everyone else leaves it, and its notes leave their devices. " +
                                    "It stays here, yours alone.",
                                action = "Stop sharing",
                                busy = busy == "confirm",
                                onConfirm = { confirmed(Confirm.StopSharing) },
                                onCancel = { confirm = null },
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            )
                        } else {
                            Action("Leave space…", t.danger, enabled = busy == null && me != null) {
                                error = null
                                busy = "leave"
                                scope.launch {
                                    unsynced = graph.sharing.unsynced(space.id)
                                    busy = null
                                    confirm = Confirm.Leave
                                }
                            }
                            InlineConfirm(
                                visible = confirm == Confirm.Leave,
                                title = "Leave ${space.name}?",
                                text = leaveText(space.name, unsynced),
                                action = "Leave",
                                busy = busy == "confirm",
                                onConfirm = { confirmed(Confirm.Leave) },
                                onCancel = { confirm = null },
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val KEPT_AS_COPIES = "They'll be kept as copies in your own notes."

/** What leaving takes away (§7.9): the notes; a change not yet synced stays, as a copy in the account's own notes. */
internal fun leaveText(name: String, unsynced: Int): String {
    val gone = "Its notes leave this phone; to see them again, you'd need a new invitation."
    return when (unsynced) {
        0 -> gone
        1 -> "$gone 1 note in $name has changes that haven't synced. They'll be kept as a copy in your own notes."
        else -> "$gone $unsynced notes in $name have changes that haven't synced. $KEPT_AS_COPIES"
    }
}

/** A member: who, their role, and for the owner Make owner and Remove, each confirmed under the row. */
@Composable
private fun MemberRow(
    avatars: Avatars,
    space: Space,
    member: Member,
    role: String,
    manage: Boolean,
    busy: Boolean,
    working: Boolean,
    confirm: Confirm?,
    onAsk: (Confirm) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (Confirm) -> Unit
) {
    val name = member.name
    val asking = (confirm as? Confirm.Remove)?.member?.id == member.id ||
        (confirm as? Confirm.MakeOwner)?.member?.id == member.id
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        PersonLine(
            identity = { Who(avatars, member, role) },
            actions = if (!manage) {
                null
            } else {
                {
                    Row {
                        NotesButton(
                            "Make owner",
                            { onAsk(Confirm.MakeOwner(member)) },
                            Modifier.semantics { contentDescription = "Make $name the owner" },
                            kind = ButtonKind.Ghost,
                            enabled = !busy && !asking,
                            small = true
                        )
                        NotesButton(
                            "Remove",
                            { onAsk(Confirm.Remove(member)) },
                            Modifier.semantics { contentDescription = "Remove $name" },
                            kind = ButtonKind.DangerText,
                            enabled = !busy && !asking,
                            small = true
                        )
                    }
                }
            }
        )
        if (manage) {
            InlineConfirm(
                visible = (confirm as? Confirm.MakeOwner)?.member?.id == member.id,
                title = "Make $name the owner?",
                text = "Only the owner invites and removes people, renames ${space.name} and deletes it. " +
                    "You stay a member.",
                action = "Make owner",
                busy = working,
                onConfirm = { onConfirm(Confirm.MakeOwner(member)) },
                onCancel = onCancel,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            InlineConfirm(
                visible = (confirm as? Confirm.Remove)?.member?.id == member.id,
                title = "Remove $name?",
                text = "${space.name} and its notes leave $name's devices.",
                action = "Remove",
                busy = working,
                onConfirm = { onConfirm(Confirm.Remove(member)) },
                onCancel = onCancel,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }
    }
}

/** Someone invited who hasn't joined: an outlined avatar, and Cancel, which needs no asking. */
@Composable
private fun InvitedRow(
    avatars: Avatars,
    invite: Invitee,
    arriving: Boolean,
    cancelling: Boolean,
    enabled: Boolean,
    onCancel: () -> Unit
) {
    val t = LocalTokens.current
    val reduce = LocalReduceMotion.current
    val shown = remember { MutableTransitionState(!arriving || reduce).apply { targetState = true } }
    val glow = remember { Animatable(if (arriving) 1f else 0f) }
    LaunchedEffect(Unit) { glow.animateTo(0f, tween(1600, easing = EaseOut)) }
    AnimatedVisibility(shown, enter = fadeIn(tween(220)) + expandVertically(springPop())) {
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(t.on.copy(alpha = t.on.alpha * glow.value)) }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            PersonLine(
                identity = {
                    val person = Member(invite.userId, invite.username, invite.displayName, invite.avatar)
                    Who(avatars, person, "Invited", pending = true)
                },
                actions = {
                    NotesButton(
                        "Cancel",
                        onCancel,
                        Modifier.semantics { contentDescription = "Cancel ${invite.name}'s invitation" },
                        kind = ButtonKind.Ghost,
                        enabled = enabled,
                        busy = cancelling,
                        small = true
                    )
                }
            )
        }
    }
}

/**
 * Everyone else on the server, one tap each: few enough on a home server to show them all. Alone, the sentence
 * above says what they're for; under the people already in, a quiet label does.
 */
@Composable
private fun Invite(
    avatars: Avatars,
    people: People,
    candidates: List<Member>,
    shared: Boolean,
    busy: String?,
    onRetry: () -> Unit,
    onInvite: (Member) -> Unit
) {
    val t = LocalTokens.current
    // Alone in its card, it's the card's first line: the same inset all round.
    val pad = Modifier.fillMaxWidth().padding(
        start = 16.dp,
        end = 16.dp,
        top = if (shared) 10.dp else 14.dp,
        bottom = 14.dp
    )
    when (people) {
        People.Loading -> if (!shared) {
            Text("Loading people…", style = Type.small, color = t.ink3, modifier = pad)
        }

        is People.Failed -> {
            if (shared) Divider()
            Row(pad, verticalAlignment = Alignment.CenterVertically) {
                Text(people.message, style = Type.small, color = t.danger, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                NotesButton("Try again", onRetry, kind = ButtonKind.Chip, small = true)
            }
        }

        is People.Ready -> when {
            candidates.isNotEmpty() -> {
                if (shared) Divider()
                Column(pad) {
                    if (shared) {
                        Text("Invite", style = Type.label, color = t.ink3, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in candidates) {
                            key(p.id) {
                                InviteChip(avatars, p, inviting = busy == "invite:${p.id}", enabled = busy == null) {
                                    onInvite(p)
                                }
                            }
                        }
                    }
                }
            }

            !shared -> Text(
                "There’s no one else on this server yet. An admin adds people in Settings › Server › People.",
                style = Type.small,
                color = t.ink3,
                modifier = pad
            )

            else -> Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
private fun InviteChip(avatars: Avatars, person: Member, inviting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val name = person.name
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(t.chip)
            .border(1.dp, t.edge, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Invite $name" }
            .padding(start = 5.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(avatars, person, base = 30.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = Type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = if (enabled || inviting) t.ink else t.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        if (inviting) {
            CircularProgressIndicator(Modifier.size(14.dp), color = t.ink3, strokeWidth = 1.5.dp)
        } else {
            Icon(Icons.Plus, null, Modifier.size(14.dp), tint = t.ink3)
        }
    }
}

/** Avatar, name and role, read as one; the username beside the role when they chose a name, which may repeat. */
@Composable
private fun Who(avatars: Avatars, person: Member, role: String, pending: Boolean = false) {
    val t = LocalTokens.current
    val name = person.name
    val line = listOfNotNull(person.username.takeIf { person.displayName.isNotEmpty() }, role.ifEmpty { null })
        .joinToString(" · ")
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Avatar(avatars, person, pending = pending)
        Spacer(Modifier.width(AVATAR_GAP))
        Column {
            Text(
                name,
                style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (line.isNotEmpty()) Text(line, style = Type.small, color = t.ink3)
        }
    }
}

private val AVATAR = 36.dp
private val AVATAR_GAP = 12.dp

/** Someone's photo, or their initial, in a circle; [pending] (invited, not in yet) an outline instead of a fill. */
@Composable
private fun Avatar(
    avatars: Avatars,
    person: Member,
    base: Dp = AVATAR,
    pending: Boolean = false,
    /** Opaque, for where avatars overlap: the one under shouldn't show through. */
    solid: Boolean = false
) {
    val t = LocalTokens.current
    val photo = rememberPhoto(avatars, person.id, person.avatar)
    // Grows a little with large text, so it doesn't shrink beside the name; not fully, or it'd crowd the row.
    val size = base * LocalDensity.current.fontScale.coerceIn(1f, 1.4f)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (pending) {
                    Modifier.drawBehind {
                        val w = 1.dp.toPx()
                        drawCircle(
                            t.ink3,
                            radius = this.size.minDimension / 2 - w / 2,
                            style = Stroke(
                                w,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()))
                            )
                        )
                    }
                } else {
                    Modifier.then(if (solid) Modifier.background(t.bg) else Modifier).background(t.hover)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) {
            Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            // Sized by the circle, not the text setting: a larger letter would only spill out of it.
            val letter = with(LocalDensity.current) { (size * 0.4f).toSp() }
            Text(
                initial(person.name),
                style = Type.label.copy(fontSize = letter, lineHeight = letter, fontWeight = FontWeight.SemiBold),
                color = t.ink2
            )
        }
    }
}

/**
 * Who and their buttons on one line; when the buttons would squeeze the name (a narrow screen, large text),
 * the buttons go under it, lined up with the name.
 */
@Composable
private fun PersonLine(identity: @Composable () -> Unit, actions: (@Composable () -> Unit)?) {
    Layout(contents = listOf(identity, actions ?: {})) { (who, buttons), c ->
        val width = c.maxWidth
        val b = buttons.firstOrNull()?.measure(Constraints(maxWidth = width))
        val bw = b?.width ?: 0
        val avatar = AVATAR * fontScale.coerceIn(1f, 1.4f)
        if (b == null || width - bw >= (avatar + AVATAR_GAP + 96.dp).roundToPx()) {
            val w = who.first().measure(Constraints(maxWidth = width - bw))
            val h = maxOf(w.height, b?.height ?: 0)
            layout(width, h) {
                w.place(0, (h - w.height) / 2)
                b?.place(width - bw, (h - b.height) / 2)
            }
        } else {
            val w = who.first().measure(Constraints(maxWidth = width))
            // The buttons' own padding lines their words up with the name.
            val x = (avatar + AVATAR_GAP - 14.dp).roundToPx()
            layout(width, w.height + b.height) {
                w.place(0, 0)
                b.place(x, w.height)
            }
        }
    }
}
