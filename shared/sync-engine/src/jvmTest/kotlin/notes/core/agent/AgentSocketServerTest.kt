package notes.core.agent

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import notes.core.api.AgentRun
import notes.core.api.AiSettingsInput
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.Credentials
import notes.core.api.SignIn
import notes.core.api.Tokens
import notes.core.sync.TestServer

/** The socket against a real notesd, with a stand-in for the model: what the server asks is what it answers. */
class AgentSocketServerTest {
    private val server = TestServer()
    private val model = FakeModel()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Session(var tokens: Tokens?) : Credentials {
        override suspend fun tokens() = tokens

        override suspend fun refreshed(tokens: Tokens) {
            this.tokens = tokens
        }

        override suspend fun revoked() {
            tokens = null
        }
    }

    private val api = runBlocking {
        val signIn = ApiClient(server.url).login(TestServer.USERNAME, TestServer.PASSWORD, "Test")
        ApiClient(server.url, Session(assertIs<ApiResult.Ok<SignIn>>(signIn).value.tokens))
    }

    /** An editor showing [shows] for every note, until told otherwise. */
    private class Editor(@Volatile var shows: Fields?) : AgentEditors {
        val applied = CopyOnWriteArrayList<AgentChange>()

        override suspend fun read(noteId: String): EditorReply =
            shows?.let { EditorReply.Shown("default", it, null, null) } ?: EditorReply.Refused("not_open")

        override suspend fun apply(noteId: String, runId: String, change: AgentChange): EditorReply {
            val now = shows ?: return EditorReply.Refused("not_open")
            val after = madeTo(change, now, null) ?: return EditorReply.Refused("changed")
            applied += change
            shows = after
            return EditorReply.Applied(now, null, after)
        }
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        model.close()
        server.close()
    }

    private fun socket(editor: AgentEditors) = AgentSocket(
        open = api::agentSocket,
        editors = editor,
        scope = scope,
        timing = AgentSocket.Timing(retryBaseMs = 100, retryMaxMs = 400)
    )

    private suspend fun agentOn(trigger: String, prompt: String) {
        assertIs<ApiResult.Ok<*>>(api.saveAiSettings(AiSettingsInput(model.url, "m", "sk-test-1234", null)))
        assertIs<ApiResult.Ok<*>>(api.putAgentConfig("default", true, "p", "", mapOf(trigger to prompt), null))
    }

    /** As a device's first sync puts it, starting no writing session. */
    private suspend fun note(id: String, title: String, content: String) {
        val body = buildJsonObject {
            put("operationId", "op-$id")
            put("id", id)
            put("title", title)
            put("content", content)
        }
        assertIs<ApiResult.Ok<*>>(api.createNote(body, "initial"))
    }

    private suspend fun runs(): List<AgentRun> =
        assertIs<ApiResult.Ok<notes.core.api.RunsPage>>(api.agentRuns()).value.runs

