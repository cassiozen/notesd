package notes.app.sharing

import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.settings.Action
import notes.app.settings.Card
import notes.app.settings.Divider
import notes.app.ui.ButtonKind
import notes.app.ui.InlineConfirm
import notes.app.ui.Kicker
import notes.app.ui.LocalReduceMotion
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesField
import notes.app.ui.Type
import notes.app.ui.springPop
import notes.core.api.PublishSettings
import notes.core.db.Space

/** The address without its scheme: "notes.example.com/@garden". */
internal fun shownAddress(s: PublishSettings): String = "${s.origin.substringAfter("://")}/@${s.handle}"

/**
 * Space info's section for publishing (§7.10): off, its owner publishes the space; on, its address (opened, shared,
 * or moved to another handle, asked first) and Stop publishing. A member sees where it is. Its custom CSS is the
 * web app's to change.
 */
@Composable
fun PublishedSection(graph: AppGraph, space: Space) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val trait = space.traits.firstOrNull { it.key == "published" }
    val owner = space.shared?.isOwner != false

    var settings by remember { mutableStateOf<PublishSettings?>(null) }
    // One call at a time: "publish", "handle" or "stop".
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // The handle being typed; null when it isn't being changed.
    var draft by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<String?>(null) }
    var stopping by remember { mutableStateOf(false) }

    // Read again when the trait changes: published, moved or taken down here or on another device.
    LaunchedEffect(trait?.summary, owner) {
        if (trait == null || !owner) return@LaunchedEffect
        when (val r = graph.publishing.settings(space.id)) {
            is Shared.Done -> settings = r.value
            is Shared.Failed -> if (r.message != Publishing.NOT_PUBLISHED) error = r.message
        }
    }

    /** Runs one call; [settled] after it either way, [done] only when it went through. */
    fun <T> act(key: String, call: suspend Publishing.() -> Shared<T>, settled: () -> Unit = {}, done: (T) -> Unit) {
        if (busy != null) return
        busy = key
        error = null
        scope.launch {
            when (val r = graph.publishing.call()) {
                is Shared.Done -> done(r.value)
                is Shared.Failed -> error = r.message
            }
            busy = null
            settled()
        }
    }

    Kicker("Published", Modifier.padding(start = 8.dp, bottom = 8.dp).semantics { heading() })
    Card {
        Column(Modifier.animateContentSize(if (LocalReduceMotion.current) snap() else springPop())) {
            trait?.problem?.let {
                Text(it, style = Type.small, color = t.danger, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 0.dp))
            }
            val current = settings
            when {
                trait == null -> Column(
                    Modifier.fillMaxWidth().padding(16.dp, 14.dp, 16.dp, 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Publish ${space.name} as a website anyone can read: each note with a title and some text " +
                            "is a post, with pages for its tags and a search. Its address comes from the space's " +
                            "name, and you can change it after.",
                        style = Type.body,
                        color = t.ink2
                    )
                    NotesButton(
                        "Publish",
                        { act("publish", { publish(space) }) { settings = it } },
                        kind = ButtonKind.Accent,
                        enabled = busy == null,
                        busy = busy == "publish",
                        small = true
                    )
                }

                !owner || current == null -> Text(
                    trait.summary,
                    style = Type.body,
                    color = t.ink2,
                    modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 14.dp)
                )

                draft != null -> HandleEditor(
                    current,
                    draft.orEmpty(),
                    asking,
                    working = busy == "handle",
                    onType = {
                        draft = it.lowercase()
                        error = null
                    },
                    onSave = { typed ->
                        val handle = typed.trim()
                        if (handle.isEmpty() || handle == current.handle) draft = null else asking = handle
                    },
                    onCancel = {
                        draft = null
                        asking = null
                    },
                    // Only the question: the handle stays as typed, to change or save again.
                    onKeep = { asking = null },
                    onConfirm = { handle ->
                        act("handle", { changeHandle(space.id, handle) }, settled = { asking = null }) {
                            settings = it
                            draft = null
                        }
                    }
                )

                else -> Address(current, space.name, enabled = busy == null) {
                    error = null
                    draft = current.handle
                }
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
    if (trait != null && owner) {
        Spacer(Modifier.height(12.dp))
        Card {
            Column(Modifier.animateContentSize()) {
                Action("Stop publishing…", t.danger, enabled = busy == null) {
                    error = null
                    stopping = true
                }
                InlineConfirm(
                    visible = stopping,
                    title = "Stop publishing ${space.name}?",
                    text = "The site goes offline. Its address stays this space's, for when you publish it again.",
                    action = "Stop publishing",
                    busy = busy == "stop",
                    onConfirm = {
                        act("stop", { stop(space.id) }) {
                            stopping = false
                            settings = null
                        }
                    },
                    onCancel = { stopping = false },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                )
            }
        }
    }
}

/** Where the site is, with Open (the browser), Share (the system's sheet) and Change (its handle). */
@Composable
private fun Address(settings: PublishSettings, name: String, enabled: Boolean, onChange: () -> Unit) {
    val t = LocalTokens.current
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
        Text(
            shownAddress(settings),
            style = Type.mono,
            color = t.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = "Address: ${shownAddress(settings)}" }
        )
        // The buttons' own padding would indent their words from the address's.
        Row(Modifier.padding(top = 4.dp).offset(x = (-14).dp), verticalAlignment = Alignment.CenterVertically) {
            NotesButton("Open", { uri.openUri(settings.url) }, kind = ButtonKind.Ghost, small = true)
            NotesButton("Share", { share(context, name, settings.url) }, kind = ButtonKind.Ghost, small = true)
            NotesButton(
                "Change address",
                onChange,
                kind = ButtonKind.Ghost,
                enabled = enabled,
                small = true
            )
        }
    }
}

/** The handle being changed: saved on Done or Save, asked first, since links to the old address stop working. */
@Composable
private fun HandleEditor(
    settings: PublishSettings,
    draft: String,
    asking: String?,
    working: Boolean,
    onType: (String) -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onKeep: () -> Unit,
    onConfirm: (String) -> Unit
) {
    Divider()
    Column(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 12.dp)) {
        NotesField(
            draft,
            onType,
            "Address",
            hint = "${settings.origin.substringAfter("://")}/@$draft",
            mono = true,
            enabled = !working,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onSave(draft) })
        )
        if (asking == null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotesButton("Save", { onSave(draft) }, kind = ButtonKind.Solid, enabled = !working, small = true)
                NotesButton("Cancel", onCancel, kind = ButtonKind.Ghost, enabled = !working, small = true)
            }
        }
        InlineConfirm(
            visible = asking != null,
            title = "Move the site to ${settings.origin.substringAfter("://")}/@$asking?",
            text = "Links to the current address will stop working.",
            action = "Change address",
            busy = working,
            onConfirm = { asking?.let(onConfirm) },
            onCancel = onKeep,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private fun share(context: Context, name: String, url: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, name)
        .putExtra(Intent.EXTRA_TEXT, url)
    context.startActivity(Intent.createChooser(send, null))
}
