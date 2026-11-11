package notes.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.account.Account
import notes.app.agent.AgentPage
import notes.app.agent.agentLine
import notes.app.notes.SpaceIconGrid
import notes.app.notes.SpaceIconImage
import notes.app.notes.SpaceTraits
import notes.app.notes.TraitEntry
import notes.app.notes.TraitLine
import notes.app.notes.TraitRow
import notes.app.notes.describeTraits
import notes.app.notes.name
import notes.app.notes.traitRows
import notes.app.sharing.PeoplePage
import notes.app.sharing.WebsitePage
import notes.app.sharing.peopleLine
import notes.app.sharing.websiteLine
import notes.app.ui.Icons
import notes.app.ui.InlineConfirm
import notes.app.ui.LocalReduceMotion
import notes.app.ui.LocalTokens
import notes.app.ui.Reveal
import notes.app.ui.SpaceTheme
import notes.app.ui.SpaceThemes
import notes.app.ui.Type
import notes.app.ui.pressScale
import notes.core.api.SpaceTrait
import notes.core.db.Space
import notes.core.db.Spaces

/**
 * A space's Space info (§7.8): its space-level settings, a row per trait opening its page ([onOpen], by the
 * trait's key). Goes back by itself once the space is gone, deleted here or elsewhere, or left.
 */
@Composable
fun SpaceInfoScreen(
    graph: AppGraph,
    id: String,
    backLabel: String?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit = {},
    /** A space's Activity, from its agent's page. */
    onActivity: (String) -> Unit = {}
) {
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val registry = rememberSpaceInfoRegistry(graph, onActivity)
    val space = spaces.firstOrNull { it.id == id }
    // The list starts empty while it loads: only a space seen here and then missing is gone.
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
    SpaceInfoPage(
        space,
        backLabel,
        onBack,
        registry = registry,
        onOpen = onOpen,
        // The app's scope: a name typed and then left by going back is still saved.
        work = graph.scope,
        onSave = { name, icon ->
            when {
                // The server refuses them, so the store does too; a paste can bring them in.
                name.any { Character.isISOControl(it) } -> "Remove the special characters from the name."

                spaces.any { it.id != id && it.name.equals(name, ignoreCase = true) } -> "Another space has this name."

                runCatching { graph.sync.spaces.update(id, name, icon) }.isFailure ->
                    "Couldn't save on this phone. Try again."

                else -> {
                    graph.scheduler.request()
                    null
                }
            }
        },
        onDelete = {
            val deleted = runCatching { graph.sync.spaces.delete(id) }
            when {
                deleted.getOrNull() == true -> {
                    graph.scheduler.request()
                    null
                }

                deleted.isFailure -> "The space couldn't be deleted on this phone. Try again."

                else -> "Notes arrived in this space from another device. Move or delete them first."
            }
        }
    )
}

/**
 * The app's traits, each with its row and page, for Space info and the pages it opens. Read fresh as either
 * opens: another device may have set an agent up, or an admin connected AI.
 */
@Composable
internal fun rememberSpaceInfoRegistry(graph: AppGraph, onActivity: (String) -> Unit): List<TraitEntry> {
    val account by graph.accounts.account.collectAsStateWithLifecycle()
    val agents by graph.agents.cache.value.collectAsStateWithLifecycle()
    val agentsFailed by graph.agents.cache.failed.collectAsStateWithLifecycle()
    val ai by graph.ai.cache.value.collectAsStateWithLifecycle()
    val aiFailed by graph.ai.cache.failed.collectAsStateWithLifecycle()
    LaunchedEffect(account) {
        if (account !is Account.SignedIn) return@LaunchedEffect
        launch { graph.ai.cache.refresh() }
        graph.agents.cache.refresh()
    }
    val activity by rememberUpdatedState(onActivity)
    return remember(graph, account, agents, ai, agentsFailed, aiFailed) {
        spaceInfoRegistry(graph, { activity(it) }) { space ->
            // Signed in but couldn't tell: the page says so, with Try again, rather than hiding.
            val unsure = (agents == null && agentsFailed) || (ai == null && aiFailed)
            if (unsure && account is Account.SignedIn) return@spaceInfoRegistry true
            val configs = agents ?: return@spaceInfoRegistry false
            val provider = ai ?: return@spaceInfoRegistry false
            provider.configured || space.id in configs
        }
    }
}

