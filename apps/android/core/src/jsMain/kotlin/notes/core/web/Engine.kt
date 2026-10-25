package notes.core.web

import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import notes.core.api.FetchApi
import notes.core.api.LockKey
import notes.core.db.Database
import notes.core.db.Note
import notes.core.db.NoteFilter
import notes.core.db.NoteStore
import notes.core.db.Search
import notes.core.db.SpaceScope
import notes.core.db.SyncEvent
import notes.core.db.directDatabase
import notes.core.markdown.webCanonical
import notes.core.sync.SyncEngine
import notes.core.sync.SyncStatus

// Kotlin/JS exports neither suspend functions nor flows: Promises and callbacks instead.
// Longs as Doubles, exact for every timestamp and revision here.

@JsExport
class LocalNote(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Double,
    val updatedAt: Double,
    val conflictOf: String?,
    /** Null until the server has it. */
    val revision: Double?,
    /** Changed here since the last sync. */
    val dirty: Boolean,
    val spaceId: String
)

@JsExport
class LocalListItem(val id: String, val title: String, val preview: String, val updatedAt: Double, val locked: Boolean)

/** [excerpt]: HTML-escaped, matches in `<mark>`, as the server's search returns it. */
@JsExport
class LocalHit(
    val id: String,
    val title: String,
    val excerpt: String,
    val updatedAt: Double,
    val locked: Boolean,
    val spaceId: String
)

@JsExport
class LocalTag(val key: String, val name: String, val count: Int)

@JsExport
class LocalSpace(val id: String, val name: String, val icon: String, val noteCount: Int)

/** One wrapped copy of the locked-notes key, as the server's keyring has it (§10.1). */
@JsExport
class LocalLockKey(
    val id: String,
    val kind: String,
    val credentialId: String?,
    val salt: String,
    val iterations: Int,
    val wrapped: String,
    val createdAt: Double
)

/** [kind]: NotConnected, Syncing, Synced (with [at]), Offline, SignedOut or Failed (with [message]). */
@JsExport
class EngineStatus(val kind: String, val at: Double?, val message: String?)

/**
 * Something sync did that the user should hear about. [kind]: ConflictedCopy
 * ([noteId] and [copyId]), RecoveredCopy ([copyId]) or Restored ([noteId]).
 */
@JsExport
class EngineNotice(val kind: String, val noteId: String?, val copyId: String?, val title: String)

@JsExport
class NotesEngine internal constructor(private val db: Database) {
    private val scope = CoroutineScope(SupervisorJob())
    private var csrfToken: String? = null
    private val engine = SyncEngine(db, { FetchApi { csrfToken } })
    private val notes = NoteStore(db)

    /** The session's token, for mutations (§10); the cookie goes with every request by itself. */
    fun setCsrfToken(token: String?) {
        csrfToken = token
    }

    /** One cycle. Resolves with how it ended: Done, NotConnected, Offline, SignedOut or Failed. */
    fun sync(): Promise<String> = scope.promise { engine.sync().name }

    fun status(): EngineStatus = engine.status.value.export()

    fun get(id: String): Promise<LocalNote?> = scope.promise {
        notes.get(id)?.takeIf { it.deletedAt == null }?.export()
    }

    /** [filter]: "tag" (with [tag]), "untagged", "locked", or null for every note. [space] null: every space. */
    fun list(filter: String?, tag: String?, space: String?): Promise<Array<LocalListItem>> = scope.promise {
        notes.list(filterOf(filter, tag), space)
            .map { LocalListItem(it.id, it.title, it.preview, it.updatedAt.toDouble(), it.locked) }
            .toTypedArray()
    }

    fun lockedCount(space: String?): Promise<Int> = scope.promise { notes.lockedCount(space) }

    /** In [space], or in every space but [exceptSpace]; neither: everywhere. */
    fun search(
        query: String,
        filter: String?,
        tag: String?,
        space: String?,
        exceptSpace: String?,
        limit: Int
    ): Promise<Array<LocalHit>> = scope.promise {
        val within = space?.let { SpaceScope.In(it) } ?: exceptSpace?.let { SpaceScope.Except(it) }
        notes.search(query, filterOf(filter, tag), limit, within).map {
            LocalHit(it.id, it.title, excerptHtml(it.excerpt), it.updatedAt.toDouble(), it.locked, it.spaceId)
        }.toTypedArray()
    }

    fun countElsewhere(query: String, space: String): Promise<Int> = scope.promise {
        notes.countElsewhere(query, space)
    }

    fun tags(space: String?): Promise<Array<LocalTag>> = scope.promise {
        notes.tags(space).map { LocalTag(it.key, it.name, it.count) }.toTypedArray()
    }

    /** NoteStore.save, [basedOnTitle] and [basedOnContent] together or neither. Null when nothing was stored. */
    fun save(
        id: String,
        title: String,
        content: String,
        basedOnTitle: String?,
        basedOnContent: String?,
        imageIds: Array<String>?,
        spaceId: String
    ): Promise<LocalNote?> = scope.promise {
        val basedOn = if (basedOnTitle != null && basedOnContent != null) basedOnTitle to basedOnContent else null
        notes.save(id, title, content, basedOn, imageIds?.toList(), spaceId)?.export()
    }

