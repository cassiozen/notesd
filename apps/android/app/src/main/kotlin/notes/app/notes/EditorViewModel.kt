package notes.app.notes

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import notes.app.images.ImageException
import notes.app.images.Prepared
import notes.app.images.prepareImage
import notes.core.api.LOCKED_IMAGE
import notes.core.crypto.LockSession
import notes.core.crypto.LockedException
import notes.core.crypto.LockedNotes
import notes.core.db.Note
import notes.core.db.NoteStore
import notes.core.db.Spaces
import notes.core.db.TagCount
import notes.core.doc.Document
import notes.core.images.ImageRef
import notes.core.images.ImageStore
import notes.core.markdown.Markdown
import notes.core.text.tagText

enum class SaveStatus { Blank, Unsaved, Saved }

/** The note a conflicted copy was split from. */
data class Original(val id: String, val title: String)

/**
 * One open note. Saves run on [appScope] so a save started as the screen
 * closes still lands; they're serialised, so an older text never overwrites a
 * newer one. A note that doesn't exist yet is created by its first non-blank
 * save.
 *
 * A new note can start with a tag ([startTag]); with nothing more typed it
 * still counts as blank.
 *
 * Sync can change the note while it's open. With no unsaved typing, the change
 * goes straight into the editor ([incoming]). Otherwise the next save passes
 * the stored version the typing grew from, and the store merges the two.
 *
 * A locked note (§10.1) is stored encrypted. Its text is in memory only while
 * [locks] is unlocked: [document] is null otherwise, and [needsUnlock] says
 * why. Saves encrypt with the session key, reusing the stored envelope when
 * the text is unchanged, so the server sees no edit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModel(
    private val store: NoteStore,
    val id: String,
    private val appScope: CoroutineScope,
    private val locks: LockSession,
    private val startTag: String? = null,
    private val images: ImageStore? = null,
    /** Where a new note is created: the space the list showed (§7.7). */
    spaceId: String = Spaces.DEFAULT
) : ViewModel() {
    /** The note's space: where it's created, or where it is once loaded or moved. */
    private var noteSpace = spaceId

    private val _document = MutableStateFlow<Document?>(null)

    /** The text to show, null while loading. Kept current on every save so a recreated view picks it up. */
    val document: StateFlow<Document?> = _document

    private val _incoming =
        MutableSharedFlow<Document>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Text that changed underneath the editor (sync, or a merge on save): show it, keeping the cursor. */
    val incoming: SharedFlow<Document> = _incoming

    private val _status = MutableStateFlow(SaveStatus.Blank)
    val status: StateFlow<SaveStatus> = _status

    private val _original = MutableStateFlow<Original?>(null)
    val original: StateFlow<Original?> = _original

    /** Every tag, for completing one being typed. */
    val tags: StateFlow<List<TagCount>> =
        store.changes.onSubscription { emit(Unit) }.mapLatest { store.tags() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _gone = MutableStateFlow(false)

    /** The note was deleted elsewhere; any unsaved typing was kept as a recovered copy. */
    val gone: StateFlow<Boolean> = _gone

    private val _locked = MutableStateFlow(false)

    /** The note's body is (to be) encrypted. */
    val locked: StateFlow<Boolean> = _locked

    /** Locked, and locked notes are locked: show the unlock prompt instead of the text. */
    val needsUnlock: StateFlow<Boolean> = combine(_locked, locks.state) { l, s -> l && s != LockSession.State.Unlocked }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _unreadable = MutableStateFlow(false)

    /** Locked with a key the keyring no longer has (it was reset, or there's no lock password at all): it can't be opened. */
    val unreadable: StateFlow<Boolean> = _unreadable

    private val _title = MutableStateFlow("")

    /** The stored title, readable even while the note is locked. */
    val title: StateFlow<String> = _title

    private val _imageProblem = MutableStateFlow<String?>(null)

    /** A picture couldn't be added, or a lock change couldn't move the pictures: shown until dismissed. */
    val imageProblem: StateFlow<String?> = _imageProblem

    fun dismissImageProblem() {
        _imageProblem.value = null
    }

    /**
     * Stores a picked picture for this note (encrypted if it's locked) and
     * returns the image run to insert; null, with [imageProblem] set, if it
     * can't be used. Sync uploads it before the note.
     */
    suspend fun addImage(resolver: ContentResolver, uri: Uri): String? {
        val store = images ?: return null
        return try {
            val prepared = prepareImage(resolver, uri)
            val locked = _locked.value
            var id = storeImage(store, prepared, locked)
            // Locked or unlocked meanwhile: stored the way the note is now.
            if (_locked.value != locked) id = storeImage(store, prepared, _locked.value)
            ImageRef(id, prepared.width, prepared.height).markdown()
        } catch (e: ImageException) {
            _imageProblem.value = e.message
            null
        }
    }

    private suspend fun storeImage(store: ImageStore, prepared: Prepared, locked: Boolean): String {
        if (!locked) return store.add(prepared.bytes, prepared.type)
        // A copy: locking zeroes the session's key.
        val key = locks.key()?.copyOf() ?: throw ImageException("Unlock locked notes to add pictures to this note.")
        val sealed = try {
            withContext(Dispatchers.Default) { LockedNotes.encryptImage(key, prepared.bytes) }
        } finally {
            key.fill(0)
        }
        return store.add(sealed, LOCKED_IMAGE)
    }

    /**
     * Before a lock change: stores the pictures with ids [ids] again,
     * encrypted for [locked] or plain without; old id → new id. Null, with
     * [imageProblem] set, when one isn't on this phone and can't be
     * downloaded: the lock stays as it is.
     */
    suspend fun moveImages(ids: Set<String>, locked: Boolean): Map<String, String>? {
        val store = images ?: return emptyMap()
        if (ids.isEmpty()) return emptyMap()
        // A copy: locking zeroes the session's key, and fetching the pictures may take a while.
        val key = locks.key()?.copyOf()
        val moved = try {
            key?.let { store.move(ids, locked, it) }
        } finally {
            key?.fill(0)
        }
        if (moved == null) {
            _imageProblem.value = "A picture in this note couldn’t be moved with the lock. " +
                "If it isn’t on this phone yet, connect and try again."
        }
        return moved
    }

    /** The plain text the stored envelope holds: unchanged text is saved as the same envelope. */
    private var sealed: Pair<String, String>? = null

    /** A locked note's edit that came too late to encrypt; saved on the next unlock. */
    private var stranded: Document? = null

    /** The store had no such note when it was opened: a new note. Set before [document]. */
    var isNew = false
        private set

    private val saveLock = Mutex()
    private var lastSave: Job? = null
    private var edits = 0
    private var saved = 0
    private var saving = 0

    /** The stored title and content the editor's text grew from; null until the note exists. */
    private var known: Pair<String, String>? = null

    /** What a new note starts with; unchanged, it isn't stored. */
    private val blankContent = startTag?.let { tagText(it) + "\n" } ?: ""

    init {
        viewModelScope.launch {
            val note = store.get(id)
            isNew = note == null
            note?.let { noteSpace = it.spaceId }
            known = note?.let { it.title to it.content }
            _locked.value = note != null && LockedNotes.isLocked(note.content)
            _title.value = note?.title.orEmpty()
            _document.value = when {
                note != null -> parse(note)
                blankContent.isEmpty() -> Document.forNote("", Document.EMPTY)
                else -> Document.forNote("", Markdown.parse(blankContent))
            }
            if (note != null) show(note)
            launch { locks.state.collect { lockChanged(it) } }
            store.changes.conflate().collect { refresh() }
        }
    }

    // Saves in flight finish (encrypting) before the key goes; one that failed keeps the key.
    private val unhook = locks.onBeforeLock { saved() }

    override fun onCleared() {
        unhook()
    }

    /**
     * Locks or unlocks the note: saved like an edit. Locking needs the session
     * unlocked (the screen asks for the password or creates one first).
     */
    fun setLocked(doc: Document, locked: Boolean) {
        if (_locked.value == locked) return
        _locked.value = locked
        sealed = null
        edited()
        save(doc)
    }

    private suspend fun lockChanged(state: LockSession.State) {
        if (!_locked.value) return
        if (state == LockSession.State.Unlocked) {
            if (_document.value == null) store.get(id)?.let { _document.value = parse(it) }
            stranded?.let {
                stranded = null
                edited()
                save(it)
            }
        } else {
            // The plain text leaves memory; the screen shows the unlock prompt, or,
            // with no lock password left (a reset), that the note can't be opened.
            _document.value = null
            sealed = null
            _unreadable.value = state == LockSession.State.None
        }
    }

    fun edited() {
        edits++
        _status.value = SaveStatus.Unsaved
    }

    /** Stores [doc] if anything changed since the last save. Call with the editor's current document. */
    fun save(doc: Document) {
        if (edits == saved) return
        val edit = edits
        saved = edit
        saving++
        _document.value = doc
        lastSave = appScope.launch {
            try {
                saveLock.withLock { store(doc, edit) }
            } finally {
                saving--
            }
        }
    }

    /** The space the note is in, or will be created in. */
    fun spaceId(): String = noteSpace

    /**
     * Moves the note to another space, after the saves started so far. A new note not stored yet
     * is created there instead.
     */
    fun move(target: String) {
        noteSpace = target
        appScope.launch {
            saved()
            saveLock.withLock { store.move(id, target) }
        }
    }

    /** Waits for the saves started so far; they run in order, so the last one is enough. False if it failed. */
    suspend fun saved(): Boolean {
        val last = lastSave ?: return true
        last.join()
        return !last.isCancelled
    }

    private suspend fun store(doc: Document, edit: Int) {
        val title = doc.title.orEmpty()
        val plain = withContext(Dispatchers.Default) { Markdown.serialize(doc) }
        val basedOn = known
        if (basedOn == null && title.isBlank() && plain.trim() == blankContent.trim()) {
            if (edit == edits) _status.value = SaveStatus.Blank
            return
        }
        val content = if (!_locked.value) {
            plain
        } else {
            val key = locks.key()
            if (key == null) {
                // Locked before this save could encrypt: it waits for the next unlock.
                stranded = doc
                return
            }
            sealed?.takeIf { it.first == plain }?.second
                ?: withContext(Dispatchers.Default) { LockedNotes.encrypt(key, plain) }
        }
        // The server can't read a locked body's images: the save lists them (§7.6).
        // From the body being saved, not the lock flag, which may have changed while it encrypted.
        val imageIds = if (LockedNotes.isLocked(content)) ImageRef.ids(plain) else null
        val note = store.save(id, title, content, basedOn, imageIds, noteSpace)
        if (note == null) {
            if (basedOn != null && basedOn != (title to content)) {
                // Deleted elsewhere while typed into here: keep the typing.
                store.recover(title, content, imageIds, noteSpace)
                _gone.value = true
            } else if (edit == edits) {
                _status.value = SaveStatus.Blank
            }
            return
        }
        val stored = note.title to note.content
        _title.value = note.title
        noteSpace = note.spaceId
        if (_locked.value && note.content == content) sealed = plain to content
        if (stored != (title to content) && edit == edits) {
            // Merged with a sync change: the editor shows the result.
            known = stored
            _locked.value = LockedNotes.isLocked(note.content)
            // Null while locked: unlocking then loads the stored body, not the text from before.
            val doc = parse(note)
            _document.value = doc
            if (doc != null) _incoming.tryEmit(doc)
        } else {
            // If typing continued, the next save merges from what this one sent.
            known = if (stored == (title to content)) stored else title to content
        }
        if (edit == edits) show(note)
    }

    private suspend fun refresh() {
        if (edits != saved || saving > 0) return
        val note = store.get(id) ?: return
        if (note.deletedAt != null) {
            _gone.value = true
            return
        }
        val stored = note.title to note.content
        val before = known
        _title.value = note.title
        // Sync may have moved it to another space.
        noteSpace = note.spaceId
        if (before != null && stored != before) {
            known = stored
            _locked.value = LockedNotes.isLocked(note.content)
            val doc = parse(note)
            _document.value = doc
            if (doc != null) _incoming.tryEmit(doc)
        }
        show(note)
    }

    private suspend fun show(note: Note) {
        if (edits == saved) _status.value = SaveStatus.Saved
        val originalId = note.conflictOf
        if (originalId != null && _original.value?.id != originalId) {
            _original.value = store.get(originalId)?.takeIf { it.deletedAt == null }?.let { Original(it.id, it.title) }
        }
    }

    /** The note as the editor shows it; null for a locked note while locked notes are locked. */
    private suspend fun parse(note: Note): Document? {
        if (!LockedNotes.isLocked(note.content)) {
            _unreadable.value = false
            return withContext(Dispatchers.Default) { Document.forNote(note.title, Markdown.parse(note.content)) }
        }
        val key = locks.key()
        if (key == null) {
            // No lock password at all (reset): nothing can open it. Otherwise the screen asks to unlock.
            _unreadable.value = locks.state.value == LockSession.State.None
            return null
        }
        return withContext(Dispatchers.Default) {
            try {
                val plain = LockedNotes.decrypt(key, note.content)
                sealed = plain to note.content
                _unreadable.value = false
                Document.forNote(note.title, Markdown.parse(plain))
            } catch (_: LockedException) {
                _unreadable.value = true
                null
            }
        }
    }
}