/**
 * The app's traits with their rows and pages: People (§7.9), where inviting someone shares a space, and Website
 * (§7.10), where its owner publishes it. Both are there for the account's own spaces but the default, and for a
 * shared space its owner's. The agent's sets it up (§7.11).
 */
internal fun spaceInfoRegistry(
    graph: AppGraph,
    onActivity: (String) -> Unit,
    agentOffered: (Space) -> Boolean
): List<TraitEntry> = SpaceTraits.entries.map {
    when (it.key) {
        "shared" -> it.with(
            row = { space -> peopleLine(graph, space) },
            page = { space, back -> PeoplePage(graph, space, back) },
            canTurnOn = ::canExpose
        )

        "published" -> it.with(
            row = { space -> websiteLine(space) },
            page = { space, back -> WebsitePage(graph, space, back) },
            canTurnOn = ::canExpose
        )

        // Any space, shared or not, by any member (§7.11); offered once AI is set up, or to resume one paused.
        "agent" -> it.with(
            row = { space -> agentLine(graph, space) },
            page = { space, back -> AgentPage(graph, space, back, onActivity) },
            canTurnOn = agentOffered
        )

        else -> it
    }
}

/**
 * Never the default space: everyone keeps a private one, where a locked note refused elsewhere is kept (§7.8). For
 * a shared space, only its owner.
 */
private fun canExpose(space: Space) = space.id != Spaces.DEFAULT && space.shared?.isOwner != false

/**
 * The page itself: icon, name and note count (the owner changes the name and icon right there), one list with a
 * row per trait, each opening its page ([onOpen], by key), then Delete. [onSave] saves a name and icon,
 * [onDelete] deletes the space; each comes back with why it couldn't, or null. They run on [work].
 */
@Composable
internal fun SpaceInfoPage(
    space: Space,
    backLabel: String?,
    onBack: () -> Unit,
    onSave: suspend (name: String, icon: String) -> String?,
    onDelete: suspend () -> String?,
    registry: List<TraitEntry> = SpaceTraits.entries,
    onOpen: (String) -> Unit = {},
    work: CoroutineScope = rememberCoroutineScope()
) {
    val t = LocalTokens.current
    val focus = LocalFocusManager.current
    // A shared space's name, icon and existence are its owner's (§7.9).
    val own = space.shared?.isOwner != false
    var picking by rememberSaveable { mutableStateOf(false) }
    // Saved and on their way to the list: shown meanwhile rather than the old ones in between.
    var name by remember { mutableStateOf<String?>(null) }
    var icon by remember { mutableStateOf<String?>(null) }
    val shownName = name ?: space.name
    val shownIcon = icon ?: space.icon
    LaunchedEffect(space.name) { if (space.name == name) name = null }
    LaunchedEffect(space.icon) { if (space.icon == icon) icon = null }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(newName: String, newIcon: String) {
        error = null
        name = newName.takeIf { it != space.name }
        icon = newIcon.takeIf { it != space.icon }
        work.launch {
            onSave(newName, newIcon)?.let {
                error = it
                name = null
                icon = null
            }
        }
    }

    // The page takes the theme of the icon shown, a pick's before the list has it.
    SpaceTheme(SpaceThemes.key(shownIcon)) {
        SettingsFrame(backLabel, onBack, null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // A tap off the name ends editing it, as leaving the field does on the web.
                    .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) }
            ) {
                Header(
                    space,
                    shownName,
                    shownIcon,
                    own,
                    picking,
                    onIcon = { picking = !picking },
                    onRename = { save(it, shownIcon) }
                )
                Reveal(own && picking) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(t.field)
                            .border(1.dp, t.hair, RoundedCornerShape(20.dp))
                            .padding(6.dp)
                    ) {
                        SpaceIconGrid(shownIcon, onSelect = { save(shownName, it) })
                    }
                }
                error?.let { PageError(it, Modifier.padding(bottom = 8.dp)) }
                Traits(space, registry, onOpen)
                if (own) DeleteSpace(space, onDelete, work)
            }
        }
    }
}

