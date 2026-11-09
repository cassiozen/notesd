package notes.app.agent

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.notes.ago
import notes.app.notes.editedBy
import notes.app.settings.Card
import notes.app.settings.Divider
import notes.app.settings.SettingsFrame
import notes.app.settings.refusal
import notes.app.ui.ButtonKind
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.Sheet
import notes.app.ui.Type
import notes.core.api.AgentRun
import notes.core.api.AgentUsage
import notes.core.api.ApiResult
import notes.core.api.DiffLine
import notes.core.api.NoteDiff
import notes.core.db.Space

/** How often what's shown is fetched again while a run in it is waiting or going: the phone has no socket yet. */
internal var activityPollMs = 5_000L

private fun tokens(n: Long): String = NumberFormat.getIntegerInstance().format(n)

/** Fetched again every [activityPollMs] while [polling]. */
@Composable
private fun Polling(polling: Boolean, refresh: suspend () -> Unit) {
    LaunchedEffect(polling) {
        while (polling) {
            delay(activityPollMs)
            refresh()
        }
    }
}

/**
 * What each space's agent did: the runs, newest first, with a space to narrow them to and that space's usage
 * today. [space]: the one chosen first, "" for all. A run opens with [onRun].
 */
@Composable
fun ActivityScreen(graph: AppGraph, space: String, backLabel: String?, onBack: () -> Unit, onRun: (String) -> Unit) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val me by graph.userId.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf(space) }
    var picking by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<AgentUsage?>(null) }
    var runs by remember { mutableStateOf<List<AgentRun>?>(null) }
    var next by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(chosen, attempt) {
        runs = null
        error = null
        usage = null
        if (chosen.isNotEmpty()) launch { usage = (graph.api()?.agentUsage(chosen) as? ApiResult.Ok)?.value }
        when (val r = graph.api()?.agentRuns(space = chosen.ifEmpty { null }) ?: ApiResult.Unauthenticated) {
            is ApiResult.Ok -> {
                runs = r.value.runs
                next = r.value.nextCursor
            }

            else -> error = refusal(r).second
        }
    }

    // The newest page again: runs it has replace what was shown, new ones go first, older pages stay.
    Polling(runs.orEmpty().any(::going)) {
        val page = (graph.api()?.agentRuns(space = chosen.ifEmpty { null }) as? ApiResult.Ok)?.value ?: return@Polling
        val ids = page.runs.mapTo(HashSet()) { it.id }
        val rest = runs.orEmpty().filter { it.id !in ids }
        runs = page.runs + rest
        if (rest.isEmpty()) next = page.nextCursor
        if (chosen.isNotEmpty()) usage = (graph.api()?.agentUsage(chosen) as? ApiResult.Ok)?.value ?: usage
    }

    fun more() {
        busy = true
        scope.launch {
            when (
                val r =
                    graph.api()?.agentRuns(space = chosen.ifEmpty { null }, cursor = next) ?: ApiResult.Unauthenticated
            ) {
                is ApiResult.Ok -> {
                    runs = runs.orEmpty() + r.value.runs
                    next = r.value.nextCursor
                }

                else -> error = refusal(r).second
            }
            busy = false
        }
    }

    SettingsFrame(backLabel, onBack, "Activity") {
        Card {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { picking = true }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Space", style = Type.rowTitle, color = t.ink, modifier = Modifier.weight(1f))
                Text(
                    spaces.firstOrNull { it.id == chosen }?.name ?: "All spaces",
                    style = Type.rowMeta,
                    color = t.ink2
                )
            }
        }
        usage?.takeIf { chosen.isNotEmpty() }?.let {
            Text(
                "Today in this space: ${tokens(it.tokens)} of ${tokens(it.dailyTokenCap)} tokens",
                style = Type.small,
                color = t.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        val list = runs
        when {
            error != null && list == null -> Card {
                Text(
                    error.orEmpty(),
                    style = Type.small,
                    color = t.errInk,
                    modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }
                )
                Divider()
                notes.app.settings.Action("Try again", t.link, enabled = true) { attempt++ }
            }

            list == null -> Text("Loading…", style = Type.rowMeta, color = t.ink3, modifier = Modifier.padding(8.dp))

            list.isEmpty() -> Text(
                "Nothing yet. Each time an agent acts, it shows here, with what it changed.",
                style = Type.body,
                color = t.ink3,
                modifier = Modifier.padding(8.dp)
            )

            else -> Card {
                for ((i, r) in list.withIndex()) {
                    if (i > 0) Divider()
                    RunRow(r, spaces, me, showSpace = chosen.isEmpty()) { onRun(r.id) }
                }
            }
        }
        if (error != null && list != null) {
            Text(error.orEmpty(), style = Type.small, color = t.errInk, modifier = Modifier.padding(8.dp))
        }
        if (next.isNotEmpty() && list != null) {
            Spacer(Modifier.height(12.dp))
            NotesButton(if (busy) "Loading…" else "Show more", ::more, kind = ButtonKind.Chip, enabled = !busy)
        }
    }

    if (picking) {
        Sheet({ picking = false }, "Space") {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SpaceChoice("All spaces", chosen.isEmpty()) {
                    picking = false
                    chosen = ""
                }
                for (s in spaces) {
                    SpaceChoice(s.name, chosen == s.id) {
                        picking = false
                        chosen = s.id
                    }
                }
            }
        }
    }
}

