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
import notes.app.agent.AgentSection
import notes.app.notes.SpaceIconGrid
import notes.app.notes.SpaceIconImage
import notes.app.notes.SpaceTraits
import notes.app.notes.TraitEntry
import notes.app.notes.TraitRow
import notes.app.notes.describeTraits
import notes.app.notes.name
import notes.app.notes.traitRows
import notes.app.sharing.PeopleSection
import notes.app.sharing.PublishedSection
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
 * A space's Space info (§7.8): its one page of space-level settings, everything changed in place. Goes back by
 * itself once the space is gone, deleted here or elsewhere, or left.
 */
@Composable
fun SpaceInfoScreen(graph: AppGraph, id: String, backLabel: String?, onBack: () -> Unit) {
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val account by graph.accounts.account.collectAsStateWithLifecycle()
    val agents by graph.agents.cache.value.collectAsStateWithLifecycle()
    val ai by graph.ai.cache.value.collectAsStateWithLifecycle()
    // Fresh as the page opens: another device may have set one up, or an admin connected AI.
    LaunchedEffect(account) {
        if (account !is Account.SignedIn) return@LaunchedEffect
        launch { graph.ai.cache.refresh() }
        graph.agents.cache.refresh()
    }
    val registry = remember(graph, agents, ai) {
        spaceInfoRegistry(graph) { space ->
            val configs = agents ?: return@spaceInfoRegistry false
            val provider = ai ?: return@spaceInfoRegistry false
            provider.configured || space.id in configs
        }
    }
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
 * The app's traits with their sections: People (§7.9), where inviting someone shares a space, and Published
 * (§7.10), where its owner publishes it. Both are there for the account's own spaces but the default, and for a
 * shared space its owner's. The agent's sets it up (§7.11).
 */
internal fun spaceInfoRegistry(graph: AppGraph, agentOffered: (Space) -> Boolean): List<TraitEntry> =
    SpaceTraits.entries.map {
        when (it.key) {
            "shared" -> it.with(section = { space -> PeopleSection(graph, space) }, canTurnOn = ::canExpose)

            "published" -> it.with(section = { space -> PublishedSection(graph, space) }, canTurnOn = ::canExpose)

            // Any space, shared or not, by any member (§7.11); offered once AI is set up, or to resume one paused.
            "agent" -> it.with(section = { space -> AgentSection(graph, space) }, canTurnOn = agentOffered)

            else -> it
        }
    }

/**
 * Never the default space: everyone keeps a private one, where a locked note refused elsewhere is kept (§7.8). For
 * a shared space, only its owner.
 */
private fun canExpose(space: Space) = space.id != Spaces.DEFAULT && space.shared?.isOwner != false

/**
 * The page itself: icon, name and note count with the trait glyphs (the owner changes the name and icon right
 * there), a section per trait, then Delete. [onSave] saves a name and icon, [onDelete] deletes the space; each
 * comes back with why it couldn't, or null. They run on [work].
 */
@Composable
internal fun SpaceInfoPage(
    space: Space,
    backLabel: String?,
    onBack: () -> Unit,
    onSave: suspend (name: String, icon: String) -> String?,
    onDelete: suspend () -> String?,
    registry: List<TraitEntry> = SpaceTraits.entries,
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
    // Glyphs there when the page opened; one that turns on later arrives with a little movement.
    val initialTraits = remember { space.traits.mapTo(HashSet()) { it.key } }

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

    val unsaved = remember { UnsavedSections() }
    var discarding by remember { mutableStateOf(false) }
    val changed = unsaved.unsaved
    val leave = { if (changed.isEmpty()) onBack() else discarding = true }
    BackHandler(enabled = changed.isNotEmpty() && !discarding) { discarding = true }

    // The page takes the theme of the icon shown, a pick's before the list has it.
    SpaceTheme(SpaceThemes.key(shownIcon)) {
        SettingsFrame(
            backLabel,
            leave,
            null,
            top = {
                InlineConfirm(
                    visible = discarding && changed.isNotEmpty(),
                    title = "Discard your changes?",
                    text = "Your changes to ${changed.joinToString(" and ")} aren't saved.",
                    action = "Discard",
                    onConfirm = {
                        discarding = false
                        onBack()
                    },
                    onCancel = { discarding = false },
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                )
            }
        ) {
            CompositionLocalProvider(LocalUnsaved provides unsaved) {
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
                        initialTraits,
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
                    Traits(space, registry)
                    if (own) DeleteSpace(space, onDelete, work)
                }
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

/** Icon, name, note count and glyphs; the owner's icon opens the picker under it and the name is a field. */
@Composable
private fun Header(
    space: Space,
    name: String,
    icon: String,
    own: Boolean,
    picking: Boolean,
    initialTraits: Set<String>,
    onIcon: () -> Unit,
    onRename: (String) -> Unit
) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (own) IconButton(icon, picking, onIcon) else SpaceIconImage(icon, 72.dp, Modifier.padding(8.dp))
        Spacer(Modifier.height(6.dp))
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(noteCount(space), style = Type.rowMeta, color = t.ink3)
            HeadGlyphs(space.traits, initialTraits)
        }
    }
}

/** The owner's icon: pressed, the picker opens under the header. A pencil says it can be. */
@Composable
private fun IconButton(icon: String, picking: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .size(88.dp)
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
        SpaceIconImage(icon, 72.dp)
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

/** The space's trait glyphs after its count, one not in [initial] arriving with a little movement. */
@Composable
private fun HeadGlyphs(traits: List<SpaceTrait>, initial: Set<String>) {
    if (traits.isEmpty()) return
    val description = describeTraits(traits)
    Row(
        Modifier.padding(start = 10.dp).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (trait in traits) key(trait.key) { HeadGlyph(trait, arriving = trait.key !in initial) }
    }
}

@Composable
private fun HeadGlyph(trait: SpaceTrait, arriving: Boolean) {
    val t = LocalTokens.current
    val reduce = LocalReduceMotion.current
    val shown = remember { Animatable(if (arriving && !reduce) 0f else 1f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f)) }
    Icon(
        painterResource(SpaceTraits.glyph(trait.key)),
        null,
        Modifier.size(16.dp).graphicsLayer {
            val p = shown.value
            scaleX = 0.4f + 0.6f * p
            scaleY = 0.4f + 0.6f * p
            alpha = p.coerceIn(0f, 1f)
            rotationZ = (1f - p) * -40f
        },
        tint = if (trait.problem != null) t.danger else t.ink3
    )
}

/** A trait with a section of its own gets it; the others, a row each, share a card between sections. */
@Composable
private fun Traits(space: Space, registry: List<TraitEntry>) {
    val blocks = ArrayList<List<TraitRow>>()
    for (row in traitRows(space, registry)) {
        val last = blocks.lastOrNull()
        if (row.section() == null && last != null && last.last().section() == null) {
            blocks[blocks.lastIndex] = last + row
        } else {
            blocks += listOf(row)
        }
    }
    for (block in blocks) {
        Spacer(Modifier.height(if (block === blocks.first()) 4.dp else 24.dp))
        val section = block.first().section()
        if (section != null) {
            key(block.first().key()) { section(space) }
        } else {
            Card {
                for ((i, row) in block.withIndex()) {
                    if (i > 0) Divider()
                    TraitLine(row)
                }
            }
        }
    }
}

private fun TraitRow.section(): (@Composable (Space) -> Unit)? = when (this) {
    is TraitRow.On -> entry.section
    is TraitRow.Off -> entry.section
    is TraitRow.Unknown -> null
}

private fun TraitRow.key(): String = when (this) {
    is TraitRow.On -> trait.key
    is TraitRow.Off -> entry.key
    is TraitRow.Unknown -> trait.key
}

/** A trait without a section: glyph, label, and its summary or problem; dimmed while off. */
@Composable
private fun TraitLine(row: TraitRow) {
    val t = LocalTokens.current
    val off = row is TraitRow.Off
    val (glyph, label) = when (row) {
        is TraitRow.On -> row.entry.glyph to row.trait.name()
        is TraitRow.Off -> row.entry.glyph to row.entry.label
        is TraitRow.Unknown -> SpaceTraits.unknownGlyph to row.trait.name()
    }
    val trait = (row as? TraitRow.On)?.trait ?: (row as? TraitRow.Unknown)?.trait
    val problem = trait?.problem
    val summary = problem ?: trait?.summary ?: "Off"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Off: the glyph dims; the words stay at a readable contrast.
        Icon(
            painterResource(glyph),
            null,
            Modifier.size(22.dp).alpha(if (off) 0.5f else 1f),
            tint = if (problem != null) t.danger else t.ink3
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label, style = Type.rowTitle, color = if (off) t.ink2 else t.ink)
            if (summary.isNotEmpty()) {
                Text(summary, style = Type.rowMeta, color = if (problem != null) t.danger else t.ink2)
            }
            if (row is TraitRow.Unknown) Text("Update the app to change this.", style = Type.small, color = t.ink3)
        }
    }
}

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
    Spacer(Modifier.height(32.dp))
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
