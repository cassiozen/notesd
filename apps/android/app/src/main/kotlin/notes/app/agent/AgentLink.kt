package notes.app.agent

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import notes.app.account.Account
import notes.app.account.AccountStore
import notes.app.notes.OpenNotes
import notes.core.agent.AgentSocket
import notes.core.agent.DroppedRun
import notes.core.agent.EditorReply
import notes.core.agent.Fields
import notes.core.agent.Overlay
import notes.core.agent.Prepared
import notes.core.api.ApiClient
import notes.core.api.ApiResult

/**
 * Keeps the agent socket (docs/contributing/agent-clients.md) open while someone could use it: signed in, in
 * the foreground, online, with AI set up and the agent on in the space shown. Tells it which notes are open.
 */
class AgentLink(
    private val accounts: AccountStore,
    private val ai: AiSetup,
    private val agents: AgentConfigs,
    private val space: StateFlow<String>,
    private val foreground: StateFlow<Boolean>,
    private val online: StateFlow<Boolean>,
    private val openNotes: OpenNotes,
    private val editors: OpenNoteEditors,
    api: () -> ApiClient?,
    private val scope: CoroutineScope,
    timing: AgentSocket.Timing = AgentSocket.Timing()
) {
    val socket = AgentSocket({ api()?.agentSocket(it) ?: ApiResult.Unauthenticated }, editors, scope, timing)

    private enum class Want { Open, Closed, Background }

    /** New notes that opened with a result done ahead, until their editor asks for their run. */
    private val openedAhead = ConcurrentHashMap<String, Prepared>()

    /** New notes, by id, that opened with a result done ahead the server no longer had. */
    val missed: Flow<String> = socket.missed

    /**
     * What a new note opens with: its space's "new note opened" run, done ahead (docs/contributing/agent-clients.md:
     * Done ahead), when it's ready. Null opens it empty, and the run starts once it's open.
     */
    fun openNewNote(noteId: String, spaceId: String): Fields? {
        if (!acts(NOTE_CREATED, spaceId)) return null
        val p = socket.takePrepared(spaceId) ?: return null
        openedAhead[noteId] = p
        return Fields(p.title, p.content)
    }

    /**
     * Asks for an editor trigger's run on a note open here (docs/contributing/agent-clients.md: Editor triggers),
     * if the note's space has its agent on with a prompt for [trigger]. Not otherwise: the server would drop it.
     * A new note that opened with its run done ahead says so instead, and nothing runs.
     */
    fun requestRun(trigger: String, noteId: String, spaceId: String): Boolean {
        if (trigger == NOTE_CREATED) {
            openedAhead.remove(noteId)?.let {
                socket.usePrepared(it, noteId)
                return true
            }
        }
        if (!acts(trigger, spaceId)) return false
        return socket.requestRun(trigger, noteId, spaceId)
    }

    private fun acts(trigger: String, spaceId: String): Boolean {
        if (ai.cache.value.value?.configured != true) return false
        val config = agents.cache.value.value?.get(spaceId)
        return config?.enabled == true && !config.triggers[trigger].isNullOrBlank()
    }

    /**
     * The user answered an overlay. Accepting a suggestion makes it in the note's editor first, and says what that
     * did; one that can't be made any more is answered `stale`. A locked note's is never made, nor answered.
     */
    fun answerOverlay(overlay: Overlay, answer: String) {
        if (answer != "accept") return socket.answerOverlay(overlay, answer)
        scope.launch {
            when (val made = editors.accept(overlay)) {
                is EditorReply.Applied -> socket.answerOverlay(overlay, "accept", made)
                EditorReply.Refused("stale") -> socket.answerOverlay(overlay, "stale")
                else -> {}
            }
        }
    }

    /** The command a note's menu offers for its space's agent: its label, or null with none. */
    fun menuCommand(spaceId: String): String? {
        if (ai.cache.value.value?.configured != true) return null
        val config = agents.cache.value.value?.get(spaceId)
        if (config?.enabled != true || config.triggers[MENU].isNullOrBlank()) return null
        return config.menuLabel.ifBlank { null }
    }

    /** Why the menu command, asked for on [noteId], didn't run: the one run asked for by hand. */
    fun menuDropped(noteId: String): Flow<String> = socket.dropped
        .filter { it.trigger == MENU && it.noteId == noteId }
        .map { whyNot(it.why, online.value) }

    fun start() {
        openNotes.listener = object : OpenNotes.Listener {
            override fun opened(id: String, spaceId: String) = socket.opened(id, spaceId)

            override fun closed(id: String, revision: Long?) = socket.closed(id, revision)
        }
        scope.launch { online.collect { socket.online(it) } }
        val signedIn = accounts.account.map { it is Account.SignedIn }.distinctUntilChanged()
        // Another account's notes and runs must never reach this one's socket.
        scope.launch {
            accounts.account.map { (it as? Account.SignedIn)?.let { a -> a.server to a.username } }
                .distinctUntilChanged()
                .drop(1)
                .collect { socket.reset() }
        }
        // The agent may have been turned on from another device while away.
        scope.launch {
            combine(signedIn, foreground) { a, f -> a && f }.distinctUntilChanged().filter { it }.collect {
                launch { ai.cache.refresh() }
                launch { agents.cache.refresh() }
            }
        }
        // The space shown, while it has its new notes done ahead. Not in the background: nobody can make a note.
        scope.launch {
            combine(signedIn, foreground, ai.cache.value, agents.cache.value, space) { a, f, settings, configs, s ->
                val config = configs?.get(s)
                s.takeIf {
                    a && f && settings?.configured == true && config?.enabled == true &&
                        !config.triggers[NOTE_CREATED].isNullOrBlank()
                }
            }.distinctUntilChanged().collect { socket.wantAhead(it) }
        }
        scope.launch {
            combine(signedIn, foreground, ai.cache.value, agents.cache.value, space) { a, f, settings, configs, s ->
                when {
                    !a || settings?.configured != true || configs?.get(s)?.enabled != true -> Want.Closed
                    !f -> Want.Background
                    else -> Want.Open
                }
            }.distinctUntilChanged().collect {
                when (it) {
                    Want.Open -> socket.start()

                    Want.Closed -> socket.stop()

                    // Its notes stay open, so there's no close to wait for; the server keeps their sessions.
                    Want.Background -> socket.stop(waitForCloses = false)
                }
            }
        }
    }

    companion object {
        const val MENU = "menu"
        const val NOTE_CREATED = "noteCreated"

        /** Why a run asked for by hand didn't go, as the web app says it. */
        fun whyNot(why: DroppedRun.Why, online: Boolean): String = when {
            why == DroppedRun.Why.Off -> "The agent didn't run: it isn't on for this space."
            online -> "The agent didn't run: the server can't be reached."
            else -> "The agent didn't run: you're offline."
        }
    }
}