@Composable
private fun PageError(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Type.small,
        color = LocalTokens.current.danger,
        modifier = modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/**
 * Icon, name and note count; the owner's icon opens the picker under it and the name is a field. No trait glyphs:
 * the list under it says the same, in words.
 */
@Composable
private fun Header(
    space: Space,
    name: String,
    icon: String,
    own: Boolean,
    picking: Boolean,
    onIcon: () -> Unit,
    onRename: (String) -> Unit
) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (own) IconButton(icon, picking, onIcon) else SpaceIconImage(icon, ICON, Modifier.padding(8.dp))
        Spacer(Modifier.height(4.dp))
        if (own) {
            NameField(name, onRename)
        } else {
            Text(
                name,
                style = Type.display,
                color = t.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp).semantics { heading() }
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(noteCount(space), style = Type.rowMeta, color = t.ink3)
    }
}

/** Smaller than a page's hero: the list under it should start on the first screen. */
private val ICON = 64.dp

/** The owner's icon: pressed, the picker opens under the header. A pencil says it can be. */
@Composable
private fun IconButton(icon: String, picking: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .size(ICON + 16.dp)
            .pressScale(interaction)
            .clip(shape)
            .background(if (picking) t.hover else Color.Transparent)
            .clickable(
                interaction,
                ripple(),
                role = Role.Button,
                onClickLabel = if (picking) "Hide icons" else "Show icons"
            ) { onClick() }
            .semantics {
                contentDescription = "Change icon"
                stateDescription = if (picking) "Icons shown" else "Icons hidden"
            },
        contentAlignment = Alignment.Center
    ) {
        SpaceIconImage(icon, ICON)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(26.dp)
                .clip(CircleShape)
                .background(t.bg)
                .border(1.dp, t.hair, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Edit, null, Modifier.size(13.dp), tint = t.ink2)
        }
    }
}

