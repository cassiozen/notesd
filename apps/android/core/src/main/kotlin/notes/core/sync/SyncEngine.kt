package notes.core.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.MutationResult
import notes.core.api.RemoteNote
import notes.core.db.Database
import notes.core.db.LockKeyStore
import notes.core.db.Operation
import notes.core.db.SpaceStore
import notes.core.db.SyncEvent
import notes.core.db.SyncStore
import notes.core.images.ImageStore

/** How a cycle ended; the scheduler retries [Offline] and [Failed] with backoff. */
enum class SyncOutcome { Done, NotConnected, Offline, SignedOut, Failed }

sealed interface SyncStatus {
    data object NotConnected : SyncStatus

    data object Syncing : SyncStatus

    data class Synced(val at: Long) : SyncStatus

    data object Offline : SyncStatus

    data object SignedOut : SyncStatus

    data class Failed(val message: String) : SyncStatus
}

/**
 * One sync cycle: push the outbox, pull the feed, then push again whatever
 * the pull queued (conflicted and recovered copies). Spaces made or renamed
 * here go before the notes, so the server knows the space a note names;
 * deleted ones are tried first, to free their names, and again after the
 * notes moved out of them have gone (§7.7). One cycle at a time;
 * calling [sync] while one runs waits for it and runs another. Decisions use
 * the server's revisions and cursor only, never the device clock.
 */
