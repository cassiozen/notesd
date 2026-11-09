package notes.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.R
import notes.app.agent.providerHost
import notes.app.ui.ButtonKind
import notes.app.ui.ConfirmDialog
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.Type
import notes.core.api.AiSettings
import notes.core.api.ApiResult
import notes.core.api.EmailSettings

private const val API_KEYS = "https://resend.com/api-keys"

/** One integration as Integrations lists it. Null [on]: still loading or failed. */
private class Integration(
    val name: String,
    val icon: Painter,
    /** What connecting it lets you do, in a line. */
    val pitch: String,
    /** Null while unknown; else whether it's connected, with what it shows then. */
    val on: String?,
    val known: Boolean,
    /** Whether this account may connect, change or disconnect it (server-wide ones are admins'). */
    val editable: Boolean,
    val open: () -> Unit
)

/** The Integrations row's value in the list: off, the one connected by name, or how many are. */
internal fun integrationsValue(email: EmailSettings?, ai: AiSettings?): String {
    if (email == null || ai == null) return ""
    val on = listOfNotNull("Resend".takeIf { email.configured }, "AI".takeIf { ai.configured })
    return when (on.size) {
        0 -> "Off"
        1 -> on[0]
        else -> "${on.size}"
    }
}

/** What's connected, then what can be; each connects in its own dialog. */
@Composable
internal fun IntegrationsPage(graph: AppGraph) {
    val t = LocalTokens.current
    val email by graph.email.settings.collectAsStateWithLifecycle()
    val ai by graph.ai.cache.value.collectAsStateWithLifecycle()
    val aiFailed by graph.ai.cache.failed.collectAsStateWithLifecycle()
    var attempt by remember { mutableIntStateOf(0) }
    var emailFailed by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        launch { graph.ai.cache.refresh() }
        emailFailed = false
        emailFailed = !graph.email.refresh() && graph.email.settings.value == null
    }

    val mail = email
    val provider = ai
    val integrations = listOf(
        Integration(
            "Resend",
            rememberVectorPainter(Icons.Mail),
            "Email notes to anyone, from an address on your own domain.",
            mail?.takeIf { it.configured }?.let { "Sending as ${it.from}" },
            known = mail != null,
            editable = mail?.editable == true
        ) { open = if (mail?.configured == true) "resend" else "resend-connect" },
        Integration(
            "AI",
            painterResource(R.drawable.trait_agent),
            "Give spaces an agent that tags, titles and tidies notes, through any OpenAI-compatible API.",
            provider?.takeIf { it.configured }?.let { "${it.model} at ${providerHost(it.baseUrl)}" },
            known = provider != null,
            editable = provider?.editable == true
        ) { open = if (provider?.configured == true) "ai" else "ai-connect" }
    )
    val connected = integrations.filter { it.known && it.on != null }
    val available = integrations.filter { it.known && it.on == null }

    if ((mail == null && emailFailed) || (provider == null && aiFailed)) {
        Card {
            Info("Couldn't load integrations.", "Check the connection to the server.")
            Divider()
            Action("Try again", t.link, enabled = true) { attempt++ }
        }
        Spacer(Modifier.height(16.dp))
    } else if (connected.isEmpty() && available.isEmpty()) {
        Card {
            Text("Loading…", style = Type.rowMeta, color = t.ink3, modifier = Modifier.padding(16.dp))
        }
    }
    if (connected.isNotEmpty()) {
        GroupLabel("Connected")
        Card {
            connected.forEachIndexed { i, it ->
                if (i > 0) Divider()
                IntegrationRow(it.icon, it.name, it.on.orEmpty()) {
                    if (it.editable) {
                        NotesButton(
                            "Manage",
                            it.open,
                            Modifier.semantics { contentDescription = "Manage ${it.name}" },
                            kind = ButtonKind.Chip,
                            small = true
                        )
                    } else {
                        Pill("On")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (available.isNotEmpty()) {
        GroupLabel(if (connected.isEmpty()) "Available" else "More to connect")
        Card {
            available.forEachIndexed { i, it ->
                if (i > 0) Divider()
                IntegrationRow(it.icon, it.name, it.pitch + if (it.editable) "" else " An admin can connect it.") {
                    if (it.editable) {
                        NotesButton(
                            "Connect",
                            it.open,
                            Modifier.semantics { contentDescription = "Connect ${it.name}" },
                            small = true
                        )
                    }
                }
            }
        }
    }

    // As opened: connecting changes what's saved, and the dialog stays until it's done.
    val close = { open = null }
    when (open) {
        "resend-connect" -> ResendConnectDialog(graph, close)
        "resend" -> mail?.let { ResendManageDialog(graph, it, close) }
        "ai-connect" -> AiDialog(graph, null, close)
        "ai" -> provider?.let { AiDialog(graph, it, close) }
    }
}

@Composable
internal fun GroupLabel(text: String) {
    Text(
        text,
        style = Type.label,
        color = LocalTokens.current.ink3,
        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp).semantics { heading() }
    )
}

@Composable
private fun IntegrationRow(icon: Painter, name: String, detail: String, trailing: @Composable () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(t.ink),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = t.bg)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.rowTitle, color = t.ink)
            Text(detail, style = Type.rowMeta, color = t.ink2)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** The server's own check, made here too: the key isn't sent until the sender's chosen. */
internal fun keyError(key: String): String? = when {
    key.isEmpty() -> "Paste your Resend API key."
    !key.startsWith("re_") || key.length < 8 || key.any { it.isWhitespace() } -> "A Resend API key starts with re_."
    else -> null
}

/** A sender's display name and address, as "Name <a@b.c>" puts them together. */
internal object Sender {
    // RFC 5322 specials in a display name need quotes, or the server refuses the address.
    private val specials = Regex("""[()<>\[\]:;@\\,."]""")
    private val angled = Regex("""^\s*(.*?)\s*<([^<>]+)>\s*$""")

    fun format(name: String, address: String): String {
        val n = name.trim()
        val a = address.trim()
        if (n.isEmpty()) return a
        if (!specials.containsMatchIn(n)) return "$n <$a>"
        val quoted = n.replace(Regex("""["\\]""")) { "\\" + it.value }
        return "\"$quoted\" <$a>"
    }

    fun parse(from: String): Pair<String, String> {
        val m = angled.find(from) ?: return "" to from.trim()
        var name = m.groupValues[1]
        if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) {
            name = name.substring(1, name.length - 1).replace(Regex("""\\(.)""")) { it.groupValues[1] }
        }
        return name to m.groupValues[2].trim()
    }
}

/** The server's refusal, on the field it's about when it's one of [fields]; anything else is the form's. */
internal fun refusal(r: ApiResult<*>, fields: Set<String> = setOf("apiKey", "from")): Pair<String, String> {
    val field = (r as? ApiResult.Rejected)?.takeIf { it.status == 400 }?.field
    if (r is ApiResult.Rejected && field in fields) return field!! to sentence(r.message)
    return "form" to if (r is ApiResult.Unreachable) "Can't reach the server." else "Something went wrong. Try again."
}

/** A server message as a sentence: capitalised, with its full stop. */
internal fun sentence(message: String): String {
    val text = message.replaceFirstChar { it.uppercase() }
    return if (text.endsWith('.')) text else "$text."
}

private enum class Step { Key, Sender, Done }

/** Key, then sender, then done. Nothing's saved until the sender's chosen. */
@Composable
private fun ResendConnectDialog(graph: AppGraph, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.Key) }
    var apiKey by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }

    fun next() {
        val error = keyError(apiKey.trim())
        errors = error?.let { mapOf("apiKey" to it) }.orEmpty()
        if (error == null) step = Step.Sender
    }

    fun connect() {
        if (address.isBlank()) {
            errors = mapOf("from" to "Enter the address emails come from.")
            return
        }
        errors = emptyMap()
        busy = true
        scope.launch {
            val r = graph.email.save(Sender.format(name, address), apiKey.trim())
            busy = false
            if (r is ApiResult.Ok) {
                step = Step.Done
            } else {
                errors = mapOf(refusal(r))
                if ("apiKey" in errors) step = Step.Key
            }
        }
    }

    val title = when (step) {
        Step.Key -> "Connect Resend"
        Step.Sender -> "Who it's from"
        Step.Done -> "Ready to send"
    }
    NotesDialog({ if (!busy) onDismiss() }, title) {
        ScrollingBody {
            when (step) {
                Step.Key -> {
                    StepDots(0)
                    Text(
                        "Notes are sent through your Resend account, with an API key from it.",
                        style = Type.body,
                        color = t.ink2
                    )
                    Spacer(Modifier.height(16.dp))
                    NotesField(
                        value = apiKey,
                        onValueChange = {
                            // A whole key pasted in needs no Continue.
                            val pasted = apiKey.isEmpty() && it.length > 1 && keyError(it.trim()) == null
                            apiKey = it
                            errors = emptyMap()
                            if (pasted) step = Step.Sender
                        },
                        label = "API key",
                        placeholder = "re_…",
                        error = errors["apiKey"],
                        hint = "With sending access.",
                        secret = true,
                        mono = true
                    )
                    Text(
                        "Create an API key in Resend ↗",
                        style = Type.label,
                        color = t.link,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clickable(role = Role.Button) { uri.openUri(API_KEYS) }
                            .padding(vertical = 6.dp)
                    )
                    DialogButtons("Cancel", onDismiss, "Continue", ::next)
                }

                Step.Sender -> {
                    StepDots(1)
                    SenderFields(name, address, { name = it }, {
                        address = it
                        errors = emptyMap()
                    }, errors["from"], enabled = !busy)
                    errors["form"]?.let { FormError(it) }
                    DialogButtons("Back", { step = Step.Key }, if (busy) "Connecting…" else "Connect", ::connect, busy)
                }

                Step.Done -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .padding(vertical = 8.dp)
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(t.goodBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Send, null, Modifier.size(30.dp), tint = t.good)
                    }
                    Text(
                        "Anyone on this server can now email a note: share it and choose Email.",
                        style = Type.body,
                        color = t.ink2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(20.dp))
                    NotesButton("Done", onDismiss, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The sender and key as saved; a new key replaces the old one, an empty field keeps it. */
@Composable
private fun ResendManageDialog(graph: AppGraph, saved: EmailSettings, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val initial = remember { Sender.parse(saved.from) }
    var name by remember { mutableStateOf(initial.first) }
    var address by remember { mutableStateOf(initial.second) }
    var apiKey by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf(false) }

    fun save() {
        errors = buildMap {
            if (apiKey.isNotBlank()) keyError(apiKey.trim())?.let { put("apiKey", it) }
            if (address.isBlank()) put("from", "Enter the address emails come from.")
        }
        if (errors.isNotEmpty()) return
        busy = true
        scope.launch {
            val r = graph.email.save(Sender.format(name, address), apiKey.trim().ifEmpty { null })
            busy = false
            if (r is ApiResult.Ok) onDismiss() else errors = mapOf(refusal(r))
        }
    }

    fun disconnect() {
        busy = true
        scope.launch {
            val r = graph.email.remove()
            busy = false
            if (r is ApiResult.Ok) onDismiss() else errors = mapOf(refusal(r))
        }
    }

    NotesDialog({ if (!busy) onDismiss() }, "Resend") {
        ScrollingBody {
            SenderFields(name, address, { name = it }, {
                address = it
                errors = emptyMap()
            }, errors["from"], enabled = !busy)
            Spacer(Modifier.height(12.dp))
            NotesField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    errors = emptyMap()
                },
                label = "API key",
                placeholder = "Saved · …${saved.keyHint}",
                error = errors["apiKey"],
                hint = "Paste a new key to replace the saved one.",
                secret = true,
                mono = true,
                enabled = !busy
            )
            errors["form"]?.let { FormError(it) }
            DialogButtons("Cancel", onDismiss, if (busy) "Saving…" else "Save", ::save, busy)
            Spacer(Modifier.height(4.dp))
            Action("Disconnect…", t.danger, enabled = !busy) { disconnecting = true }
        }
    }

    if (disconnecting) {
        ConfirmDialog(
            title = "Disconnect Resend?",
            text = "Nobody on this server can email notes until it's connected again. The API key is removed " +
                "from the server.",
            action = "Disconnect",
            danger = true,
            onDismiss = { disconnecting = false },
            onConfirm = ::disconnect
        )
    }
}