/**
 * The owner's name, edited where it's shown: saved on Done, on a tap elsewhere or when the keyboard goes; put
 * back with Escape, or when emptied or left as it was. Trimmed, inner spaces collapsed, as the server keeps it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameField(name: String, onRename: (String) -> Unit) {
    val t = LocalTokens.current
    val focus = LocalFocusManager.current
    // Typing; null when the field isn't being edited.
    var draft by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(name)
    val rename by rememberUpdatedState(onRename)

    fun commit() {
        val typed = draft ?: return
        draft = null
        val normalized = Spaces.normalize(typed)
        if (normalized != null && normalized != current) rename(normalized)
    }

    // Leaving the page with the field still open saves what was typed.
    DisposableEffect(Unit) { onDispose { commit() } }
    // The keyboard closed with Back: done typing, so the field lets go too.
    val ime = WindowInsets.isImeVisible
    var imeWas by remember { mutableStateOf(false) }
    LaunchedEffect(ime) {
        if (imeWas && !ime && focused) focus.clearFocus()
        imeWas = ime
    }

    BasicTextField(
        value = draft ?: name,
        onValueChange = { v -> if (v.codePointCount(0, v.length) <= Spaces.MAX_NAME) draft = v },
        singleLine = true,
        textStyle = Type.display.copy(color = t.ink),
        cursorBrush = SolidColor(t.tint),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        // Hugs the name, so the pencil and the field's outline sit beside it rather than at the page's edges.
        modifier = Modifier
            .onFocusChanged {
                if (it.isFocused && !focused) draft = current
                if (!it.isFocused && focused) commit()
                focused = it.isFocused
            }
            .onPreviewKeyEvent {
                val escape = it.key == Key.Escape && it.type == KeyEventType.KeyDown && draft != null
                if (escape) {
                    draft = current
                    focus.clearFocus()
                }
                escape
            }
            .semantics { contentDescription = "Name" },
        decorationBox = { inner ->
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    // Room to tap even for a short name.
                    .widthIn(min = 160.dp)
                    .clip(shape)
                    .background(if (focused) t.field else Color.Transparent)
                    .border(1.5.dp, if (focused) t.focus else Color.Transparent, shape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Balances the pencil, so the name stays centred under the icon.
                Spacer(Modifier.width(PENCIL))
                // The field's own width is its text's, so the pencil sits right after it; a long name takes what's
                // left and scrolls, the pencil kept in view.
                Box(Modifier.weight(1f, fill = false).width(IntrinsicSize.Max)) { inner() }
                Box(Modifier.width(PENCIL), contentAlignment = Alignment.CenterStart) {
                    if (!focused) Icon(Icons.Edit, null, Modifier.padding(start = 6.dp).size(15.dp), tint = t.ink3)
                }
            }
        }
    )
}

private val PENCIL = 24.dp

/** Every trait in one list, a row each, those with a page opening it. */
@Composable
private fun Traits(space: Space, registry: List<TraitEntry>, onOpen: (String) -> Unit) {
    val rows = traitRows(space, registry)
    if (rows.isEmpty()) return
    Card {
        for ((i, row) in rows.withIndex()) {
            if (i > 0) TraitDivider()
            when (row) {
                is TraitRow.Unknown -> TraitLineRow(
                    SpaceTraits.unknownGlyph,
                    serverLine(row.trait, "${row.trait.summary} · Update the app to change this."),
                    null
                )

                is TraitRow.On, is TraitRow.Off -> {
                    val entry = row.entry() ?: continue
                    key(entry.key) {
                        val line = entry.row?.invoke(space)
                            ?: (row as? TraitRow.On)?.let { serverLine(it.trait, it.trait.summary) }
                            ?: TraitLine(entry.label, "Off", on = false, opens = false)
                        TraitLineRow(entry.glyph, line, entry.page?.let { { onOpen(entry.key) } })
                    }
                }
            }
        }
    }
}

/** A trait this app has no row for: the server's words, or its problem. */
private fun serverLine(trait: SpaceTrait, summary: String) =
    TraitLine(trait.name(), trait.problem ?: summary, on = true, problem = trait.problem != null, opens = false)

/** Delete, confirmed under it; off, with why, for the default space or one with notes. */
@Composable
private fun DeleteSpace(space: Space, onDelete: suspend () -> String?, work: CoroutineScope) {
    val t = LocalTokens.current
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val why = when {
        space.id == Spaces.DEFAULT -> "This space can be renamed, not deleted."
        space.noteCount > 0 -> "Move or delete its notes to delete it."
        else -> null
    }
    Spacer(Modifier.height(24.dp))
    Card {
        Column(Modifier.animateContentSize()) {
            Action("Delete space…", t.danger, enabled = why == null && !asking) {
                error = null
                asking = true
            }
            InlineConfirm(
                visible = asking && why == null,
                title = "Delete ${space.name}?",
                text = "It has no notes: only the space itself goes.",
                action = "Delete",
                busy = busy,
                onConfirm = {
                    busy = true
                    // On [work], not this composable's scope: leaving the page mustn't cancel a delete under way.
                    work.launch {
                        // Done: the space leaves the list and the page goes back by itself.
                        onDelete()?.let {
                            error = it
                            asking = false
                        }
                        busy = false
                    }
                },
                onCancel = { asking = false },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            )
        }
    }
    why?.let { Footnote(it) }
    error?.let { PageError(it, Modifier.padding(top = 8.dp)) }
}
