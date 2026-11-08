package notes.core.sync

import kotlin.coroutines.cancellation.CancellationException
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
import notes.core.api.ApiResult
import notes.core.api.MutationResult
import notes.core.api.PERMANENT_ERRORS
import notes.core.api.PendingImages
import notes.core.api.RemoteNote
import notes.core.api.SyncApi
import notes.core.api.UPGRADE_REQUIRED_MESSAGE
import notes.core.api.unexpectedMessage
import notes.core.db.Database
import notes.core.db.LockKeyStore
import notes.core.db.MOVE
import notes.core.db.Operation
import notes.core.db.SpaceStore
import notes.core.db.SyncEvent
import notes.core.db.SyncStore
import notes.core.text.nowMillis

/**
 * How a cycle ended; the scheduler retries [Offline] and [Failed] with backoff. [UpgradeRequired]
 * isn't retried: every later cycle of this engine ends the same way without asking the server.
 */
enum class SyncOutcome { Done, NotConnected, Offline, SignedOut, UpgradeRequired, Failed }

sealed interface SyncStatus {
    data object NotConnected : SyncStatus

    data object Syncing : SyncStatus

    data class Synced(val at: Long) : SyncStatus

    data object Offline : SyncStatus

    data object SignedOut : SyncStatus

    data class Failed(val message: String) : SyncStatus

    /** The server needs a newer app: nothing more is sent or fetched, nothing is dropped. */
    data class UpgradeRequired(val minVersion: String?) : SyncStatus
}

/**
 * One sync cycle: push the outbox, pull the feed, then push again whatever
 * the pull queued (conflicted and recovered copies). Spaces made or renamed
 * here go before the notes, so the server knows the space a note names;
 * deleted ones are tried first, to free their names, and again after the
 * notes moved out of them have gone (§7.7). One cycle at a time;
 * calling [sync] while one runs waits for it and runs another. Decisions use
 * the server's revisions and cursor only, never the device clock.
 *
 * A queued change the server answers with something notesd never sends is
 * kept, and counted; after [notes.core.db.PARK_AFTER] such cycles it's parked,
 * skipped so the ones behind it go, until [open], [retryParked], or a cycle
 * that sends something.
 */
