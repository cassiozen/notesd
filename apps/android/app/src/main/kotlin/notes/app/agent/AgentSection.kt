package notes.app.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.settings.Action
import notes.app.settings.Card
import notes.app.settings.Divider
import notes.app.settings.Info
import notes.app.settings.Unsaved
import notes.app.settings.refusal
import notes.app.settings.sentence
import notes.app.ui.ButtonKind
import notes.app.ui.Icons
import notes.app.ui.Kicker
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesField
import notes.app.ui.Sheet
import notes.app.ui.Type
import notes.core.api.ApiResult
import notes.core.db.Space

private const val MAX_MATCHES = 8
private const val MAX_MENU_LABEL = 40

private val FIELDS = setOf("prompt", "menuLabel", "referenceNotes")

/** What to focus once a rule shows: its prompt (after it's added) or its moment (after it moved). */
private data class FocusOn(val key: String, val moment: Boolean, val at: Long = System.nanoTime())

/**
 * A space's agent: on or off, what the space is for, its rules, and the notes it always reads. The agent
 * trait's part of Space info (§7.8, §7.11), on or off: changed in place, saved with its own Save.
 */
@Composable
fun AgentSection(graph: AppGraph, space: Space, onActivity: (String) -> Unit = {}) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val configs by graph.agents.cache.value.collectAsStateWithLifecycle()
    val configsFailed by graph.agents.cache.failed.collectAsStateWithLifecycle()
    val ai by graph.ai.cache.value.collectAsStateWithLifecycle()
    val aiFailed by graph.ai.cache.failed.collectAsStateWithLifecycle()
    val saved = configs?.get(space.id)
    var edited by remember { mutableStateOf<AgentDraft?>(null) }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var formError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedNote by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf<AgentExample?>(null) }
    val draft = edited ?: draftOf(saved)
    val dirty = edited.let { it != null && !sameAgent(it, draftOf(saved)) }
    Unsaved("the agent", dirty)

    fun change(next: AgentDraft) {
        edited = next
        savedNote = false
    }

    fun save() {
        val empty = emptyRules(draft)
        if (empty.isNotEmpty()) {
            errors = empty.associate { "triggers.$it" to "Say what the agent does then, or remove the rule." }
            return
        }
        errors = emptyMap()
        formError = null
        busy = true
        val sent = draft
        scope.launch {
            val r = graph.agents.save(
                space.id,
                sent.enabled,
                sent.prompt,
                sent.menuLabel,
                sent.triggers,
                sent.referenceNotes
            )
            busy = false
            val field = (r as? ApiResult.Rejected)?.takeIf { it.status == 400 }?.field
            when {
                r is ApiResult.Ok -> {
                    // Its trait is the server's: the next sync brings it.
                    graph.scheduler.request()
                    // Edits made while it saved stay, still to be saved.
                    if (edited == sent) edited = null
                    savedNote = true
                }

                r is ApiResult.Rejected && field != null && (field in FIELDS || field.startsWith("triggers.")) ->
                    errors = mapOf(field to sentence(r.message))

                r is ApiResult.Rejected && r.error == "not_found" ->
                    formError = "This space hasn't reached the server yet. Try again once it has synced."

                else -> formError = refusal(r).second
            }
        }
    }

    fun cancel() {
        edited = null
        errors = emptyMap()
        formError = null
    }

    Column(Modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Kicker("Agent", Modifier.weight(1f).semantics { heading() })
            if (dirty) {
                NotesButton("Cancel", ::cancel, kind = ButtonKind.Chip, enabled = !busy, small = true)
                NotesButton("Save", ::save, busy = busy, small = true)
            } else {
                if (savedNote) {
                    Text(
                        "Saved",
                        style = Type.small,
                        color = t.ink3,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                if (saved != null) {
                    NotesButton("Activity…", { onActivity(space.id) }, kind = ButtonKind.Ghost, small = true)
                }
            }
        }
        val provider = ai
        when {
            configs == null || provider == null -> Card {
                if (configsFailed || aiFailed) {
                    Info("Couldn't load the agent.", "Check the connection to the server.")
                    Divider()
                    Action("Try again", t.link, enabled = true) {
                        scope.launch { graph.ai.cache.retryIfFailed() }
                        scope.launch { graph.agents.cache.retryIfFailed() }
                    }
                } else {
                    Text("Loading…", style = Type.rowMeta, color = t.ink3, modifier = Modifier.padding(16.dp))
                }
            }

            else -> {
                OnOff(draft, providerHost(provider.baseUrl).takeIf { provider.configured }) {
                    change(draft.copy(enabled = it))
                }
                formError?.let {
                    Text(
                        it,
                        style = Type.small,
                        color = t.errInk,
                        modifier = Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                // Never set up, it's one line until turned on; one turned off keeps its rules in view, to change.
                if (draft.enabled || saved != null) {
                    Spacer(Modifier.height(20.dp))
                    Examples(showing, draft, onShow = { showing = it }) {
                        change(withExample(draft, it))
                        showing = null
                    }
                    Spacer(Modifier.height(20.dp))
                    Purpose(draft, showing, errors["prompt"], ::change)
                    Spacer(Modifier.height(24.dp))
                    Rules(draft, showing, errors, ::change)
                    Spacer(Modifier.height(24.dp))
                    References(graph, space, draft, showing, errors["referenceNotes"], ::change)
                }
            }
        }
    }
}

@Composable
private fun OnOff(draft: AgentDraft, provider: String?, onChange: (Boolean) -> Unit) {
    val t = LocalTokens.current
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(value = draft.enabled, role = Role.Switch, onValueChange = onChange)
                .semantics { contentDescription = "Agent" }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (draft.enabled) "On" else "Off", style = Type.rowTitle, color = t.ink)
                Text(
                    when {
                        provider == null -> "Paused: AI isn't set up on this server. Everything here is kept."

                        draft.enabled ->
                            "It acts on this space's notes as your rules say. Notes go to $provider; locked notes " +
                                "never do. Everything it does is in Activity, and can be undone."

                        else -> "Turn it on to have it act on this space's notes as your rules say."
                    },
                    style = Type.rowMeta,
                    color = t.ink2
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = draft.enabled,
                // The row toggles it, so the whole line is the target.
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = t.solid, checkedThumbColor = t.onSolid)
            )
        }
    }
}