    fun move(id: String, spaceId: String): Promise<Boolean> = scope.promise { notes.move(id, spaceId) }

    fun delete(id: String): Promise<Unit> = scope.promise { notes.delete(id) }

    /** Keeps text whose note was deleted elsewhere as a new note. Resolves with its id. */
    fun recover(title: String, content: String, imageIds: Array<String>?, spaceId: String): Promise<String> =
        scope.promise { notes.recover(title, content, imageIds?.toList(), spaceId) }

    fun pendingNotes(): Promise<Int> = scope.promise { notes.pendingNotes() }

    fun spaces(): Promise<Array<LocalSpace>> = scope.promise {
        engine.spaces.list().map { LocalSpace(it.id, it.name, it.icon, it.noteCount) }.toTypedArray()
    }

    fun createSpace(id: String, name: String, icon: String): Promise<String> =
        scope.promise { engine.spaces.create(name, icon, id) }

    /** [icon] null keeps the current one. */
    fun updateSpace(id: String, name: String, icon: String?): Promise<Unit> =
        scope.promise { engine.spaces.update(id, name, icon) }

    /** False for the default space or one with notes. */
    fun deleteSpace(id: String): Promise<Boolean> = scope.promise { engine.spaces.delete(id) }

    fun lockKeys(): Promise<Array<LocalLockKey>> = scope.promise {
        engine.lockKeys.keys().map {
            LocalLockKey(it.id, it.kind, it.credentialId, it.salt, it.iterations, it.wrapped, it.createdAt.toDouble())
        }.toTypedArray()
    }

    /** After this device changed the keyring on the server, so it opens offline without waiting for a sync. */
    fun replaceLockKeys(keys: Array<LocalLockKey>): Promise<Unit> = scope.promise {
        engine.lockKeys.replace(
            keys.map {
                LockKey(it.id, it.kind, it.credentialId, it.salt, it.iterations, it.wrapped, it.createdAt.toLong())
            }
        )
    }

    /** Every note and all sync state off this device. */
    fun wipe(): Promise<Unit> = scope.promise { notes.wipe() }

    /** Every status change. Returns an unsubscribe function. */
    fun onStatus(listener: (EngineStatus) -> Unit): () -> Unit = subscribe {
        engine.status.collect { listener(it.export()) }
    }

    /** After every committed write, from sync or from this device. */
    fun onChange(listener: () -> Unit): () -> Unit = subscribe { db.changes.collect { listener() } }

    /** After every edit made on this device: when to schedule an upload. */
    fun onLocalEdit(listener: () -> Unit): () -> Unit = subscribe { notes.localEdits.collect { listener() } }

    fun onNotice(listener: (EngineNotice) -> Unit): () -> Unit = subscribe {
        engine.events.collect { listener(it.export()) }
    }

    fun close() {
        scope.cancel()
        db.close()
    }

    private fun subscribe(collect: suspend () -> Unit): () -> Unit {
        val job = scope.launch { collect() }
        return { job.cancel() }
    }
}

/** On sqlite-wasm's oo1 [db] and its [capi]. [canonical]: the web editor's, which merges compare with (§6.1). */
@JsExport
fun openEngine(db: dynamic, capi: dynamic, canonical: (String) -> String): NotesEngine {
    webCanonical = canonical
    return NotesEngine(directDatabase(db, capi))
}

private fun filterOf(kind: String?, tag: String?): NoteFilter? = when (kind) {
    null -> null
    "tag" -> NoteFilter.Tag(requireNotNull(tag) { "A tag filter needs a tag" }, tag)
    "untagged" -> NoteFilter.Untagged
    "locked" -> NoteFilter.Locked
    else -> throw IllegalArgumentException("Unknown filter $kind")
}

private fun excerptHtml(excerpt: String): String = excerpt
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
    .replace(Search.MARK_OPEN, "<mark>")
    .replace(Search.MARK_CLOSE, "</mark>")

private fun Note.export() = LocalNote(
    id,
    title,
    content,
    createdAt.toDouble(),
    updatedAt.toDouble(),
    conflictOf,
    serverRevision?.toDouble(),
    dirty,
    spaceId
)

private fun SyncStatus.export(): EngineStatus = when (this) {
    is SyncStatus.Synced -> EngineStatus("Synced", at.toDouble(), null)
    is SyncStatus.Failed -> EngineStatus("Failed", null, message)
    SyncStatus.NotConnected -> EngineStatus("NotConnected", null, null)
    SyncStatus.Syncing -> EngineStatus("Syncing", null, null)
    SyncStatus.Offline -> EngineStatus("Offline", null, null)
    SyncStatus.SignedOut -> EngineStatus("SignedOut", null, null)
}

private fun SyncEvent.export(): EngineNotice = when (this) {
    is SyncEvent.ConflictedCopy -> EngineNotice("ConflictedCopy", noteId, copyId, title)
    is SyncEvent.RecoveredCopy -> EngineNotice("RecoveredCopy", null, copyId, title)
    is SyncEvent.Restored -> EngineNotice("Restored", noteId, null, title)
}