    private suspend fun until(timeoutMs: Long = 5_000, what: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!what()) delay(50)
    }

    @Test
    fun `a note closed with its revision ends its writing session at once`() = runBlocking {
        agentOn("editDone", "Tidy up.")
        note("plan", "Plan", "Step one\n")
        val s = socket(Editor(null))
        s.opened("plan", "default")
        s.start()
        s.connected.first { it }

        val edit = buildJsonObject {
            put("operationId", "op-edit")
            put("baseRevision", 1)
            put("title", "Plan")
            put("content", "Step one, step two\n")
        }
        val revision = assertIs<ApiResult.Ok<notes.core.api.MutationResult>>(
            api.updateNote("plan", edit, null)
        ).value.revision
        assertEquals(emptyList(), runs())
        // Long before the two idle minutes that end it otherwise.
        s.closed("plan", revision)
        until { runs().map { it.trigger to it.noteId } == listOf("editDone" to "plan") }
    }

    @Test
    fun `a run reads the note as its editor shows it, and changes it there`() = runBlocking {
        agentOn("noteDeleted", "Add it to Digest.")
        note("digest", "Digest", "Items\n")
        note("milk", "Milk", "Milk\n")
        // Typed, not saved: only the editor has it.
        val editor = Editor(Fields("Digest", "Items and more\n"))
        val s = socket(editor)
        val heard = CopyOnWriteArrayList<RunUpdate>()
        scope.launch { s.runs.collect { heard += it } }
        s.opened("digest", "default")
        s.start()
        s.connected.first { it }

        model.script += Step.Tool("edit_note", """{"id":"digest","find":"more","replace":"more\n\n- Milk"}""")
        model.script += Step.Text("Added Milk.")
        deleteMilk()

        until { editor.applied.isNotEmpty() }
        assertEquals(Fields("Digest", "Items and more\n\n- Milk\n"), editor.shows)
        // What the run read: the editor's, not the server's "Items".
        assertEquals(Fields("Digest", "Items and more\n"), editor.applied.single().before)
        until { heard.any { it.status == "done" } }
        assertEquals(listOf("running", "done"), heard.map { it.status })
        // The run is about the note deleted; it wrote to another.
        assertEquals("noteDeleted" to "milk", heard.first().trigger to heard.first().noteId)
    }

    @Test
    fun `a new note's run, asked for while connecting, works on it as its editor shows it`() = runBlocking {
        agentOn("noteCreated", "Start the entry.")
        // Not on the server yet: only the editor has it.
        val editor = Editor(Fields("Journal", "Day one\n"))
        val s = socket(editor)
        val heard = CopyOnWriteArrayList<RunUpdate>()
        scope.launch { s.runs.collect { heard += it } }
        s.opened("j1", "default")
        model.script += Step.Tool("edit_note", """{"id":"j1","find":"Day one","replace":"Day one\n\n- Woke up"}""")
        model.script += Step.Text("Started it.")
        s.start()
        s.requestRun("noteCreated", "j1", "default")

        until { editor.applied.isNotEmpty() }
        assertEquals(Fields("Journal", "Day one\n\n- Woke up\n"), editor.shows)
        assertEquals(emptySet(), s.writing.value)
        until { heard.any { it.status == "done" } }
        assertEquals("noteCreated" to "j1", heard.first().trigger to heard.first().noteId)
    }

    @Test
    fun `an editor that hasn't the note says so, and the run uses the server's copy`() = runBlocking {
        agentOn("noteDeleted", "Add it to Digest.")
        note("digest", "Digest", "Items\n")
        note("milk", "Milk", "Milk\n")
        val s = socket(Editor(null))
        s.opened("digest", "default")
        s.start()
        s.connected.first { it }

        model.script += Step.Tool("edit_note", """{"id":"digest","find":"Items","replace":"Items\n\n- Milk"}""")
        model.script += Step.Text("Added Milk.")
        deleteMilk()

        until { runs().firstOrNull()?.status == "done" }
        val page = assertIs<ApiResult.Ok<notes.core.api.SyncPage>>(api.sync(0, 100, null)).value
        assertEquals("Items\n\n- Milk\n", page.changes.single { it.id == "digest" }.content)
    }

    @Test
    fun `after the server restarts it connects again and says which notes are open`() = runBlocking {
        agentOn("noteDeleted", "Add it to Digest.")
        note("digest", "Digest", "Items\n")
        note("milk", "Milk", "Milk\n")
        val editor = Editor(Fields("Digest", "Items\n"))
        val s = socket(editor)
        s.opened("digest", "default")
        s.start()
        s.connected.first { it }

        server.restart()
        s.connected.first { !it }
        withTimeout(10_000) { s.connected.first { it } }

        model.script += Step.Tool("edit_note", """{"id":"digest","find":"Items","replace":"Items\n\n- Milk"}""")
        model.script += Step.Text("Added Milk.")
        deleteMilk()
        until { editor.applied.isNotEmpty() }
        assertEquals("Items\n\n- Milk\n", editor.shows?.content)
    }

    private suspend fun deleteMilk() {
        val body = buildJsonObject {
            put("operationId", "op-del")
            put("baseRevision", 1)
        }
        assertIs<ApiResult.Ok<*>>(api.deleteNote("milk", body, null))
    }

    private sealed interface Step {
        data class Tool(val name: String, val args: String) : Step

        data class Text(val text: String) : Step
    }

    /** Stands in for an OpenAI-compatible API: an agent's turns, in order, then "No change." */
    private class FakeModel : AutoCloseable {
        val script = CopyOnWriteArrayList<Step>()
        private val calls = java.util.concurrent.atomic.AtomicInteger()
        private val web = MockWebServer()
        val url: String

        init {
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val call = calls.incrementAndGet()
                    val step = synchronized(script) { if (script.isEmpty()) null else script.removeAt(0) }
                    val message = buildJsonObject {
                        put("role", "assistant")
                        when (step) {
                            is Step.Tool -> putJsonArray("tool_calls") {
                                add(
                                    buildJsonObject {
                                        put("id", "call_$call")
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", step.name)
                                            put("arguments", step.args)
                                        }
                                    }
                                )
                            }

                            is Step.Text -> put("content", step.text)

                            null -> put("content", "No change.")
                        }
                    }
                    val answer = buildJsonObject {
                        put("model", "fake-model-1")
                        put("choices", buildJsonArray { add(buildJsonObject { put("message", message) }) })
                        putJsonObject("usage") {
                            put("prompt_tokens", 10)
                            put("completion_tokens", 2)
                        }
                    }
                    return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                        .body(answer.toString()).build()
                }
            }
            web.start()
            url = web.url("/v1").toString()
        }

        override fun close() = web.close()
    }
}
