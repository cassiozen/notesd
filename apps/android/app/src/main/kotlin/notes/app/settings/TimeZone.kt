package notes.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.NotesField
import notes.app.ui.Sheet
import notes.app.ui.Type
import notes.core.api.AccountSettings
import notes.core.api.ApiResult

/** Region/City zones, as a person picks one: not the legacy aliases (`US/Pacific`, `Etc/GMT+3`, `SystemV/…`). */
internal fun timeZones(current: String): List<String> {
    val all = ZoneId.getAvailableZoneIds().filter { id ->
        '/' in id && REGIONS.any { id.startsWith("$it/") }
    }.sorted()
    return if (current in all) all else listOf(current) + all
}

private val REGIONS =
    listOf("Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

/** A zone as it's read: `America/Sao_Paulo` is "America/Sao Paulo". */
internal fun zoneName(id: String) = id.replace('_', ' ')

/** The account's time zone, which agents use for "today". Synced to the account; saved as it's picked. */
@Composable
internal fun TimeZoneCard(graph: AppGraph) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<AccountSettings?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        when (val r = graph.api()?.accountSettings()) {
            is ApiResult.Ok -> settings = r.value
            null -> Unit
            else -> error = refusal(r).second
        }
    }

    fun choose(zone: String) {
        busy = true
        error = null
        scope.launch {
            when (val r = graph.api()?.setTimeZone(zone) ?: ApiResult.Unauthenticated) {
                is ApiResult.Ok -> settings = r.value
                else -> error = refusal(r).second
            }
            busy = false
        }
    }

    Spacer(Modifier.height(20.dp))
    Card {
        val current = settings
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = current != null && !busy, role = Role.Button) { picking = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Time zone", style = Type.rowTitle, color = t.ink, modifier = Modifier.weight(1f))
            Text(
                current?.let { zoneName(it.timeZone) } ?: if (error == null) "Loading…" else "",
                style = Type.rowMeta,
                color = t.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    error?.let { Text(it, style = Type.small, color = t.errInk, modifier = Modifier.padding(8.dp)) }
    Footnote(
        "Agents use it to know what day it is." +
            if (settings?.timeZoneSource == "auto") " Set from the first device that connected." else ""
    )

    val current = settings
    if (picking && current != null) {
        TimeZonePicker(current.timeZone, onDismiss = { picking = false }) {
            picking = false
            if (it != current.timeZone) choose(it)
        }
    }
}

/** Every zone, narrowed as a city or region is typed; the current one is ticked and scrolled to. */
@Composable
private fun TimeZonePicker(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val t = LocalTokens.current
    var query by remember { mutableStateOf("") }
    val zones = remember(current) { timeZones(current) }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = zones.filter { z -> zoneName(z).lowercase().let { name -> words.all { it in name } } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = zones.indexOf(current).coerceAtLeast(0))

    Sheet(onDismiss, "Time zone") {
        NotesField(
            value = query,
            onValueChange = { query = it },
            label = "Search",
            placeholder = "City or region",
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 480.dp), state = list) {
            items(shown, key = { it }) { zone ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { onPick(zone) }
                        .semantics { selected = zone == current }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(zoneName(zone), style = Type.body, color = t.ink, modifier = Modifier.weight(1f))
                    if (zone == current) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Check, null, Modifier.size(18.dp), tint = t.ink2)
                    }
                }
            }
        }
    }
}
