package notes.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.notes.listTime
import notes.app.notes.parkedMessage
import notes.app.ui.LocalTokens
import notes.app.ui.Type
import notes.core.api.UPGRADE_REQUIRED_MESSAGE
import notes.core.sync.SyncStatus

/** Sync & data's value in the list, as on iOS: "Up to date", "3 waiting", "Offline". */
internal fun syncSummary(status: SyncStatus, pending: Int): String {
    val waiting = if (pending > 0) "$pending waiting" else null
    return when (status) {
        SyncStatus.NotConnected -> waiting.orEmpty()
        SyncStatus.Syncing -> "Syncing…"
        is SyncStatus.Synced -> waiting ?: "Up to date"
        SyncStatus.Offline -> "Offline"
        SyncStatus.SignedOut -> "Signed out"
        is SyncStatus.Failed -> "Sync problem"
        is SyncStatus.UpgradeRequired -> "Update notesd"
    }
}

/** The page's status line: the summary, with what's waiting when that isn't already what it says. */
private fun statusLine(status: SyncStatus, pending: Int): String {
    val summary = syncSummary(status, pending)
    val waiting = "$pending waiting"
    return if (pending > 0 && summary != waiting) "$summary · $waiting" else summary
}

/** How sync is doing on this phone: its state, when it last finished, what went wrong, and Sync now. */
@Composable
internal fun SyncCard(graph: AppGraph) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val status by graph.sync.status.collectAsStateWithLifecycle()
    var pending by remember { mutableIntStateOf(0) }
    var parked by remember { mutableIntStateOf(0) }
    var lastSyncAt by remember { mutableStateOf<Long?>(null) }

    // Each change here, and each cycle's end, can change all three.
    LaunchedEffect(status) {
        graph.store.changes.onSubscription { emit(Unit) }.collect {
            pending = graph.store.pendingNotes()
            parked = graph.sync.parked()
            lastSyncAt = graph.sync.store.state().lastSyncAt
        }
    }

    Card {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Status", style = Type.rowTitle, color = t.ink, modifier = Modifier.weight(1f))
            Text(
                statusLine(status, pending),
                style = Type.rowMeta,
                color = t.ink2,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        lastSyncAt?.takeIf { it > 0 }?.let {
            Divider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Last synced", style = Type.rowTitle, color = t.ink, modifier = Modifier.weight(1f))
                Text(listTime(it), style = Type.rowMeta, color = t.ink2)
            }
        }
        val problem = when (val s = status) {
            is SyncStatus.UpgradeRequired -> UPGRADE_REQUIRED_MESSAGE
            is SyncStatus.Failed -> s.message
            else -> null
        }
        if (problem != null || parked > 0) {
            Divider()
            Column(Modifier.padding(16.dp)) {
                problem?.let { Text(it, style = Type.small, color = t.errInk) }
                if (parked > 0) Text(parkedMessage(parked), style = Type.small, color = t.errInk)
            }
        }
        if (parked > 0) {
            Divider()
            Action("Retry changes that couldn't sync", t.link, enabled = true) {
                scope.launch {
                    graph.sync.retryParked()
                    graph.scheduler.request()
                }
            }
        }
        Divider()
        Action("Sync now", t.link, enabled = status != SyncStatus.Syncing) { graph.scheduler.request() }
    }
}