/**
 * Examples to learn from: one picked shows its parts where they'd go, each to take or leave. Taking one never
 * turns the agent on, or saves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Examples(
    showing: AgentExample?,
    draft: AgentDraft,
    onShow: (AgentExample?) -> Unit,
    onUseAll: (AgentExample) -> Unit
) {
    val t = LocalTokens.current
    FlowRow(
        Modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Text("Learn from an example", style = Type.small, color = t.ink2)
        for (ex in EXAMPLES) {
            Chip(ex.name, selected = showing?.name == ex.name) { onShow(if (showing?.name == ex.name) null else ex) }
        }
    }
    if (showing != null) {
        val has = hasExample(draft, showing)
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                if (has) "Everything from ${showing.name} is in." else "${showing.blurb} Take any part of it below.",
                style = Type.small,
                color = t.ink2
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!has) NotesButton("Use all", { onUseAll(showing) }, kind = ButtonKind.Chip, small = true)
                NotesButton("Close", { onShow(null) }, kind = ButtonKind.Ghost, small = true)
            }
        }
    }
}

/** A pill to pick: an example, or an idea. [from]: where an idea comes from, after it. */
@Composable
private fun Chip(label: String, selected: Boolean = false, from: String? = null, onClick: () -> Unit) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) t.solid else t.chip)
            .border(1.dp, if (selected) t.solid else t.hair, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                if (from != null) contentDescription = "$label, from $from"
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = Type.label, color = if (selected) t.onSolid else t.ink)
        if (from != null) {
            Spacer(Modifier.width(6.dp))
            Text(from, style = Type.small, color = t.ink3)
        }
    }
}

