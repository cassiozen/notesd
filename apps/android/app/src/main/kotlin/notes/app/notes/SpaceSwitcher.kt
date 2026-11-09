package notes.app.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import notes.app.ui.ButtonKind
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.Sheet
import notes.app.ui.Type
import notes.app.ui.compositeOverBg
import notes.core.api.SpaceTrait
import notes.core.db.Space
import notes.core.db.Spaces

/** An invitation to someone else's space; [from] is the inviter's name. */
data class SpaceInvite(val id: String, val spaceName: String, val spaceIcon: String, val from: String)

/** Joins or declines an invitation by id: null once done, else why not, in words. */
typealias InviteAnswer = suspend (String) -> String?

/**
 * The switcher between spaces (§7.7): the current space's icon and name, opening the current space's card,
 * invitations, the other spaces and New space; in a sheet, or with [popup] (the docked sidebar) a popup under
 * it. The trait button beside it opens Space info directly (§7.8).
 */
@Composable
fun SpaceSwitcher(
    spaces: List<Space>,
    current: String,
    onSwitch: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.space,
    iconSize: Dp = 30.dp,
    glyphSize: Dp = 13.dp,
    onInfo: (String) -> Unit = {},
    popup: Boolean = false,
    invites: List<SpaceInvite> = emptyList(),
    changes: Map<String, String> = emptyMap(),
    onJoin: InviteAnswer = { null },
    onDecline: InviteAnswer = { null },
    /** Opened and closed: [invites] and [changes] need only be kept current while it's open. */
    onShown: (Boolean) -> Unit = {}
) {
    val t = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    val space = spaces.firstOrNull { it.id == current }
    val name = space?.name ?: Spaces.DEFAULT_NAME
    Box(modifier) {
        SpaceIdentity(
            space?.icon.orEmpty(),
            name,
            space?.traits.orEmpty(),
            style = style,
            iconSize = iconSize,
            iconGap = 8.dp,
            glyphSize = glyphSize,
            nameArea = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) { open = true }
                .semantics(mergeDescendants = true) { contentDescription = "Space: $name" }
                .padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            afterName = {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Disclosure, null, Modifier.size(10.dp, 6.dp), tint = t.ink2)
            },
            onTraits = { onInfo(current) }
        )
        if (open) {
            DisposableEffect(Unit) {
                onShown(true)
                onDispose { onShown(false) }
            }
            val content: @Composable () -> Unit = {
                SwitcherContent(
                    spaces,
                    current,
                    invites,
                    changes,
                    onSwitch = { id ->
                        open = false
                        onSwitch(id)
                    },
                    onInfo = { id ->
                        open = false
                        onInfo(id)
                    },
                    onCreate = {
                        open = false
                        onCreate()
                    },
                    // Joining shows the space: the switcher's done.
                    onJoin = { id -> onJoin(id).also { if (it == null) open = false } },
                    onDecline = onDecline
                )
            }
            if (popup) {
                SwitcherPopup(onDismiss = {
                    open = false
                }, content)
            } else {
                Sheet({ open = false }, content = content)
            }
        }
    }
}

/** Under the switcher's top left, kept inside the window. */
private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = IntOffset(
        anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
        anchorBounds.bottom.coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0)
    )
}

@Composable
private fun SwitcherPopup(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(16.dp)
    Popup(BelowAnchor, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .padding(top = 4.dp, bottom = 16.dp)
                .width(300.dp)
                .heightIn(max = 560.dp)
                .shadow(24.dp, shape)
                .clip(shape)
                .background(t.glassHi.compositeOverBg(t))
                .border(1.dp, t.edge, shape)
                .padding(8.dp)
        ) {
            content()
        }
    }
}

/** The switcher's body, the same in the sheet and the popup. */
@Composable
internal fun SwitcherContent(
    spaces: List<Space>,
    current: String,
    invites: List<SpaceInvite>,
    changes: Map<String, String>,
    onSwitch: (String) -> Unit,
    onInfo: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: InviteAnswer,
    onDecline: InviteAnswer
) {
    val t = LocalTokens.current
    val here = spaces.firstOrNull { it.id == current } ?: Space(current, Spaces.DEFAULT_NAME, 0)
    val others = spaces.filter { it.id != current }
    // Scrolls once there are more spaces than the sheet's height shows.
    Column(Modifier.verticalScroll(rememberScrollState())) {
        CurrentCard(here) { onInfo(here.id) }
        for (invite in invites) {
            Spacer(Modifier.height(8.dp))
            key(invite.id) { Invitation(invite, onJoin, onDecline) }
        }
        if (others.isNotEmpty()) {
            Text(
                "Switch to".uppercase(),
                style = Type.label.copy(fontSize = 11.sp, letterSpacing = 0.06.em),
                color = t.ink3,
                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp)
            )
            for (s in others) SwitchRow(s, changes[s.id], { onSwitch(s.id) }, { onInfo(s.id) })
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(1.dp).background(t.hair))
        MenuRow(Icons.Plus, "New space…", onCreate)
    }
}