/** A dialog's content, scrolling once it's taller than the window: a small phone, or the keyboard up. */
@Composable
internal fun ScrollingBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), content = content)
}

/** Name and address, and how they'll look in a recipient's inbox as they're typed. */
@Composable
private fun SenderFields(
    name: String,
    address: String,
    onName: (String) -> Unit,
    onAddress: (String) -> Unit,
    error: String?,
    enabled: Boolean
) {
    val t = LocalTokens.current
    val shown = name.trim().ifEmpty { address.trim() }.ifEmpty { "Your name" }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, t.hair, RoundedCornerShape(16.dp))
            .background(t.chip)
            .padding(14.dp)
            // A picture of what's typed below; the fields say it already.
            .clearAndSetSemantics {}
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(t.on),
            contentAlignment = Alignment.Center
        ) {
            Text(shown.take(1).uppercase(), style = Type.title.copy(fontSize = 18.sp), color = t.ink)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    shown,
                    style = Type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = t.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (name.isNotBlank() && address.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        address.trim(),
                        style = Type.small,
                        color = t.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            Text("Saturday market", style = Type.label.copy(fontSize = 13.sp), color = t.ink)
            Text(
                "Sourdough, ripe tomatoes and something green. Ask about the late plums.",
                style = Type.small,
                color = t.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    NotesField(
        value = name,
        onValueChange = onName,
        label = "Name",
        placeholder = "Your name",
        hint = "Optional. Shown beside the address.",
        enabled = enabled
    )
    Spacer(Modifier.height(12.dp))
    NotesField(
        value = address,
        onValueChange = onAddress,
        label = "Address",
        placeholder = "notes@example.com",
        error = error,
        hint = "On a domain you've verified in Resend.",
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
    )
}

@Composable
internal fun DialogButtons(
    back: String,
    onBack: () -> Unit,
    action: String,
    onAction: () -> Unit,
    busy: Boolean = false,
    /** False while something else in the dialog is busy. */
    enabled: Boolean = !busy
) {
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NotesButton(back, onBack, Modifier.weight(1f), kind = ButtonKind.Chip, enabled = enabled)
        NotesButton(action, onAction, Modifier.weight(1f), enabled = enabled || busy, busy = busy)
    }
}

/** Progress pills, as onboarding's: the current step is wide, done ones are filled. */
@Composable
private fun StepDots(current: Int, total: Int = 2) {
    val t = LocalTokens.current
    Row(
        Modifier.padding(bottom = 12.dp).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        for (i in 0 until total) {
            Box(
                Modifier
                    .height(4.dp)
                    .width(if (i == current) 28.dp else 12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (i <= current) t.solid else t.hair)
            )
        }
    }
}
