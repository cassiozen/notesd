package notes.app.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.ui.ButtonKind
import notes.app.ui.ConfirmDialog
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.Type
import notes.core.api.AiSettings
import notes.core.api.AiSettingsInput
import notes.core.api.ApiResult

private val AI_FIELDS = setOf("baseUrl", "apiKey", "model", "dailyTokenCap")

private enum class Busy { Save, Test }

/** What the fields hold, checked here first as the server would; field to message when they don't. */
internal fun aiInput(
    baseUrl: String,
    apiKey: String,
    model: String,
    cap: String,
    keySaved: Boolean
): Pair<AiSettingsInput?, Map<String, String>> {
    val errors = buildMap {
        if (baseUrl.isBlank()) put("baseUrl", "Enter the API's address.")
        if (!keySaved && apiKey.isBlank()) put("apiKey", "Paste the provider's API key.")
        if (model.isBlank()) put("model", "Enter the model's name.")
    }.toMutableMap()
    val tokens = cap.trim().ifEmpty { "0" }.toLongOrNull()?.takeIf { it >= 0 }
    if (tokens == null) errors["dailyTokenCap"] = "Enter a whole number of tokens."
    if (errors.isNotEmpty() || tokens == null) return null to errors
    return AiSettingsInput(baseUrl.trim(), model.trim(), apiKey.trim().ifEmpty { null }, tokens) to emptyMap()
}

/**
 * Connects the server's AI provider ([saved] null), or changes it: address, key, model and the daily cap. Test
 * calls the model with what's typed before anything's saved. A new key replaces the saved one, an empty field
 * keeps it.
 */
@Composable
internal fun AiDialog(graph: AppGraph, saved: AiSettings?, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(saved?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(saved?.model.orEmpty()) }
    var cap by remember { mutableStateOf((saved?.dailyTokenCap ?: AiSettings.DEFAULT_DAILY_TOKEN_CAP).toString()) }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var tested by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<Busy?>(null) }
    var disconnecting by remember { mutableStateOf(false) }

    fun input(): AiSettingsInput? {
        val (input, found) = aiInput(baseUrl, apiKey, model, cap, keySaved = saved != null)
        errors = found
        tested = null
        return input
    }

    fun save() {
        val body = input() ?: return
        busy = Busy.Save
        scope.launch {
            val r = graph.ai.save(body)
            busy = null
            if (r is ApiResult.Ok) onDismiss() else errors = mapOf(refusal(r, AI_FIELDS))
        }
    }

    fun test() {
        val body = input() ?: return
        busy = Busy.Test
        scope.launch {
            when (val r = graph.ai.test(body)) {
                is ApiResult.Ok -> {
                    val seconds = String.format(Locale.ROOT, "%.1f", r.value.latencyMs / 1000.0)
                    tested = "Connected. ${r.value.model} called a tool, in $seconds s."
                }

                is ApiResult.Rejected if r.error == "ai_test_failed" ->
                    errors = mapOf("form" to "The test failed: ${sentence(r.message)}")

                else -> errors = mapOf(refusal(r, AI_FIELDS))
            }
            busy = null
        }
    }

    fun disconnect() {
        busy = Busy.Save
        scope.launch {
            val r = graph.ai.remove()
            busy = null
            if (r is ApiResult.Ok) onDismiss() else errors = mapOf(refusal(r))
        }
    }

    fun edit(set: (String) -> Unit): (String) -> Unit = {
        set(it)
        errors = emptyMap()
        tested = null
    }

    val enabled = busy == null
    NotesDialog({ if (enabled) onDismiss() }, if (saved != null) "AI" else "Connect AI") {
        ScrollingBody {
            if (saved == null) {
                Text(
                    "Any OpenAI-compatible API works, with a model that supports tool calls. The key stays on this " +
                        "server.",
                    style = Type.body,
                    color = t.ink2
                )
                Spacer(Modifier.height(16.dp))
            }
            NotesField(
                value = baseUrl,
                onValueChange = edit { baseUrl = it },
                label = "API address",
                placeholder = "https://api.openai.com/v1",
                error = errors["baseUrl"],
                mono = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
            )
            Spacer(Modifier.height(12.dp))
            NotesField(
                value = apiKey,
                onValueChange = edit { apiKey = it },
                label = "API key",
                placeholder = if (saved != null) "Saved · …${saved.keyHint}" else "sk-…",
                error = errors["apiKey"],
                hint = if (saved != null) "Paste a new key to replace the saved one." else null,
                secret = true,
                mono = true,
                enabled = enabled
            )
            Spacer(Modifier.height(12.dp))
            NotesField(
                value = model,
                onValueChange = edit { model = it },
                label = "Model",
                placeholder = "gpt-4.1-mini",
                error = errors["model"],
                mono = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
            )
            Spacer(Modifier.height(12.dp))
            NotesField(
                value = cap,
                onValueChange = edit { cap = it },
                label = "Daily tokens per space",
                error = errors["dailyTokenCap"],
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            errors["form"]?.let { FormError(it) }
            tested?.let {
                Text(
                    it,
                    style = Type.small,
                    color = t.good,
                    modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Spacer(Modifier.height(20.dp))
            NotesButton(
                "Test",
                ::test,
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Chip,
                enabled = enabled,
                busy = busy == Busy.Test
            )
            DialogButtons(
                "Cancel",
                onDismiss,
                if (saved != null) "Save" else "Connect",
                ::save,
                busy = busy == Busy.Save,
                enabled = enabled
            )
            if (saved != null) {
                Spacer(Modifier.height(4.dp))
                Action("Disconnect…", t.danger, enabled = enabled) { disconnecting = true }
            }
        }
    }

    if (disconnecting) {
        ConfirmDialog(
            title = "Disconnect AI?",
            text = "Spaces' agents stop until it's connected again. The API key is removed from the server.",
            action = "Disconnect",
            danger = true,
            onDismiss = { disconnecting = false },
            onConfirm = ::disconnect
        )
    }
}