class SyncEngine(
    db: Database,
    private val api: () -> ApiClient?,
    private val pageSize: Int = 100,
    clock: () -> Long = System::currentTimeMillis,
    /** Images added here, uploaded before the notes that reference them (§7.6). */
    private val images: ImageStore? = null
) {
    val store = SyncStore(db, clock)

    /** The locked-notes keyring as last synced, for unlocking offline (§10.1). */
    val lockKeys = LockKeyStore(db)

    /** The spaces, with this device's changes waiting to go (§7.7). */
    val spaces = SpaceStore(db, clock)
    private val lock = Mutex()

    /** Why a space change this cycle was refused for good, for [SyncStore.finished]. */
    private var rejection: String? = null

    /**
     * Spaces sent this cycle under a name a space deleted here still holds on the server: stored
     * there with a number for now, and sent again, to take the name, once the delete has gone.
     */
    private val parked = mutableSetOf<String>()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConnected)
    val status: StateFlow<SyncStatus> = _status

    private val _events =
        MutableSharedFlow<SyncEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<SyncEvent> = _events

    suspend fun sync(): SyncOutcome = lock.withLock {
        val client = api()
        if (client == null) {
            _status.value = SyncStatus.NotConnected
            return@withLock SyncOutcome.NotConnected
        }
        _status.value = SyncStatus.Syncing
        rejection = null
        parked.clear()
        val failure = cycle(client)
        // A dropped change isn't retried, so the cycle still counts as done, but the reason is kept,
        // beside the failure's own when the cycle failed too: the next cycle won't say it again.
        store.finished(listOfNotNull(rejection, failure?.message).joinToString(" ").ifEmpty { null })
        val outcome = failure?.outcome ?: SyncOutcome.Done
        _status.value = when (outcome) {
            SyncOutcome.Done -> SyncStatus.Synced(store.state().lastSyncAt ?: 0)
            SyncOutcome.Offline -> SyncStatus.Offline
            SyncOutcome.SignedOut -> SyncStatus.SignedOut
            SyncOutcome.NotConnected -> SyncStatus.NotConnected
            SyncOutcome.Failed -> SyncStatus.Failed(failure?.message ?: "Sync failed")
        }
        outcome
    }

    private class Stop(val outcome: SyncOutcome, val message: String?)

    private suspend fun cycle(api: ApiClient): Stop? {
        // Deletes first too, so a name deleted and reused here is free; one refused waits for the notes.
        pushSpaces(api, deletes = true, final = false)?.let { return it }
        pushSpaces(api, deletes = false)?.let { return it }
        push(api)?.let { return it }
        pushSpaces(api, deletes = true)?.let { return it }
        // The names those deletes freed, for the spaces made here meanwhile.
        if (parked.isNotEmpty()) {
            parked.clear()
            pushSpaces(api, deletes = false)?.let { return it }
        }
        pull(api)?.let { return it }
        fetchSpaces(api)?.let { return it }
        keyring(api)?.let { return it }
        return if (store.nextOperation() != null) push(api) else null
    }

    /**
     * Sends the spaces created or renamed here, or ([deletes]) the ones deleted here. Not [final], a delete
     * refused for holding notes stays pending: notes moved out of it here may not have gone yet.
     */
    private suspend fun pushSpaces(api: ApiClient, deletes: Boolean, final: Boolean = true): Stop? {
        val pending = spaces.pending()
        for (change in pending.filter { it.delete == deletes }) {
            if (change.delete) {
                when (val r = api.deleteSpace(change.id)) {
                    is ApiResult.Ok -> spaces.deleted(change)

                    // Notes reached it from another device (or it's the default): it stays.
                    is ApiResult.Conflict -> if (final) {
                        spaces.dropped(change)
                        rejection = "“${change.name}” wasn’t deleted: notes reached it from another device."
                    }

                    is ApiResult.Rejected if r.status < 500 -> {
                        spaces.dropped(change)
                        rejection = "“${change.name}” wasn’t deleted: ${r.message.ifEmpty { r.error }}"
                    }

                    else -> return stop(r)
                }
            } else {
                // Deleted here, its name not yet free on the server (notes moved out of it go later):
                // sent now so notes can name it, but kept waiting to take the name after the delete.
                val held = pending.any { it.delete && it.name.equals(change.name, ignoreCase = true) }
                if (held && change.id in parked) continue
                when (val r = api.putSpace(change.id, change.name, change.icon)) {
                    is ApiResult.Ok -> if (held) parked += change.id else spaces.sent(change, r.value)

                    is ApiResult.Rejected if r.status < 500 -> {
                        spaces.dropped(change)
                        rejection = "The space “${change.name}” wasn’t saved: ${r.message.ifEmpty { r.error }}"
                    }

                    else -> return stop(r)
                }
            }
        }
        return null
    }

    /** The list is small and has no change feed: fetched whole every cycle, like the keyring. */
    private suspend fun fetchSpaces(api: ApiClient): Stop? = when (val r = api.spaces()) {
        is ApiResult.Ok -> {
            spaces.replace(r.value)
            null
        }

        else -> stop(r)
    }

    /**
     * The keyring is tiny and has no change feed: fetched whole every cycle.
     * A change made on this device while the fetch was out is newer; the
     * fetched copy is dropped, and the next cycle brings the server's.
     */
    private suspend fun keyring(api: ApiClient): Stop? {
        val since = lockKeys.generation()
        return when (val result = api.lockKeys()) {
            is ApiResult.Ok -> {
                lockKeys.replaceIfUnchanged(result.value, since)
                null
            }

            else -> stop(result)
        }
    }

    /** Uploads every image waiting on this device; a Stop if one couldn't go. */
    private suspend fun uploadImages(api: ApiClient): Stop? {
        val store = images ?: return null
        return when (val r = store.upload(api)) {
            is ApiResult.Ok -> null
            is ApiResult.Rejected -> Stop(SyncOutcome.Failed, "The server had a problem (${r.status}).")
            else -> stop(r)
        }
    }

    private suspend fun push(api: ApiClient): Stop? {
        // Every waiting image goes, whether or not a note operation follows.
        uploadImages(api)?.let { return it }
        while (true) {
            val next = store.nextOperation() ?: return null
            val op = store.prepare(next) ?: continue
            // For each operation, once it's in hand: an image's pending row is written
            // before the note showing it is saved, so this sends every image the
            // operation can show, including ones added while an earlier one was in flight.
            uploadImages(api)?.let { return it }
            // Likewise a space made since the cycle began: the server files a note naming an
            // unknown space in the default.
            pushSpaces(api, deletes = false)?.let { return it }
            val result = send(api, op)
            when (result) {
                is ApiResult.Ok -> store.sent(op, result.value)

                is ApiResult.Conflict -> {
                    val current = result.current?.let { decodeNote(it.toString()) }
                    if (current == null) store.rejected(op, result.message) else emit(store.conflict(op, current))
                }

                is ApiResult.Rejected -> {
                    if (result.status >=
                        500
                    ) {
                        return Stop(SyncOutcome.Failed, "The server had a problem (${result.status}).")
                    }
                    store.rejected(op, result.message.ifEmpty { result.error })
                }

                else -> return stop(result)
            }
        }
    }

    private suspend fun send(api: ApiClient, op: Operation): ApiResult<MutationResult> = when (op.type) {
        "create" -> api.createNote(
            buildJsonObject {
                put("operationId", op.operationId)
                put("id", op.noteId)
                put("title", op.title)
                put("content", op.content)
                op.createdAt?.let { put("createdAt", it) }
                op.updatedAt?.let { put("updatedAt", it) }
                op.conflictOf?.let { put("conflictOf", it) }
                op.spaceId?.let { put("spaceId", it) }
                op.imageIds?.let { ids -> put("imageIds", JsonArray(ids.map { JsonPrimitive(it) })) }
            }
        )

        "update" -> api.updateNote(
            op.noteId,
            buildJsonObject {
                put("operationId", op.operationId)
                put("baseRevision", op.baseRevision)
                put("title", op.title)
                put("content", op.content)
                op.updatedAt?.let { put("updatedAt", it) }
                op.spaceId?.let { put("spaceId", it) }
                op.imageIds?.let { ids -> put("imageIds", JsonArray(ids.map { JsonPrimitive(it) })) }
            }
        )

        else -> api.deleteNote(
            op.noteId,
            buildJsonObject {
                put("operationId", op.operationId)
                put("baseRevision", op.baseRevision)
            }
        )
    }

    private suspend fun pull(api: ApiClient): Stop? {
        var restarted = false
        while (true) {
            val state = store.state()
            when (val result = api.sync(state.cursor, pageSize, state.generation)) {
                is ApiResult.Ok -> {
                    emit(store.applyPage(result.value))
                    if (!result.value.hasMore) return null
                }

                is ApiResult.Conflict if result.code == "generation_mismatch" || result.code == "cursor_ahead" -> {
                    // Restored from a backup. Once per cycle: a second time means something else is wrong.
                    if (restarted) return Stop(SyncOutcome.Failed, result.message)
                    restarted = true
                    store.startFullSync()
                }

                is ApiResult.Rejected if result.status < 500 -> return Stop(
                    SyncOutcome.Failed,
                    result.message.ifEmpty {
                        result.error
                    }
                )

                else -> return stop(result)
            }
        }
    }

    private fun stop(result: ApiResult<*>): Stop = when (result) {
        ApiResult.Unauthenticated -> Stop(SyncOutcome.SignedOut, null)
        is ApiResult.Unreachable -> Stop(SyncOutcome.Offline, null)
        is ApiResult.RateLimited -> Stop(SyncOutcome.Offline, null)
        is ApiResult.Rejected -> Stop(SyncOutcome.Failed, "The server had a problem (${result.status}).")
        is ApiResult.Conflict -> Stop(SyncOutcome.Failed, result.message)
        is ApiResult.Ok -> error("not a failure")
    }

    private fun emit(events: List<SyncEvent>) {
        for (e in events) _events.tryEmit(e)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun decodeNote(s: String): RemoteNote? = try {
            json.decodeFromString<RemoteNote>(s)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
