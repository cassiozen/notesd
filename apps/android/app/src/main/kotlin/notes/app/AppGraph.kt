package notes.app

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import notes.app.account.Account
import notes.app.account.AccountStore
import notes.app.account.KeystoreSealer
import notes.app.account.Sealer
import notes.app.images.ImageLoader
import notes.app.locks.Biometrics
import notes.app.locks.FingerprintUnlock
import notes.app.locks.KeystoreBiometrics
import notes.app.notes.Deletions
import notes.app.notes.TagTreeState
import notes.app.settings.Appearance
import notes.app.sync.SyncScheduler
import notes.core.api.ApiClient
import notes.core.crypto.LockSession
import notes.core.db.Database
import notes.core.db.NoteFilter
import notes.core.db.NoteStore
import notes.core.db.Space
import notes.core.db.Spaces
import notes.core.images.ImageStore
import notes.core.sync.NoteMailer
import notes.core.sync.SyncEngine
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The app's long-lived objects, wired by hand. One per process, owned by [NotesApp]. */
class AppGraph(
    context: Context,
    sealer: Sealer = KeystoreSealer(),
    biometrics: Biometrics = KeystoreBiometrics(context)
) {
    /** Outlives screens: saves and deletes started here finish after the screen that asked is gone. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database = Database(context.getDatabasePath("notes.db").apply { parentFile?.mkdirs() }.path)
    val store = NoteStore(database)
    val deletions = Deletions(scope) { store.delete(it) }
    val appearance = Appearance(context)
    val accounts = AccountStore(File(context.filesDir, "account"), sealer)

    /** Pictures in notes (§7.6): added here wait in files/ to upload; downloaded ones are cached. */
    val images = ImageStore(
        database,
        File(context.filesDir, "images"),
        File(context.cacheDir, "images"),
        { api() }
    )

    /** Null server while never connected; signed out, its requests come back Unauthenticated. */
    val sync = SyncEngine(database, { api() }, images = images)
    val scheduler = SyncScheduler(context.applicationContext, sync, store, accounts, scope)

    /** The locked-notes key (§10.1), unlocked against the keyring sync keeps. */
    val locks = LockSession(sync.lockKeys, { api() }, scope)

    /** Decoded pictures for the editor; a locked note's leave memory when locked notes lock. */
    val imageLoader = ImageLoader(images, locks, scope)

    /** Opens locked notes with the fingerprint instead of the lock password, once turned on here. */
    val fingerprint = FingerprintUnlock(context, locks, biometrics, scope)

    /** Emails notes through the server; it syncs a note before sending it. */
    val mailer = NoteMailer(store, sync) { api() }
    val email = EmailSetup { api() }

    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    /**
     * What the list shows. Shared, so the editor can open a tag's notes; kept so the app reopens on it,
     * unless it was picked in another space: the app opens in the default one (§7.7).
     */
    val listFilter = MutableStateFlow(
        if (prefs.getString(LIST_FILTER_SPACE, Spaces.DEFAULT) == Spaces.DEFAULT) {
            decodeFilter(prefs.getString(LIST_FILTER, null))
        } else {
            null
        }
    )

    private val _space = MutableStateFlow(Spaces.DEFAULT)

    /**
     * Every space (§7.7), the default first, kept current with every write while the UI watches.
     * The space shown was deleted, here or on another device: back to the default. Checked only as a
     * fresh list arrives, so a stale one can't undo a switch to a space made since.
     * Lazy: opening the database with the graph breaks Robolectric tests run in another sandbox.
     */
    val spaces: StateFlow<List<Space>> = store.changes.onSubscription { emit(Unit) }.map { sync.spaces.list() }
        .onEach { list -> if (list.none { it.id == _space.value }) switchSpace(Spaces.DEFAULT) }
        .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

    /** The space the list, tags and search show: this phone's own, never synced, the default at a cold start. */
    val space: StateFlow<String> = _space

    /** Shows another space, from All notes. */
    fun switchSpace(id: String) {
        if (_space.value == id) return
        _space.value = id
        listFilter.value = null
    }

    /** A note is open in the editor: coming back after a while doesn't move the list from under it. */
    @Volatile var editorOpen = false

    private var backgroundSince: Long? = null

    /** Which parts of the list's tag tree are open, remembered across restarts. */
    val tagTree = TagTreeState(prefs)
    private val _onboardingDismissed = MutableStateFlow(prefs.getBoolean(ONBOARDING_DISMISSED, false))

    /** "Use without a server" was chosen: start at the list instead of onboarding. */
    val onboardingDismissed: StateFlow<Boolean> = _onboardingDismissed

    fun setOnboardingDismissed(dismissed: Boolean) {
        prefs.edit { putBoolean(ONBOARDING_DISMISSED, dismissed) }
        _onboardingDismissed.value = dismissed
    }

    private var api: ApiClient? = null

    /** The client for the connected server, shared so its token refreshes are single-flight. Null if never connected. */
    fun api(): ApiClient? {
        val server = when (val a = accounts.account.value) {
            Account.None -> return null
            is Account.SignedIn -> a.server
            is Account.SignedOut -> a.server
        }
        return api?.takeIf { it.baseUrl.toString() == server }
            ?: ApiClient(server.toHttpUrl(), accounts).also { api = it }
    }

    /**
     * Ends the session on the server if it answers quickly, then forgets the
     * tokens. Notes and the server address stay: signing in again resumes.
     */
    suspend fun signOut() {
        withTimeoutOrNull(LOGOUT_WAIT_MS) { api()?.logout() }
        accounts.signOut()
        locks.lock(force = true)
        fingerprint.disable()
    }

    /** Back to a fresh install: no notes, no account, onboarding on next launch. */
    suspend fun removeAllData() {
        if (accounts.account.value is Account.SignedIn) withTimeoutOrNull(LOGOUT_WAIT_MS) { api()?.logout() }
        store.wipe()
        images.wipe()
        locks.forget()
        fingerprint.disable()
        accounts.clear()
        setOnboardingDismissed(false)
    }

    /** How this phone appears in the server's list of signed-in devices. */
    val deviceName: String = run {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        if (Build.MODEL.startsWith(maker, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    init {
        scope.launch {
            combine(listFilter, _space) { f, s -> f to s }.drop(1).collect { (f, s) ->
                prefs.edit {
                    putString(LIST_FILTER, encodeFilter(f))
                    putString(LIST_FILTER_SPACE, s)
                }
            }
        }
        // Fetched when a note or Settings opens; another account's settings must not linger.
        scope.launch { accounts.account.collect { if (it !is Account.SignedIn) email.forget() } }
        scope.launch { locks.state.collect { if (it != LockSession.State.Unlocked) imageLoader.forgetLocked() } }
    }

    /** Back after [SPACE_RESET_MS] away with no note open, the list starts over in the default space. Started with sync. */
    fun watchSpaces() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    backgroundSince = System.currentTimeMillis()
                }

                override fun onStart(owner: LifecycleOwner) {
                    val since = backgroundSince ?: return
                    backgroundSince = null
                    if (System.currentTimeMillis() - since > SPACE_RESET_MS && !editorOpen) switchSpace(Spaces.DEFAULT)
                }
            }
        )
    }

    /** Each sync may bring the first lock password, a changed one, or a reset. Started with sync. */
    fun watchKeyring() {
        scope.launch {
            locks.refresh()
            sync.status.collect { locks.refresh() }
        }
    }

    private companion object {
        const val ONBOARDING_DISMISSED = "onboarding_dismissed"
        const val LIST_FILTER = "list_filter"
        const val LIST_FILTER_SPACE = "list_filter_space"
        const val SPACE_RESET_MS = 30 * 60_000L
        const val LOGOUT_WAIT_MS = 5_000L
    }
}

/** The list's filter in prefs: "untagged", "locked", or "tag:" + key + newline + name. */
internal fun encodeFilter(f: NoteFilter?): String? = when (f) {
    null -> null
    NoteFilter.Untagged -> "untagged"
    NoteFilter.Locked -> "locked"
    is NoteFilter.Tag -> "tag:${f.key}\n${f.name}"
}

internal fun decodeFilter(s: String?): NoteFilter? = when {
    s == "untagged" -> NoteFilter.Untagged

    s == "locked" -> NoteFilter.Locked

    s != null && s.startsWith("tag:") && '\n' in s ->
        NoteFilter.Tag(s.substring(4).substringBefore('\n'), s.substringAfter('\n'))

    else -> null
}