/** A card trait line: the problem if there is one, else its label and summary. */
internal fun cardLine(trait: SpaceTrait): String = trait.problem
    ?: if (trait.summary.isNotEmpty()) "${trait.name()} · ${trait.summary}" else trait.name()

/** What a row says for TalkBack: its name, then the change if there is one, else its traits, problems named. */
internal fun rowDescription(space: Space, change: String?): String = when {
    change != null -> "${space.name}, $change"
    space.traits.isEmpty() -> space.name
    else -> "${space.name}, ${describeTraits(space.traits)}"
}

/** A row's second line, only for something to look at: the change, else its traits' problems. */
internal fun rowLine(space: Space, change: String?): String? =
    change ?: space.traits.mapNotNull { it.problem }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
private fun CurrentCard(space: Space, onInfo: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.hover.copy(alpha = t.hover.alpha * 0.65f))
    ) {
        // Private is the default and goes unsaid: without traits, just the icon and name.
        val plain = space.traits.isEmpty()
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = if (plain) Alignment.CenterVertically else Alignment.Top
        ) {
            SpaceIconImage(space.icon, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    space.name,
                    style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = t.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Room for Info, which sits over the corner so its touch target doesn't push the lines down.
                    modifier = Modifier.padding(end = 56.dp)
                )
                for (trait in space.traits) {
                    val color = if (trait.problem != null) t.danger else t.ink2
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(SpaceTraits.glyph(trait.key)),
                            null,
                            Modifier.size(14.dp),
                            tint = if (trait.problem != null) t.danger else t.ink3
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(cardLine(trait), style = Type.small, color = color)
                    }
                }
            }
        }
        InfoPill(onInfo, Modifier.align(if (plain) Alignment.CenterEnd else Alignment.TopEnd))
    }
}

@Composable
private fun InfoPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Box(
        modifier
            .padding(end = 4.dp)
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Space info" },
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(t.chip)
                .border(1.dp, t.edge, RoundedCornerShape(8.dp))
                .padding(start = 8.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Info", style = Type.label.copy(fontSize = 12.sp), color = t.ink2)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Chevron, null, Modifier.size(10.dp), tint = t.ink3)
        }
    }
}

@Composable
private fun Invitation(invite: SpaceInvite, onJoin: InviteAnswer, onDecline: InviteAnswer) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun answer(with: InviteAnswer) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            error = with(invite.id)
            busy = false
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // Neutral: a space's accent is kept for the caret, checked tasks, the main button and links.
            .background(t.chip)
            .border(1.dp, t.hair, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        SpaceIconImage(invite.spaceIcon, 22.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(invite.from) }
                    append(" invited you to ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(invite.spaceName) }
                },
                style = Type.body.copy(fontSize = 14.sp),
                color = t.ink
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotesButton(
                    "Join",
                    { answer(onJoin) },
                    Modifier.semantics { contentDescription = "Join ${invite.spaceName}" },
                    enabled = !busy,
                    small = true
                )
                NotesButton(
                    "Decline",
                    { answer(onDecline) },
                    Modifier.semantics { contentDescription = "Decline ${invite.spaceName}" },
                    kind = ButtonKind.Chip,
                    enabled = !busy,
                    small = true
                )
            }
            error?.let {
                Text(
                    it,
                    style = Type.small,
                    color = t.danger,
                    modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }
}

/**
 * A space to switch to, on one line: its icon, name and trait glyphs; a second line only for something to look
 * at (what others changed, else its traits' problems). A long press (or TalkBack's action) opens its Space info
 * without switching.
 */
@Composable
private fun SwitchRow(space: Space, change: String?, onSwitch: () -> Unit, onInfo: () -> Unit) {
    val t = LocalTokens.current
    val description = rowDescription(space, change)
    val line = rowLine(space, change)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onLongClickLabel = "Space info", onLongClick = onInfo, onClick = onSwitch)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction("Space info") {
                        onInfo()
                        true
                    }
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpaceIconImage(space.icon, 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    space.name,
                    style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    color = t.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (space.traits.isNotEmpty()) Glyphs(space.traits, 13.dp, 4.dp, Modifier.padding(start = 7.dp))
                if (change != null) {
                    Spacer(Modifier.width(7.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(t.ink))
                }
            }
            if (line != null) {
                Text(
                    line,
                    style = Type.small.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
                    color = if (change != null) t.ink2 else t.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
