package notes.app.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import notes.core.db.NoteFilter
import notes.core.db.NoteStore
import notes.core.db.NoteSummary
import notes.core.db.SearchHit
import notes.core.db.SpaceScope
import notes.core.db.SyncEvent
import notes.core.sync.SyncEngine
import notes.core.sync.SyncStatus

data class ListState(
    val loaded: Boolean = false,
    val query: String = "",
    val filter: NoteFilter? = null,
    val notes: List<NoteSummary> = emptyList(),
    val hits: List<SearchHit> = emptyList(),
    /** Searching: the matches in every other space (§7.7), listed once asked for. */
    val elsewhere: List<SearchHit> = emptyList(),
    /** How many there are: [elsewhere] stops at a page. */
    val elsewhereCount: Int = 0
)

/** Notes that are only on this phone because sync can't get through; [error] when the server refused. */
data class NotSynced(val pending: Int, val error: String?)

/**
 * Sync is meant to go unnoticed, so this is null unless changes are stuck. Syncing is skipped
 * rather than clearing it: the scheduler's retries would otherwise make it flicker.
 */
fun notSynced(status: Flow<SyncStatus>, pending: Flow<Int>): Flow<NotSynced?> =
    combine(status.filter { it != SyncStatus.Syncing }, pending) { s, n ->
        when {
            n == 0 -> null
            s == SyncStatus.Offline -> NotSynced(n, null)
            s is SyncStatus.Failed -> NotSynced(n, s.message)
            else -> null
        }
    }.distinctUntilChanged()

@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModel(
    private val store: NoteStore,
    val deletions: Deletions,
    private val engine: SyncEngine,
    private val filter: MutableStateFlow<NoteFilter?>,
    private val space: StateFlow<String>,
    private val requestSync: () -> Unit
) : ViewModel() {
    val events: SharedFlow<SyncEvent> get() = engine.events

    val notSynced: StateFlow<NotSynced?> =
        notSynced(engine.status, store.changes.onSubscription { emit(Unit) }.mapLatest { store.pendingNotes() })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun syncNow() = requestSync()

    private val query = MutableStateFlow("")

    private class Query(val q: String, val filter: NoteFilter?, val space: String, val pending: Set<String>)

    // Re-queried on every write: local and cheap at this scale. Notes waiting out the undo window are hidden.
    val state: StateFlow<ListState> =
        combine(
            store.changes.onSubscription { emit(Unit) },
            query,
            filter,
            space,
            deletions.pending
        ) { _, q, f, s, pending -> Query(q, f, s, pending.toSet()) }
            .mapLatest { r ->
                if (r.q.isBlank()) {
                    ListState(true, r.q, r.filter, notes = store.list(r.filter, r.space).filter { it.id !in r.pending })
                } else {
                    val elsewhere = store.search(r.q, scope = SpaceScope.Except(r.space))
                    val shown = elsewhere.filter { it.id !in r.pending }
                    ListState(
                        true,
                        r.q,
                        r.filter,
                        hits = store.search(r.q, r.filter, scope = SpaceScope.In(r.space)).filter {
                            it.id !in r.pending
                        },
                        elsewhere = shown,
                        elsewhereCount = store.countElsewhere(r.q, r.space) - (elsewhere.size - shown.size)
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState())

    fun search(q: String) {
        query.value = q
    }

    fun filter(f: NoteFilter?) {
        filter.value = f
    }
}
