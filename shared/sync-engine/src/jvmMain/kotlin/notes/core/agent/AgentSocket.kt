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
    /** Reconnect backoff, and how long a socket no longer wanted waits for the open notes to say they closed. */
    data class Timing(val retryBaseMs: Long = 1_000, val retryMaxMs: Long = 30_000, val closeWaitMs: Long = 5_000)

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

    private val _connected = MutableStateFlow(false)

    /** The server said hello: requests and run updates can come. */
    val connected: StateFlow<Boolean> = _connected

    private val _runs = MutableSharedFlow<RunUpdate>(extraBufferCapacity = 64)

    /** Runs starting and ending, while connected. Best-effort, as the server sends them. */
    val runs: SharedFlow<RunUpdate> = _runs

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
        drop()
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
        attempt.job?.cancel()
        attempt.socket?.close(NORMAL, null)
    }

    /** Closed by the server or the network: tried again while wanted. */
    private fun lost() {
        current = null
        _connected.value = false
        if (!wanted) return
        val wait = min(timing.retryMaxMs, timing.retryBaseMs shl min(failures++, MAX_DOUBLINGS))
        retry = scope.launch {
            // Each wait at 50–100%, so a server's restart isn't met by every app at once.
            delay((wait * (0.5 + jitter() / 2)).toLong())
            val me = coroutineContext.job
            synchronized(lock) { if (retry === me && wanted && current == null) connect() }
        }
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
                for (noteId in openCount.keys) send(opened(noteId))
            }

            type == "run" -> runCatching {
                json.decodeFromJsonElement<RunUpdate>(m)
            }.getOrNull()?.let { _runs.tryEmit(it) }

            m.string("id") != null -> scope.launch {
                val reply = answer(m)
                synchronized(lock) {
                    // Ids are per connection: on a newer one this would answer another request.
                    if (current === attempt) send(result(m.string("id")!!, reply))
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
            is EditorReply.Shown -> {
                put("spaceId", reply.spaceId)
                put("title", reply.note.title)
                put("content", reply.note.content)
                put("cursor", reply.cursor)
                put("revision", reply.revision)
            }

            is EditorReply.Applied -> {
                put("before", fields(reply.before))
                put("revision", reply.revision)
                put("shows", fields(reply.shows))
            }

            is EditorReply.Refused -> put("error", reply.error)
        }
    }

    private fun fields(f: Fields): JsonElement = json.encodeToJsonElement(f)

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