/** A part of an example, dashed, beside where it would go, to take. */
@Composable
private fun Borrow(
    from: String,
    title: String?,
    text: String,
    action: String,
    onTake: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    val t = LocalTokens.current
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    t.hairStrong,
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                )
            }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (title != null) "$from · $title" else from,
                style = Type.label,
                color = t.ink2,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            NotesButton(action, onTake, kind = ButtonKind.Chip, small = true)
        }
        extra()
        Text(text, style = Type.small, color = t.ink2, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Purpose(draft: AgentDraft, example: AgentExample?, error: String?, onChange: (AgentDraft) -> Unit) {
    val box = remember { FocusRequester() }
    NotesField(
        value = draft.prompt,
        onValueChange = { onChange(draft.copy(prompt = it)) },
        label = "What this space is for",
        modifier = Modifier.focusRequester(box),
        placeholder = "Recipes. I cook mostly vegetarian, and measure in grams.",
        error = error,
        hint = "Goes with every rule: what the notes are, and how the agent should treat them.",
        singleLine = false,
        minLines = 3
    )
    if (example != null && example.prompt != draft.prompt) {
        Borrow(
            "${example.name}'s purpose",
            null,
            example.prompt,
            if (draft.prompt.isNotBlank()) "Use instead" else "Use",
            onTake = {
                onChange(draft.copy(prompt = example.prompt))
                box.requestFocus()
            }
        )
    }
}

@Composable
private fun Rules(
    draft: AgentDraft,
    example: AgentExample?,
    errors: Map<String, String>,
    onChange: (AgentDraft) -> Unit
) {
    val t = LocalTokens.current
    var picking by remember { mutableStateOf<String?>(null) }
    var focusOn by remember { mutableStateOf<FocusOn?>(null) }
    val rules = rulesOf(draft)
    val unused = TRIGGERS.filter { it.key !in rules }

    fun remove(key: String) {
        onChange(draft.copy(triggers = draft.triggers - key, menuLabel = if (key == "menu") "" else draft.menuLabel))
    }

    fun take(key: String, idea: Idea) {
        onChange(withIdea(draft, key, idea))
        focusOn = FocusOn(key, moment = false)
    }

    Kicker("Rules", Modifier.padding(start = 8.dp).semantics { heading() })
    Text(
        "Each rule is a moment, and what the agent does then.",
        style = Type.small,
        color = t.ink2,
        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 8.dp)
    )
    if (rules.isEmpty() && example == null) {
        Text(
            "No rules yet. Without one, the agent does nothing.",
            style = Type.body,
            color = t.ink3,
            modifier = Modifier.padding(8.dp)
        )
    }
    for (trigger in TRIGGERS) {
        val has = trigger.key in rules
        val theirs = example?.rules?.get(trigger.key)
        val offer = theirs?.takeIf { !hasIdea(draft, trigger.key, it) }
        if (!has && offer == null) continue
        Column(Modifier.padding(bottom = 12.dp)) {
            if (has) {
                Rule(
                    trigger,
                    draft,
                    errors,
                    focusOn?.takeIf { it.key == trigger.key },
                    onPrompt = { onChange(draft.copy(triggers = draft.triggers + (trigger.key to it))) },
                    onMenuLabel = { onChange(draft.copy(menuLabel = it)) },
                    onIdea = { take(trigger.key, it) },
                    onMoment = { picking = trigger.key },
                    onRemove = { remove(trigger.key) }
                )
            }
            if (offer != null && example != null) {
                Borrow(
                    if (has) "${example.name}'s version" else trigger.rule,
                    offer.title,
                    offer.prompt,
                    if (has) "Use instead" else "Add",
                    onTake = { take(trigger.key, offer) }
                ) {
                    if (trigger.key == "menu" && offer.menuLabel != null) {
                        Text("Command: ${offer.menuLabel}", style = Type.small, color = t.ink3)
                    }
                }
            }
        }
    }
    if (unused.isNotEmpty()) {
        NotesButton("+ Add rule", { picking = "" }, kind = ButtonKind.Chip)
    }

    // "": a new rule; else the rule whose moment changes.
    picking?.let { from ->
        MomentPicker(
            title = if (from.isEmpty()) "Add a rule" else "When",
            current = from.takeIf { it.isNotEmpty() },
            taken = rules.toSet(),
            onDismiss = { picking = null }
        ) { to ->
            picking = null
            if (from.isEmpty()) {
                onChange(draft.copy(triggers = draft.triggers + (to to "")))
                focusOn = FocusOn(to, moment = false)
            } else if (to != from) {
                onChange(
                    draft.copy(
                        triggers = draft.triggers - from + (to to draft.triggers[from].orEmpty()),
                        menuLabel = if (from == "menu") "" else draft.menuLabel
                    )
                )
                focusOn = FocusOn(to, moment = true)
            }
        }
    }
}

