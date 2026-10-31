package notes.core.sync

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.Credentials
import notes.core.api.Tokens
import notes.core.db.Database
import notes.core.db.Note
import notes.core.db.NoteStore
import notes.core.db.SyncEvent
import notes.core.images.ImageStore
import okhttp3.Interceptor
import okhttp3.Request

/** What a test compares: a live note's user-visible state. */
data class NoteView(val id: String, val title: String, val content: String, val conflictOf: String?)

/**
 * One device: its own database, session and sync engine against [server],
 * plus switches to make the network misbehave.
 */
class TestClient(private val server: TestServer, private val pageSize: Int = 100) : AutoCloseable {
    private val dir: File = Files.createTempDirectory("notes-client").toFile()
    private var db = Database(File(dir, "notes.db").path)
    var notes = NoteStore(db)
        private set
    var images = newImages()
        private set
    var engine = newEngine()
        private set
    val events = ArrayList<SyncEvent>()

    /** The JSON bodies of note requests that went out, as "METHOD path" to body. */
    val sentNotes = ArrayList<Pair<String, String>>()

    /** Every request fails as if there were no network. */
    var offline = false

    private var faults = ArrayList<Fault>()

    private class Fault(val match: (Request) -> Boolean, val kind: Kind, val action: () -> Unit = {}) {
        enum class Kind { FailBefore, LoseResponse, Before }
    }

    /** The next matching request never reaches the server. */
    fun failNext(match: (Request) -> Boolean) {
        faults += Fault(match, Fault.Kind.FailBefore)
    }

    /** The next matching request reaches the server, but its response is lost. */
    fun loseNextResponse(match: (Request) -> Boolean) {
        faults += Fault(match, Fault.Kind.LoseResponse)
    }

    fun clearFaults() = faults.clear()

    /** Runs [action] just before the next matching request goes out. */
    fun beforeNext(match: (Request) -> Boolean, action: () -> Unit) {
        faults += Fault(match, Fault.Kind.Before, action)
    }

    private val http = ApiClient.defaultHttp.newBuilder().addInterceptor(
        Interceptor { chain ->
            val request = chain.request()
            if (offline) throw IOException("offline")
            val fault = faults.firstOrNull { it.match(request) }?.also { faults.remove(it) }
            when (fault?.kind) {
                Fault.Kind.FailBefore -> throw IOException("connection dropped")
                Fault.Kind.Before -> fault.action()
                else -> Unit
            }
            val path = request.url.encodedPath
            if (path.startsWith("/api/v1/notes") && request.body?.contentType()?.subtype == "json") {
                val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                sentNotes += "${request.method} $path" to body
            }
            val response = chain.proceed(request)
            if (fault?.kind == Fault.Kind.LoseResponse) {
                response.close()
                throw IOException("response lost")
            }
            response
        }
    ).build()

    private val credentials = object : Credentials {
        var tokens: Tokens? = null

        override suspend fun tokens() = tokens

        override suspend fun refreshed(tokens: Tokens) {
            this.tokens = tokens
        }

        override suspend fun revoked() {
            tokens = null
        }
    }

    val api get() = ApiClient(server.url, credentials, http)

    fun mailer() = NoteMailer(notes, engine) { api }

    private fun newImages() = ImageStore(db, File(dir, "images"), File(dir, "cache/images"), { api })

    private fun newEngine() = SyncEngine(db, { api }, pageSize, images = images)

    suspend fun signIn(): TestClient {
        val r = ApiClient(server.url, http = http).login(TestServer.USERNAME, TestServer.PASSWORD, "test")
        credentials.tokens = (r as ApiResult.Ok).value.tokens
        return this
    }

    /** One sync cycle, collecting what it tells the user into [events]. */
    suspend fun sync(): SyncOutcome = coroutineScope {
        val listener = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        try {
            engine.sync()
        } finally {
            yield() // let the listener take events emitted at the very end
            listener.cancel()
        }
    }

    /** Like killing the app and starting it again: new database connections and engine, same files. */
    fun restart() {
        db.close()
        db = Database(File(dir, "notes.db").path)
        notes = NoteStore(db)
        images = newImages()
        engine = newEngine()
    }

    suspend fun save(id: String, title: String, content: String): Note? = notes.save(id, title, content)

    suspend fun live(): Set<NoteView> = notes.list().map { s ->
        val n = notes.get(s.id)!!
        NoteView(n.id, n.title, n.content, n.conflictOf)
    }.toSet()

    suspend fun note(id: String): Note? = notes.get(id)

    override fun close() {
        db.close()
        dir.deleteRecursively()
    }
}
