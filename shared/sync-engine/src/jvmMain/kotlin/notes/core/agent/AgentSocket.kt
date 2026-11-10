package notes.core.agent

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import notes.core.api.ApiResult
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** What an editor answers a run's request (docs/contributing/agent-clients.md: Reading, Applying a change). */
sealed interface EditorReply {
    /** The note as its editor would save it now; [cursor] in code points, [revision] the server's of exactly it. */
    data class Shown(val spaceId: String, val note: Fields, val cursor: Int?, val revision: Long?) : EditorReply

    /** The change was made: what showed just before it, the server's revision of that, and what shows now. */
    data class Applied(val before: Fields, val revision: Long?, val shows: Fields) : EditorReply

    /** `locked`, `changed` or `not_open`. */
    data class Refused(val error: String) : EditorReply
}

/** The notes open in this app's editors, which runs read and change. */
interface AgentEditors {
    suspend fun read(noteId: String): EditorReply

    suspend fun apply(noteId: String, runId: String, change: AgentChange): EditorReply
}

/** A run starting or ending, as the server tells every socket of the account. */
@Serializable
data class RunUpdate(
    val runId: String,
    val noteId: String,
    val trigger: String = "",
    val status: String,
    val failure: String = "",
    val outcome: String = ""
)

/** Where an overlay shows: [at] the `top`, `end` or `cursor`; or [after] the block holding a quote from the note. */
@Serializable
data class OverlayPlace(val at: String = "", val after: String = "")

/**
 * What a run shows beside a note open here, never in it (docs/contributing/agent-clients.md: Overlays): a `card`
 * to read and dismiss, or a `suggestion` whose [change] the user accepts or rejects. [body]: a card's Markdown, or
 * why a suggestion is made.
 */
@Serializable
data class Overlay(
    val id: String,
    val runId: String,
    val noteId: String,
    val kind: String,
    val place: OverlayPlace = OverlayPlace(),
    val body: String = "",
    val change: AgentOp? = null
)

/** An editor trigger's run (docs/contributing/agent-clients.md: Editor triggers) that was never asked for. */
data class DroppedRun(val trigger: String, val noteId: String, val spaceId: String, val why: Why) {
    enum class Why {
        /** No socket wanted: the agent isn't on, or the app is signed out or away. */
        Off,

        /** Still not connected after [AgentSocket.Timing.heldMs]. */
        Unreachable
    }
}

/**
 * The app's connection for agents (docs/contributing/agent-clients.md): it tells the server which notes are
 * open here, so closing one ends its writing session at once, and answers runs reading and changing them.
 * Kept open while [start]ed and online; [open] makes the authenticated socket.
 */