/** The moments in their groups: those without a rule, and [current], ticked. */
@Composable
private fun MomentPicker(
    title: String,
    current: String?,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val t = LocalTokens.current
    Sheet(onDismiss, title) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            for (group in TriggerGroup.entries) {
                val options = TRIGGERS.filter { it.group == group && (it.key == current || it.key !in taken) }
                if (options.isEmpty()) continue
                Text(
                    group.title,
                    style = Type.label,
                    color = t.ink3,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() }
                )
                for (o in options) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button) { onPick(o.key) }
                            .semantics { selected = o.key == current }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(o.rule, style = Type.rowTitle, color = t.ink)
                            Text(o.whenText, style = Type.small, color = t.ink2)
                        }
                        if (o.key == current) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Check, null, Modifier.size(18.dp), tint = t.ink2)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Rule(
    trigger: Trigger,
    draft: AgentDraft,
    errors: Map<String, String>,
    focus: FocusOn?,
    onPrompt: (String) -> Unit,
    onMenuLabel: (String) -> Unit,
    onIdea: (Idea) -> Unit,
    onMoment: () -> Unit,
    onRemove: () -> Unit
) {
    val t = LocalTokens.current
    val prompt = draft.triggers[trigger.key].orEmpty()
    val box = remember { FocusRequester() }
    val moment = remember { FocusRequester() }
    LaunchedEffect(focus) {
        when {
            focus == null -> Unit
            focus.moment -> moment.requestFocus()
            else -> box.requestFocus()
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.field)
            .border(1.dp, t.hair, shape)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .focusRequester(moment)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onMoment)
                        .semantics { contentDescription = "${trigger.rule}. Change when" }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        trigger.rule,
                        style = Type.rowTitle,
                        color = t.ink,
                        modifier = Modifier.weight(1f, fill = false).semantics { heading() }
                    )
                    Icon(Icons.Disclosure, null, Modifier.padding(start = 4.dp).size(14.dp), tint = t.ink3)
                }
                Text(trigger.whenText, style = Type.small, color = t.ink2)
            }
            NotesButton(
                "Remove",
                onRemove,
                Modifier.semantics { contentDescription = "Remove rule: ${trigger.rule}" },
                kind = ButtonKind.Ghost,
                small = true
            )
        }
        if (trigger.key == "menu") {
            Spacer(Modifier.height(12.dp))
            NotesField(
                value = draft.menuLabel,
                onValueChange = { onMenuLabel(it.take(MAX_MENU_LABEL)) },
                label = "Command name, as the menu shows it",
                placeholder = "Fix typos",
                error = errors["menuLabel"]
            )
        }
        Spacer(Modifier.height(12.dp))
        NotesField(
            value = prompt,
            onValueChange = onPrompt,
            label = "What the agent does",
            modifier = Modifier.focusRequester(box),
            placeholder = "What should the agent do?",
            error = errors["triggers.${trigger.key}"],
            singleLine = false,
            minLines = 3
        )
        val ideas = if (prompt.isBlank()) ideasFor(trigger.key) else emptyList()
        if (ideas.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ideas", style = Type.small, color = t.ink2)
                for ((idea, from) in ideas) Chip(idea.title, from = from) { onIdea(idea) }
            }
        }
    }
}

/**
 * The space's note titles, as the agent looks them up: [all] of them, and those it can [read]. A locked note's
 * title is there but its note never sent, so it's no "not here yet", and no suggestion either.
 */
private class SpaceTitles(val all: Set<String>, val read: Set<String>)

/** Null while they load. */
@Composable
private fun spaceTitles(graph: AppGraph, spaceId: String): SpaceTitles? {
    val titles by produceState<SpaceTitles?>(null, spaceId) {
        graph.store.changes.onSubscription { emit(Unit) }.collect {
            val notes = graph.store.list(space = spaceId)
            value = SpaceTitles(
                notes.mapTo(HashSet()) { it.title },
                notes.filter { !it.locked }.mapTo(LinkedHashSet()) { it.title }
            )
        }
    }
    return titles
}