class SyncEngine(
    db: Database,
    private val api: () -> SyncApi?,
    private val pageSize: Int = 100,
    clock: () -> Long = ::nowMillis,
    /** Images added here, uploaded before the notes that reference them (§7.6). */
    private val images: PendingImages? = null
) {
    val store = SyncStore(db, clock)

    /** The locked-notes keyring as last synced, for unlocking offline (§10.1). */
    val lockKeys = LockKeyStore(db)

    /** The spaces, with this device's changes waiting to go (§7.7). */
    val spaces = SpaceStore(db, clock)
    private val lock = Mutex()

    /** Why changes this cycle were refused for good, for [SyncStore.finished]. */
    private val rejections = mutableListOf<String>()

    /** A change went through this cycle: parked ones get another try. */
    private var pushed = false

    private var opened = false

    /** The server asked for a newer app; held until the app restarts with a new engine. */
    private var upgrade: ApiResult.UpgradeRequired? = null

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

    /**
     * At start: queues the edits older versions dropped and gives parked changes another try.
     * Also run by the first [sync]; again does nothing.
     */
    suspend fun open() = lock.withLock { openOnce() }

    private suspend fun openOnce() {
        if (opened) return
        store.requeueDirty()
        store.unpark()
        opened = true
    }

    /** Changes set aside after failing again and again; their notes are still here and still dirty. */
    suspend fun parked(): Int = store.parked()

    /** The user asked: parked changes back in line. The caller syncs. */
    suspend fun retryParked() = store.unpark()

    suspend fun sync(): SyncOutcome = lock.withLock {
        upgrade?.let {
            _status.value = SyncStatus.UpgradeRequired(it.minVersion)
            return@withLock SyncOutcome.UpgradeRequired
        }
        val client = api()
        if (client == null) {
            _status.value = SyncStatus.NotConnected
            return@withLock SyncOutcome.NotConnected
        }
        _status.value = SyncStatus.Syncing
        rejections.clear()
        pushed = false
        parked.clear()
        val failure = try {
            openOnce()
            cycle(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A storage error, say: Failed and retried like any other, not left showing Syncing.
            Stop(SyncOutcome.Failed, e.message ?: "Sync failed")
        }
        // A dropped change isn't retried, so the cycle still counts as done, but the reason is kept,
        // beside the failure's own when the cycle failed too: the next cycle won't say it again.
        val lastSyncAt = try {
            if (pushed) store.unpark()
            store.finished((rejections + listOfNotNull(failure?.message)).joinToString(" ").ifEmpty { null })
            store.state().lastSyncAt
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The storage that just failed may refuse this too; the status below still says why.
            null
        }
        val outcome = failure?.outcome ?: SyncOutcome.Done
        _status.value = when (outcome) {
            SyncOutcome.Done -> SyncStatus.Synced(lastSyncAt ?: 0)
            SyncOutcome.Offline -> SyncStatus.Offline
            SyncOutcome.SignedOut -> SyncStatus.SignedOut
            SyncOutcome.UpgradeRequired -> SyncStatus.UpgradeRequired(failure?.minVersion)
            SyncOutcome.NotConnected -> SyncStatus.NotConnected
            SyncOutcome.Failed -> SyncStatus.Failed(failure?.message ?: "Sync failed")
        }
        outcome
    }

    private class Stop(val outcome: SyncOutcome, val message: String?, val minVersion: String? = null)

    /** Refused for good: dropped, and the user told. */
    private fun reject(message: String) {
        rejections += message
        _events.tryEmit(SyncEvent.Rejected(message))
    }

    private suspend fun cycle(api: SyncApi): Stop? {
        // Deletes first too, so a name deleted and reused here is free; one refused waits for the notes.
        pushSpaces(api, deletes = true, final = false)?.let { return it }
        pushSpaces(api, deletes = false)?.let { return it }
        // Before the notes: which spaces are shared, so each change goes to its space's file (§7.9). An odd answer
        // (a portal's page) doesn't stop the cycle here: the changes get their attempts, and parking, as before.
        fetchSpaces(api)?.takeIf { it.outcome != SyncOutcome.Failed }?.let { return it }
        invites(api)?.takeIf { it.outcome != SyncOutcome.Failed }?.let { return it }
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
    private suspend fun pushSpaces(api: SyncApi, deletes: Boolean, final: Boolean = true): Stop? {
        val pending = spaces.pending()
        for (change in pending.filter { it.delete == deletes }) {
            if (change.delete) {
                when (val r = api.deleteSpace(change.id)) {
                    is ApiResult.Ok -> {
                        spaces.deleted(change)
                        pushed = true
                    }

                    // Notes reached it from another device (or it's the default): it stays.
                    is ApiResult.Conflict -> if (final) {
                        spaces.dropped(change)
                        reject("“${change.name}” wasn’t deleted: notes reached it from another device.")
                    }

                    is ApiResult.Rejected if r.error in PERMANENT_ERRORS -> {
                        spaces.dropped(change)
                        reject("“${change.name}” wasn’t deleted: ${r.message.ifEmpty { r.error }}")
                    }

                    else -> return unexpected(r) { spaces.attempted(change) } ?: stop(r)
                }
            } else {
                // Deleted here, its name not yet free on the server (notes moved out of it go later):
                // sent now so notes can name it, but kept waiting to take the name after the delete.
                val held = pending.any { it.delete && it.name.equals(change.name, ignoreCase = true) }
                if (held && change.id in parked) continue
                when (val r = api.putSpace(change.id, change.name, change.icon)) {
                    is ApiResult.Ok -> {
                        if (held) parked += change.id else spaces.sent(change, r.value)
                        pushed = true
                    }

                    is ApiResult.Rejected if r.error in PERMANENT_ERRORS -> {
                        spaces.dropped(change)
                        reject("The space “${change.name}” wasn’t saved: ${r.message.ifEmpty { r.error }}")
                    }

                    else -> return unexpected(r) { spaces.attempted(change) } ?: stop(r)
                }
            }
        }
        return null
    }

    /**
     * The list is small and has no change feed: fetched whole every cycle, like the keyring. A shared space new to
     * it gets a feed; one gone from it takes its notes off the device (§7.9).
     */
    private suspend fun fetchSpaces(api: SyncApi): Stop? = when (val r = api.spaces()) {
        is ApiResult.Ok -> {
            spaces.replace(r.value)
            store.syncFeeds()
            null
        }

        else -> stop(r)
    }

    private suspend fun invites(api: SyncApi): Stop? = when (val r = api.invites()) {
        is ApiResult.Ok -> {
            spaces.replaceInvites(r.value)
            null
        }

        // A server from before shared spaces: none.
        is ApiResult.Rejected if r.error == "no_such_endpoint" -> null

        else -> stop(r)
    }

    /**
     * The keyring is tiny and has no change feed: fetched whole every cycle.
     * A change made on this device while the fetch was out is newer; the
     * fetched copy is dropped, and the next cycle brings the server's.
     */
    private suspend fun keyring(api: SyncApi): Stop? {
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
    private suspend fun uploadImages(api: SyncApi): Stop? {
        val store = images ?: return null
        return when (val r = store.upload(api)) {
            is ApiResult.Ok -> {
                r.value.forEach(::reject)
                null
            }

            else -> stop(r)
        }
    }

    private suspend fun push(api: SyncApi): Stop? {
        // Every waiting image goes, whether or not a note operation follows.
        uploadImages(api)?.let { return it }
        while (true) {
            val next = store.nextOperation() ?: return null
            var op = store.prepare(next) ?: continue
            // For each operation, once it's in hand: an image's pending row is written
            // before the note showing it is saved, so this sends every image the
            // operation can show, including ones added while an earlier one was in flight.
            uploadImages(api)?.let { return it }
            // Likewise a space made since the cycle began: the server files a note naming an
            // unknown space in the default.
            pushSpaces(api, deletes = false)?.let { return it }
            var result = send(api, op)
            if (result is ApiResult.Rejected && result.error == OPERATION_ID_REUSED) {
                op = store.remint(op)
                result = send(api, op)
                // Twice is no coincidence.
                if (result is ApiResult.Rejected && result.error == OPERATION_ID_REUSED) {
                    result = ApiResult.Unexpected(result.status)
                }
            }
            when (result) {
                is ApiResult.Ok -> {
                    store.sent(op, result.value)
                    pushed = true
                }

                // Its space's notes are in another file now (shared since it was queued): sent there instead.
                is ApiResult.Conflict if result.code == SPACE_ELSEWHERE && result.spaceId != null &&
                    store.refile(op, result.spaceId) -> Unit

                is ApiResult.Conflict if result.code == NOTE_MOVED && moved(op, result) -> Unit

                is ApiResult.Conflict -> {
                    // Without the server's version there's nothing to reconcile with: kept, and tried again.
                    val current = result.current?.let { decodeNote(it.toString()) }
                    if (current == null) {
                        store.attempted(op)
                        return Stop(SyncOutcome.Failed, result.message.ifEmpty { unexpectedMessage(409) })
                    }
                    emit(store.conflict(op, current))
                }

                is ApiResult.Rejected if op.type == "delete" && result.error == "not_found" -> {
                    store.deleteDone(op)
                    pushed = true
                }

                is ApiResult.Rejected if result.error in PERMANENT_ERRORS -> {
                    store.rejected(op)
                    val what = op.title.trim().ifEmpty { "A note" }
                    reject("“$what” wasn’t saved on the server: ${result.message.ifEmpty { result.error }}")
                }

                else -> return unexpected(result) { store.attempted(op) } ?: stop(result)
            }
        }
    }

    /**
     * The note went to another file (§7.9). A move there is done; other changes follow it, if this device has
     * the space. False leaves the conflict to reconcile, which takes a move out of reach for a deletion.
     */
    private suspend fun moved(op: Operation, result: ApiResult.Conflict): Boolean {
        val current = result.current?.let { decodeNote(it.toString()) } ?: return false
        val to = current.movedTo ?: return false
        if (op.type == MOVE && to == op.spaceId) {
            store.moveDone(op)
            pushed = true
            return true
        }
        return store.follows(current) && store.refile(op, to)
    }

    /**
     * A Stop, after [count]ing an attempt against the change, when [r] isn't an answer notesd gives
     * to it: an [ApiResult.Unexpected], or an error code it doesn't send there. Null otherwise.
     */
    private suspend fun unexpected(r: ApiResult<*>, count: suspend () -> Unit): Stop? {
        val status = when (r) {
            is ApiResult.Unexpected -> r.status
            is ApiResult.Rejected if r.status < 500 -> r.status
            else -> return null
        }
        count()
        return Stop(SyncOutcome.Failed, unexpectedMessage(status))
    }

    private suspend fun send(root: SyncApi, op: Operation): ApiResult<MutationResult> {
        // The file the change goes to: the account's, or its space's (§7.9).
        val api = if (op.feed.isEmpty()) root else root.inSpace(op.feed)
        return sendTo(api, op)
    }

    private suspend fun sendTo(api: SyncApi, op: Operation): ApiResult<MutationResult> = when (op.type) {
        MOVE -> api.moveNote(
            op.noteId,
            buildJsonObject {
                put("operationId", op.operationId)
                put("baseRevision", op.baseRevision)
                put("spaceId", op.spaceId)
            }
        )

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

    /** The account's feed, then each shared space's (§7.9). */
    private suspend fun pull(api: SyncApi): Stop? {
        pullFeed(api, "")?.let { return it }
        for (feed in store.feeds()) pullFeed(api.inSpace(feed), feed)?.let { return it }
        return null
    }

    private suspend fun pullFeed(api: SyncApi, feed: String): Stop? {
        var restarted = false
        while (true) {
            val state = store.state(feed)
            when (val result = api.sync(state.cursor, pageSize, state.generation)) {
                is ApiResult.Ok -> {
                    emit(store.applyPage(result.value, feed))
                    if (!result.value.hasMore) return null
                }

                is ApiResult.Conflict if result.code == "generation_mismatch" || result.code == "cursor_ahead" -> {
                    // Restored from a backup. Once per cycle: a second time means something else is wrong.
                    if (restarted) return Stop(SyncOutcome.Failed, result.message)
                    restarted = true
                    store.startFullSync(feed)
                }

                // Left or removed since the list came: the next list drops the feed.
                is ApiResult.Rejected if feed.isNotEmpty() && result.error == "not_found" -> return null

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

        is ApiResult.Unexpected -> Stop(SyncOutcome.Failed, unexpectedMessage(result.status))

        is ApiResult.UpgradeRequired -> {
            upgrade = result
            Stop(SyncOutcome.UpgradeRequired, UPGRADE_REQUIRED_MESSAGE, result.minVersion)
        }

        is ApiResult.Ok -> error("not a failure")
    }

    private fun emit(events: List<SyncEvent>) {
        for (e in events) _events.tryEmit(e)
    }

    private companion object {
        const val OPERATION_ID_REUSED = "operation_id_reused"
        const val SPACE_ELSEWHERE = "space_elsewhere"
        const val NOTE_MOVED = "note_moved"
        val json = Json { ignoreUnknownKeys = true }

        fun decodeNote(s: String): RemoteNote? = try {
            json.decodeFromString<RemoteNote>(s)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