class AgentSocket(
    private val open: suspend (WebSocketListener) -> ApiResult<WebSocket>,
    private val editors: AgentEditors,
    private val scope: CoroutineScope,
    private val timing: Timing = Timing(),
    private val jitter: () -> Double = Random::nextDouble
) {
    /**
     * Reconnect backoff; how long a socket no longer wanted waits for the open notes to say they closed; how long
     * a run asked for while connecting is held.
     */
    data class Timing(
        val retryBaseMs: Long = 1_000,
        val retryMaxMs: Long = 30_000,
        val closeWaitMs: Long = 5_000,
        val heldMs: Long = 10_000
    )

    private val lock = Any()
    private var wanted = false
    private var online = true
    private var failures = 0
    private var retry: Job? = null
    private var closing: Job? = null

    /** The connection being made or in use; callbacks from an older one are ignored. */
    private var current: Attempt? = null

    /** Notes open here, each with how many opens haven't closed yet, and its space. */
    private val openCount = HashMap<String, Int>()
    private val openIn = HashMap<String, String>()

    /** Runs asked for before hello, by trigger and note: sent once it comes, or dropped after [Timing.heldMs]. */
    private val held = LinkedHashMap<String, Held>()

    /** Runs going on notes, by run; and those whose change is in the editor already. */
    private val running = HashMap<String, String>()
    private val landed = HashSet<String>()

    /** Overlay answers made while not connected, by overlay: sent first after hello. */
    private val answers = LinkedHashMap<String, JsonObject>()

    private val _connected = MutableStateFlow(false)

    /** The server said hello: requests and run updates can come. */
    val connected: StateFlow<Boolean> = _connected

    private val _runs = MutableSharedFlow<RunUpdate>(extraBufferCapacity = 64)

    /** Runs starting and ending, while connected. Best-effort, as the server sends them. */
    val runs: SharedFlow<RunUpdate> = _runs

    private val _writing = MutableStateFlow<Set<String>>(emptySet())

    /** Notes with a run going whose change isn't in the editor yet: "Agent is writing…". */
    val writing: StateFlow<Set<String>> = _writing

    private val _dropped = MutableSharedFlow<DroppedRun>(extraBufferCapacity = 16)

    /** Runs asked for that never went. */
    val dropped: SharedFlow<DroppedRun> = _dropped

    private val _overlays = MutableStateFlow<Map<String, List<Overlay>>>(emptyMap())

    /** What runs show beside each note open here, by note. */
    val overlays: StateFlow<Map<String, List<Overlay>>> = _overlays

    fun start() = synchronized(lock) {
        if (wanted) return
        wanted = true
        closing?.cancel()
        closing = null
        // Still open, waiting to close: it carries on.
        if (current == null) connect()
    }

    /**
     * Closes once the notes open here have said they closed, so their writing sessions end now rather than on
     * the server's idle timer; or after [Timing.closeWaitMs]. Not [waitForCloses]: at once (an app going to the
     * background, whose notes stay open).
     */
    fun stop(waitForCloses: Boolean = true) = synchronized(lock) {
        if (!wanted) return
        wanted = false
        retry?.cancel()
        dropHeld(tell = true)
        if (!waitForCloses || openCount.isEmpty() || !_connected.value) {
            drop()
            return
        }
        closing = scope.launch {
            delay(timing.closeWaitMs)
            val me = coroutineContext.job
            synchronized(lock) { if (closing === me) drop() }
        }
    }

    /** Disconnects and forgets every open note: another account may sign in next, and must hear none of it. */
    fun reset() = synchronized(lock) {
        wanted = false
        retry?.cancel()
        // Another account's: nothing to tell.
        dropHeld(tell = false)
        drop()
        answers.clear()
        openCount.clear()
        openIn.clear()
    }

    /** Doesn't connect while offline; back online, connects at once. */
    fun online(isOnline: Boolean) = synchronized(lock) {
        online = isOnline
        if (isOnline && wanted && current == null) {
            retry?.cancel()
            connect()
        }
    }

    /** An editor shows the note, a new one not yet saved included; [spaceId] is where it is or will be. */
    fun opened(noteId: String, spaceId: String) = synchronized(lock) {
        openCount[noteId] = (openCount[noteId] ?: 0) + 1
        openIn[noteId] = spaceId
        send(opened(noteId))
    }

    /**
     * An editor no longer shows the note, after its last save. [revision]: the server's of what it last
     * showed, or null when it doesn't know.
     */
    fun closed(noteId: String, revision: Long?) = synchronized(lock) {
        val count = openCount[noteId] ?: 0
        // Opened before a reset: another account's editor.
        if (count == 0) return
        if (count > 1) {
            openCount[noteId] = count - 1
            return
        }
        openCount.remove(noteId)
        val spaceId = openIn.remove(noteId)
        setOverlays(noteId, emptyList())
        send(
            buildJsonObject {
                put("type", "editor.closed")
                put("noteId", noteId)
                put("revision", revision)
                put("spaceId", spaceId)
            }
        )
        if (!wanted && openCount.isEmpty()) drop()
    }

    /**
     * Asks for an editor trigger's run on a note open here, sending what its editor shows so the run needn't ask
     * first. Still connecting, it's held, and sent after hello. False, and dropped, with no socket wanted.
     */
    fun requestRun(trigger: String, noteId: String, spaceId: String): Boolean = synchronized(lock) {
        if (!wanted) {
            _dropped.tryEmit(DroppedRun(trigger, noteId, spaceId, DroppedRun.Why.Off))
            return false
        }
        val attempt = current
        if (attempt != null && _connected.value) {
            sendRequest(attempt, trigger, noteId, spaceId)
            return true
        }
        val key = "$trigger:$noteId"
        held.remove(key)?.expires?.cancel()
        val h = Held(trigger, noteId, spaceId)
        held[key] = h
        h.expires = scope.launch {
            delay(timing.heldMs)
            synchronized(lock) {
                if (held[key] !== h) return@launch
                held.remove(key)
                _dropped.tryEmit(DroppedRun(trigger, noteId, spaceId, DroppedRun.Why.Unreachable))
            }
        }
        true
    }

    private fun sendRequest(attempt: Attempt, trigger: String, noteId: String, spaceId: String) {
        scope.launch {
            val shown = try {
                editors.read(noteId) as? EditorReply.Shown
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            synchronized(lock) {
                if (current !== attempt) return@launch
                send(
                    buildJsonObject {
                        put("type", "run.request")
                        put("trigger", trigger)
                        put("noteId", noteId)
                        put("spaceId", spaceId)
                        // Without it (locked, or the view not back yet), the run asks the editor itself.
                        if (shown != null) put("note", note(shown))
                    }
                )
            }
        }
    }

    /**
     * The user's answer to an overlay: `dismiss`, `accept`, `reject`, or `stale` when it can no longer be made. It
     * goes at once; the server tells every editor what's left. Not connected, it's sent first after hello, so the
     * list that follows leaves it out. An accepted suggestion was made in the editor, as [applied] says.
     */
    fun answerOverlay(overlay: Overlay, answer: String, applied: EditorReply.Applied? = null) = synchronized(lock) {
        setOverlays(overlay.noteId, _overlays.value[overlay.noteId].orEmpty().filter { it.id != overlay.id })
        val m = buildJsonObject {
            put("type", "overlay.answer")
            put("noteId", overlay.noteId)
            put("id", overlay.id)
            put("answer", answer)
            if (applied != null) {
                put("before", fields(applied.before))
                put("revision", applied.revision)
                put("shows", fields(applied.shows))
            }
        }
        if (_connected.value) send(m) else answers[overlay.id] = m
    }

    private fun setOverlays(noteId: String, list: List<Overlay>) {
        val now = _overlays.value
        if (list.isEmpty() && noteId !in now) return
        _overlays.value = if (list.isEmpty()) now - noteId else now + (noteId to list)
    }

    private fun dropHeld(tell: Boolean) {
        for (h in held.values) {
            h.expires?.cancel()
            if (tell) _dropped.tryEmit(DroppedRun(h.trigger, h.noteId, h.spaceId, DroppedRun.Why.Off))
        }
        held.clear()
    }

    private fun opened(noteId: String) = buildJsonObject {
        put("type", "editor.opened")
        put("noteId", noteId)
        put("spaceId", openIn[noteId])
    }

    // --- the connection, all under lock ---------------------------------------------------------------------

    private fun connect() {
        if (!online) return
        val attempt = Attempt()
        current = attempt
        attempt.job = scope.launch {
            val r = try {
                open(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ApiResult.Unreachable(e)
            }
            synchronized(lock) {
                val socket = (r as? ApiResult.Ok)?.value
                when {
                    current !== attempt -> socket?.cancel()
                    socket == null -> lost()
                    else -> if (attempt.socket == null) attempt.socket = socket
                }
            }
        }
    }

    /** Closed here, on purpose: no retry. */
    private fun drop() {
        closing?.cancel()
        closing = null
        val attempt = current ?: return
        current = null
        _connected.value = false
        forgetRuns()
        attempt.job?.cancel()
        attempt.socket?.close(NORMAL, null)
    }

    /** Closed by the server or the network: tried again while wanted. */
    private fun lost() {
        current = null
        _connected.value = false
        forgetRuns()
        if (!wanted) return
        val wait = min(timing.retryMaxMs, timing.retryBaseMs shl min(failures++, MAX_DOUBLINGS))
        retry = scope.launch {
            // Each wait at 50–100%, so a server's restart isn't met by every app at once.
            delay((wait * (0.5 + jitter() / 2)).toLong())
            val me = coroutineContext.job
            synchronized(lock) { if (retry === me && wanted && current == null) connect() }
        }
    }

    /** What's still going, or showing, is unknown until the server says again. */
    private fun forgetRuns() {
        running.clear()
        landed.clear()
        showWriting()
        _overlays.value = emptyMap()
    }

    private fun showWriting() {
        _writing.value = running.filterKeys { it !in landed }.values.toSet()
    }

    private fun send(m: JsonObject) {
        // Before hello, nothing is lost: hello says again what's open.
        if (_connected.value) current?.socket?.send(m.toString())
    }

    private fun heard(attempt: Attempt, text: String) {
        val m = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = m.string("type")
        when {
            type == "hello" && m.string("connectionId") != null -> {
                failures = 0
                _connected.value = true
                for (a in answers.values) send(a)
                answers.clear()
                for (noteId in openCount.keys) send(opened(noteId))
                // Only for a note still open here; one past its wait has been dropped already.
                for (h in held.values) {
                    h.expires?.cancel()
                    if (h.noteId in openCount) {
                        sendRequest(attempt, h.trigger, h.noteId, h.spaceId)
                    } else {
                        _dropped.tryEmit(DroppedRun(h.trigger, h.noteId, h.spaceId, DroppedRun.Why.Unreachable))
                    }
                }
                held.clear()
            }

            type == "overlays" -> {
                val noteId = m.string("noteId") ?: return
                // Every editor of the account hears it: only one with the note open keeps it.
                if (noteId !in openCount) return
                val list = (m["overlays"] as? JsonArray)?.mapNotNull {
                    runCatching { json.decodeFromJsonElement<Overlay>(it) }.getOrNull()
                }?.filter { it.kind == "card" || (it.kind == "suggestion" && it.change != null) } ?: return
                setOverlays(noteId, list)
            }

            type == "run" -> runCatching {
                json.decodeFromJsonElement<RunUpdate>(m)
            }.getOrNull()?.let { u ->
                if (u.status == "running") {
                    running[u.runId] = u.noteId
                } else {
                    running.remove(u.runId)
                    landed.remove(u.runId)
                }
                showWriting()
                _runs.tryEmit(u)
            }

            m.string("id") != null -> scope.launch {
                val reply = answer(m)
                synchronized(lock) {
                    // Ids are per connection: on a newer one this would answer another request.
                    if (current !== attempt) return@launch
                    send(result(m.string("id")!!, reply))
                    // Its summary still to come, the run is done as far as the user can tell.
                    val runId = m.string("runId")
                    if (reply is EditorReply.Applied && runId != null && runId in running) {
                        landed += runId
                        showWriting()
                    }
                }
            }
        }
    }

    private suspend fun answer(m: JsonObject): EditorReply {
        val noteId = m.string("noteId") ?: return NOT_OPEN
        return try {
            when (m.string("type")) {
                "read" -> editors.read(noteId)

                "apply" -> {
                    val before = m["before"]?.let { json.decodeFromJsonElement<Fields>(it) } ?: return NOT_OPEN
                    val after = m["after"]?.let { json.decodeFromJsonElement<Fields>(it) } ?: return NOT_OPEN
                    // Without its op, it can only be made while the editor still shows before.
                    val op = m["op"]?.let { runCatching { json.decodeFromJsonElement<AgentOp>(it) }.getOrNull() }
                    editors.apply(noteId, m.string("runId").orEmpty(), AgentChange(before, after, op ?: AgentOp("")))
                }

                // A request this app doesn't know: the server reads its own copy instead of waiting.
                else -> NOT_OPEN
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            NOT_OPEN
        }
    }

    private fun result(id: String, reply: EditorReply) = buildJsonObject {
        put("type", "result")
        put("id", id)
        when (reply) {
            is EditorReply.Shown -> for ((k, v) in note(reply)) put(k, v)

            is EditorReply.Applied -> {
                put("before", fields(reply.before))
                put("revision", reply.revision)
                put("shows", fields(reply.shows))
            }

            is EditorReply.Refused -> put("error", reply.error)
        }
    }

    private fun note(shown: EditorReply.Shown) = buildJsonObject {
        put("spaceId", shown.spaceId)
        put("title", shown.note.title)
        put("content", shown.note.content)
        put("cursor", shown.cursor)
        put("revision", shown.revision)
    }

    private fun fields(f: Fields): JsonElement = json.encodeToJsonElement(f)

    private class Held(val trigger: String, val noteId: String, val spaceId: String) {
        var expires: Job? = null
    }

    private inner class Attempt : WebSocketListener() {
        var job: Job? = null

        /** Known from the first callback, or once [open] returns, whichever is first. */
        var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) = seen(webSocket)

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (current !== this) return
            socket = webSocket
            heard(this, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL, null)
            gone()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = gone()

        private fun seen(webSocket: WebSocket) = synchronized(lock) { if (current === this) socket = webSocket }

        private fun gone() = synchronized(lock) { if (current === this) lost() }
    }

    private companion object {
        const val NORMAL = 1000
        const val MAX_DOUBLINGS = 16
        val NOT_OPEN = EditorReply.Refused("not_open")
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = true
        }

        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull
    }
}