@Composable
private fun SpaceChoice(name: String, current: Boolean, onPick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onPick)
            .semantics { selected = current }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = Type.body, color = t.ink, modifier = Modifier.weight(1f))
        if (current) Icon(Icons.Check, null, Modifier.size(18.dp), tint = t.ink2)
    }
}

/** A run in the list: its note and when; its trigger, space and whom it was for; what became of it. */
@Composable
private fun RunRow(r: AgentRun, spaces: List<Space>, me: Long?, showSpace: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val forWhom = editedBy(spaces.firstOrNull { it.id == r.spaceId }, r.forUserId.takeIf { it != 0L }, me)
    val about = listOfNotNull(
        triggerLabel(r.trigger),
        spaces.firstOrNull { it.id == r.spaceId }?.name?.takeIf { showSpace },
        forWhom?.let { "for $it" }
    ).joinToString(" · ")
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                noteName(r),
                style = Type.rowTitle,
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(ago(r.createdAt), style = Type.small, color = t.ink3)
        }
        Text(about, style = Type.rowMeta, color = t.ink2)
        Text(runStatus(r), style = Type.rowMeta, color = if (r.status == "failed") t.danger else t.ink2)
    }
}

/** One run: what it was, the model's message, each note's line changes, and Undo. */
@Composable
fun RunScreen(graph: AppGraph, id: String, onBack: () -> Unit) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var run by remember { mutableStateOf<AgentRun?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        when (val r = graph.api()?.agentRun(id) ?: ApiResult.Unauthenticated) {
            is ApiResult.Ok -> run = r.value
            else -> error = refusal(r).second
        }
    }
    LaunchedEffect(id) { load() }
    Polling(run?.let(::going) == true) { (graph.api()?.agentRun(id) as? ApiResult.Ok)?.value?.let { run = it } }

    fun undo() {
        busy = true
        error = null
        done = null
        scope.launch {
            when (val r = graph.api()?.undoAgentRun(id) ?: ApiResult.Unauthenticated) {
                is ApiResult.Ok -> done = undoneText(r.value.undone.size, r.value.skipped.map { it.reason })

                // Nothing left to undo: the reasons are on each note, once reloaded.
                is ApiResult.Conflict if r.code == "nothing_to_undo" -> Unit

                else -> error = refusal(r).second
            }
            // What it undid, and why not the rest.
            load()
            busy = false
        }
    }

    SettingsFrame("Activity", onBack, "Run") {
        val shown = run
        if (shown == null) {
            Text(
                error ?: "Loading…",
                style = Type.small,
                color = if (error != null) t.errInk else t.ink3,
                modifier = Modifier.padding(8.dp)
            )
            return@SettingsFrame
        }
        Card {
            Column(Modifier.padding(16.dp)) {
                Text(noteName(shown), style = Type.title, color = t.ink, modifier = Modifier.semantics { heading() })
                Text(runStatus(shown), style = Type.body, color = if (shown.status == "failed") t.danger else t.ink2)
                Spacer(Modifier.height(12.dp))
                Fact("Trigger", triggerLabel(shown.trigger))
                Fact(
                    "When",
                    Instant.ofEpochMilli(shown.createdAt).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
                )
                if (shown.model.isNotEmpty()) Fact("Model", shown.model)
                Fact("Tokens", tokens(shown.promptTokens + shown.completionTokens))
                Fact("Tool calls", shown.toolCalls.toString())
                if (shown.message.isNotEmpty()) {
                    Text(shown.message, style = Type.body, color = t.ink, modifier = Modifier.padding(top = 12.dp))
                }
                if (shown.edits.any { it.undoable }) {
                    Spacer(Modifier.height(16.dp))
                    NotesButton(if (busy) "Undoing…" else "Undo", ::undo, enabled = !busy)
                }
                done?.let {
                    Text(
                        it,
                        style = Type.small,
                        color = t.ink2,
                        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                error?.let { Text(it, style = Type.small, color = t.errInk, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        for (d in shown.diffs) {
            Spacer(Modifier.height(16.dp))
            Change(d, shown.edits.firstOrNull { it.noteId == d.noteId }?.blocked)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val t = LocalTokens.current
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = Type.small, color = t.ink3, modifier = Modifier.width(96.dp))
        Text(value, style = Type.small, color = t.ink)
    }
}

/** A note the run changed or made: its title, its lines, and why it can't be undone. */
@Composable
private fun Change(diff: NoteDiff, blocked: String?) {
    val t = LocalTokens.current
    val title = diff.titleAfter.ifEmpty { "Untitled" }
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (diff.kind == "create") "Made $title" else title,
                style = Type.rowTitle,
                color = t.ink,
                modifier = Modifier.semantics { heading() }
            )
            if (diff.kind == "edit" && diff.titleBefore != diff.titleAfter) {
                Text("Title: “${diff.titleBefore}” → “${diff.titleAfter}”", style = Type.small, color = t.ink2)
            }
            if (diff.textRemoved) {
                Text(
                    "The note was locked or deleted since, so its text isn't kept here.",
                    style = Type.small,
                    color = t.ink2,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else if (diff.lines.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Lines(diff.lines)
            }
            if (blocked != null && blocked != "undone") {
                Text(
                    "Can't undo: ${blockedText(blocked)}",
                    style = Type.small,
                    color = t.ink2,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/** A diff's lines, scrolling sideways together rather than wrapping, as code does. */
@Composable
private fun Lines(lines: List<DiffLine>) {
    val t = LocalTokens.current
    val mono = Type.mono.copy(fontSize = 13.sp, lineHeight = 19.sp)
    // Each line's colour reaches across the box, and across the longest line when it's wider.
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(t.field)
    ) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .widthIn(min = maxWidth)
                .width(IntrinsicSize.Max)
        ) {
            DiffRows(lines, mono)
        }
    }
}

@Composable
private fun DiffRows(lines: List<DiffLine>, mono: TextStyle) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth()) {
        for (l in lines) {
            if (l.op == "…") {
                Text(
                    "${l.count} unchanged ${if (l.count == 1) "line" else "lines"}",
                    style = Type.small,
                    color = t.ink3,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
                continue
            }
            val (bg, label) = when (l.op) {
                "+" -> t.goodBg to "Added"
                "-" -> t.errBg to "Removed"
                else -> null to "Unchanged"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (bg != null) Modifier.background(bg) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 1.dp)
                    .clearAndSetSemantics { contentDescription = "$label: ${l.text}" }
            ) {
                Text(if (l.op == "=") " " else l.op, style = mono, color = t.ink3, modifier = Modifier.width(16.dp))
                Text(l.text.ifEmpty { " " }, style = mono, color = t.ink, softWrap = false)
            }
        }
    }
}

/** Failed runs dismissed on this phone; the last few are enough: a newer run replaces an old failure anyway. */
private class DismissedRuns(context: Context) {
    private val prefs = context.getSharedPreferences("agent", Context.MODE_PRIVATE)

    fun has(id: String) = id in list()

    fun add(id: String) = prefs.edit { putString(KEY, (listOf(id) + list()).take(MAX).joinToString("\n")) }

    private fun list() = prefs.getString(KEY, null)?.split("\n").orEmpty()

    private companion object {
        const val KEY = "dismissedRuns"
        const val MAX = 50
    }
}

/** A note's latest agent run, if it failed and wasn't dismissed, with a way to dismiss it. */
class FailedRun(val run: AgentRun, val dismiss: () -> Unit)

/** Fetched as the note opens, once the account has an agent in some space: the phone hears of runs no other way yet. */
@Composable
fun rememberFailedRun(graph: AppGraph, noteId: String): FailedRun? {
    val context = LocalContext.current
    val dismissed = remember { DismissedRuns(context.applicationContext) }
    val configs by graph.agents.cache.value.collectAsStateWithLifecycle()
    var found by remember(noteId) { mutableStateOf<AgentRun?>(null) }
    LaunchedEffect(Unit) { if (graph.agents.cache.value.value == null) graph.agents.cache.refresh() }
    val any = configs?.isNotEmpty() == true
    LaunchedEffect(noteId, any) {
        if (!any) return@LaunchedEffect
        val r = graph.api()?.agentRuns(note = noteId, limit = 1) as? ApiResult.Ok ?: return@LaunchedEffect
        found = r.value.runs.firstOrNull()?.takeIf { it.status == "failed" && !dismissed.has(it.id) }
    }
    val run = found?.takeIf { any } ?: return null
    return FailedRun(run) {
        dismissed.add(run.id)
        found = null
    }
}