@Composable
private fun References(
    graph: AppGraph,
    space: Space,
    draft: AgentDraft,
    example: AgentExample?,
    error: String?,
    onChange: (AgentDraft) -> Unit
) {
    val t = LocalTokens.current
    var title by remember { mutableStateOf("") }
    val refs = draft.referenceNotes
    val titles = spaceTitles(graph, space.id)
    val shown = example?.let(::readsOf).orEmpty()
    // An example's while it's shown; else one a rule taken from an example needs and was left out.
    val offers = (shown + readsNeeded(draft)).distinct().filter { it !in refs }
    val room = refs.size < MAX_REFERENCES
    val typed = title.trim().lowercase()
    // Not one the server would change: the agent looks a title up exactly, so it would never find that note.
    val matches = if (typed.isEmpty()) {
        emptyList()
    } else {
        titles?.read.orEmpty()
            .filter { it.isNotEmpty() && it == titleOf(it) && typed in it.lowercase() && it !in refs }
            .take(MAX_MATCHES)
    }
    val missing = titles?.let { have -> refs.filter { it !in have.all } }.orEmpty()

    fun add(typed: String) {
        val r = titleOf(typed)
        if (r.isEmpty() || r in refs || !room) return
        onChange(draft.copy(referenceNotes = refs + r))
        title = ""
    }

    Kicker("Notes it always reads", Modifier.padding(start = 8.dp).semantics { heading() })
    Text(
        "Notes the agent gets with every rule, by title, such as a Digest it keeps. It acts faster when it " +
            "needn't look for them. Up to $MAX_REFERENCES.",
        style = Type.small,
        color = t.ink2,
        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 8.dp)
    )
    if (refs.isNotEmpty() || (room && offers.isNotEmpty())) {
        Card {
            var first = true
            for (r in refs) {
                if (!first) Divider()
                first = false
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(r, style = Type.rowTitle, color = t.ink, modifier = Modifier.weight(1f, fill = false))
                    if (r in missing) {
                        Spacer(Modifier.width(8.dp))
                        Text("not here yet", style = Type.small, color = t.ink3)
                    }
                    Spacer(Modifier.weight(1f))
                    NotesButton(
                        "×",
                        { onChange(draft.copy(referenceNotes = refs - r)) },
                        Modifier.semantics { contentDescription = "Stop reading $r" },
                        kind = ButtonKind.Ghost,
                        small = true
                    )
                }
            }
            if (room) {
                for (r in offers) {
                    if (!first) Divider()
                    first = false
                    val from = if (example != null && r in shown) example.name else "a rule needs it"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable(role = Role.Button) { add(r) }
                            .semantics(mergeDescendants = true) { contentDescription = "Read $r: $from" }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("+ $r", style = Type.rowTitle, color = t.link)
                        Spacer(Modifier.width(8.dp))
                        Text(from, style = Type.small, color = t.ink3)
                    }
                }
            }
        }
    }
    if (!room && offers.isNotEmpty()) {
        Text(
            "No room for ${offers.joinToString(
                ", "
            )}, which ${if (offers.size == 1) "a rule needs" else "rules need"}: " +
                "take one off to add it.",
            style = Type.small,
            color = t.errInk,
            modifier = Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    if (missing.isNotEmpty()) {
        Text(
            "A note that's not here yet is read once there is one, such as one a rule has the agent make.",
            style = Type.small,
            color = t.ink3,
            modifier = Modifier.padding(8.dp)
        )
    }
    if (room) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            NotesField(
                value = title,
                onValueChange = { title = it.take(200) },
                label = "Note title",
                modifier = Modifier.weight(1f),
                placeholder = "Note title",
                error = error,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(title) })
            )
            Spacer(Modifier.width(8.dp))
            NotesButton("Add", { add(title) }, kind = ButtonKind.Chip, enabled = title.isNotBlank())
        }
        if (matches.isNotEmpty()) {
            Card {
                for ((i, m) in matches.withIndex()) {
                    if (i > 0) Divider()
                    Text(
                        m,
                        style = Type.body,
                        color = t.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { add(m) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }
}
