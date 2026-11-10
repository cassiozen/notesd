package notes.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import notes.core.api.ApiResult
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** The socket's life and messages, over a stand-in connection. Against the real server: AgentSocketServerTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentSocketTest {
    private class FakeSocket(val listener: WebSocketListener) : WebSocket {
        val sent = ArrayList<JsonObject>()
        var closed = false

        override fun request(): Request = Request.Builder().url("http://localhost/").build()

        override fun queueSize() = 0L

        override fun send(text: String): Boolean {
            sent += Json.parseToJsonElement(text).jsonObject
            return true
        }

        override fun send(bytes: ByteString) = false

        override fun close(code: Int, reason: String?): Boolean {
            closed = true
            return true
        }

        override fun cancel() {
            closed = true
        }

        fun hear(text: String) = listener.onMessage(this, text)

        fun hello() = hear("""{"type":"hello","connectionId":"c1","protocol":1}""")

        fun drop() = listener.onFailure(this, java.io.IOException("gone"), null)

        fun types() = sent.map { it["type"].toString().trim('"') }
    }

    private val sockets = ArrayList<FakeSocket>()

    private open class Editors : AgentEditors {
        override suspend fun read(noteId: String): EditorReply =
            EditorReply.Shown("default", Fields("Plan", "Milk\n"), 2, 7)

        override suspend fun apply(noteId: String, runId: String, change: AgentChange): EditorReply =
            EditorReply.Applied(change.before, 7, change.after)
    }

    private fun TestScope.socket(editors: AgentEditors = Editors()) = AgentSocket(
        open = { listener -> ApiResult.Ok(FakeSocket(listener).also { sockets += it }) },
        editors = editors,
        scope = backgroundScope,
        jitter = { 1.0 }
    )

    @Test
    fun `nothing goes before hello, which hears again what's open, and opens and closes count per note`() = runTest {
        val s = socket()
        s.opened("a", "work")
        s.start()
        runCurrent()
        val ws = sockets.single()
        assertEquals(emptyList(), ws.sent)

        ws.hello()
        assertTrue(s.connected.value)
        assertEquals(
            listOf("""{"type":"editor.opened","noteId":"a","spaceId":"work"}"""),
            ws.sent.map {
                it.toString()
            }
        )
        s.opened("a", "work")
        s.closed("a", 3)
        s.closed("a", 4)
        s.closed("a", 5)
        assertEquals(listOf("editor.opened", "editor.opened"), ws.types().take(2))
        assertEquals(
            listOf("""{"type":"editor.closed","noteId":"a","revision":4,"spaceId":"work"}"""),
            ws.sent.drop(2).map { it.toString() }
        )
    }

    @Test
    fun `a dropped connection is tried again, waiting longer each time, and only while online`() = runTest {
        val s = socket()
        s.start()
        runCurrent()
        sockets.last().drop()
        advanceTimeBy(999)
        assertEquals(1, sockets.size)
        advanceTimeBy(2)
        assertEquals(2, sockets.size)
        sockets.last().drop()
        advanceTimeBy(1_999)
        assertEquals(2, sockets.size)
        advanceTimeBy(2)
        assertEquals(3, sockets.size)

        // Offline, the retry finds no network and waits for it to come back.
        s.online(false)
        sockets.last().drop()
        advanceTimeBy(60_000)
        assertEquals(3, sockets.size)
        s.online(true)
        runCurrent()
        assertEquals(4, sockets.size)

        // Hello starts the waits over.
        sockets.last().hello()
        sockets.last().drop()
        advanceTimeBy(1_001)
        assertEquals(5, sockets.size)
    }

    @Test
    fun `stopped, it closes once the open notes said they closed, or after the wait`() = runTest {
        val s = socket()
        s.opened("a", "work")
        s.start()
        runCurrent()
        val ws = sockets.single()
        ws.hello()

        s.stop()
        assertFalse(ws.closed)
        s.closed("a", 3)
        assertTrue(ws.closed)
        assertEquals("editor.closed", ws.types().last())

        s.opened("b", "work")
        s.start()
        runCurrent()
        sockets.last().hello()
        s.stop()
        advanceTimeBy(4_999)
        // Started again meanwhile: it carries on.
        s.start()
        advanceTimeBy(10_000)
        assertFalse(sockets.last().closed)
        s.stop()
        advanceTimeBy(5_001)
        assertTrue(sockets.last().closed)
        assertEquals(2, sockets.size)

        // Not waiting: closed with the note still open.
        s.start()
        runCurrent()
        sockets.last().hello()
        s.stop(waitForCloses = false)
        assertTrue(sockets.last().closed)
    }

    @Test
    fun `a reset closes at once, and an editor closing afterwards says nothing`() = runTest {
        val s = socket()
        s.opened("a", "work")
        s.start()
        runCurrent()
        sockets.single().hello()
        s.reset()
        assertTrue(sockets.single().closed)
        assertFalse(s.connected.value)

        s.start()
        runCurrent()
        sockets.last().hello()
        s.closed("a", 3)
        assertEquals(emptyList(), sockets.last().sent)
    }

    @Test
    fun `reads and applies are answered by the editors, and anything else as not open`() = runTest {
        val got = CompletableDeferred<AgentChange>()
        val s = socket(object : Editors() {
            override suspend fun apply(noteId: String, runId: String, change: AgentChange): EditorReply {
                got.complete(change)
                return super.apply(noteId, runId, change)
            }

            override suspend fun read(noteId: String): EditorReply =
                if (noteId == "locked") EditorReply.Refused("locked") else super.read(noteId)
        })
        s.start()
        runCurrent()
        val ws = sockets.single()
        ws.hello()
        ws.hear("""{"type":"read","id":"r1","noteId":"a"}""")
        ws.hear("""{"type":"read","id":"r2","noteId":"locked"}""")
        ws.hear(
            """{"type":"apply","id":"r3","noteId":"a","runId":"run1","before":{"title":"P","content":"x\n"},""" +
                """"after":{"title":"P","content":"y\n"},"op":{"kind":"replace","find":"x","replace":"y"}}"""
        )
        ws.hear("""{"type":"sing","id":"r4","noteId":"a"}""")
        runCurrent()

        assertEquals(AgentOp("replace", find = "x", replace = "y"), got.await().op)
        assertEquals(
            listOf(
                """{"type":"result","id":"r1","spaceId":"default","title":"Plan","content":"Milk\n","cursor":2,"revision":7}""",
                """{"type":"result","id":"r2","error":"locked"}""",
                """{"type":"result","id":"r3","before":{"title":"P","content":"x\n"},"revision":7,""" +
                    """"shows":{"title":"P","content":"y\n"}}""",
                """{"type":"result","id":"r4","error":"not_open"}"""
            ),
            ws.sent.map { it.toString() }
        )
    }

    @Test
    fun `an editor that fails says not open, and an answer for a dropped connection isn't sent on the next`() =
        runTest {
            val slow = CompletableDeferred<Unit>()
            val s = socket(object : Editors() {
                override suspend fun read(noteId: String): EditorReply {
                    if (noteId == "slow") slow.await()
                    if (noteId == "broken") error("view gone")
                    return super.read(noteId)
                }
            })
            s.start()
            runCurrent()
            sockets.single().hello()
            sockets.single().hear("""{"type":"read","id":"r1","noteId":"broken"}""")
            sockets.single().hear("""{"type":"read","id":"r2","noteId":"slow"}""")
            runCurrent()
            assertEquals(
                """{"type":"result","id":"r1","error":"not_open"}""",
                sockets.single().sent.single().toString()
            )

            sockets.single().drop()
            advanceTimeBy(1_001)
            sockets.last().hello()
            slow.complete(Unit)
            runCurrent()
            assertEquals(emptyList(), sockets.last().sent)
        }

    @Test
    fun `run updates are heard, and what isn't one ignored`() = runTest {
        val s = socket()
        val heard = ArrayList<RunUpdate>()
        backgroundScope.launch { s.runs.toList(heard) }
        s.start()
        runCurrent()
        val ws = sockets.single()
        ws.hello()
        ws.hear("""{"type":"run","runId":"r1","noteId":"a","trigger":"menu","status":"running"}""")
        ws.hear("not json")
        ws.hear("""{"type":"run","runId":"r1"}""")
        ws.hear("""{"type":"run","runId":"r1","noteId":"a","trigger":"menu","status":"failed","failure":"timeout"}""")
        runCurrent()
        assertEquals(
            listOf(
                RunUpdate("r1", "a", "menu", "running"),
                RunUpdate("r1", "a", "menu", "failed", failure = "timeout")
            ),
            heard
        )
    }

    @Test
    fun `a run asked for carries what the editor shows, or nothing for a note it won't read`() = runTest {
        val s = socket(object : Editors() {
            override suspend fun read(noteId: String): EditorReply =
                if (noteId == "locked") EditorReply.Refused("locked") else super.read(noteId)
        })
        s.start()
        runCurrent()
        val ws = sockets.single()
        ws.hello()
        assertTrue(s.requestRun("menu", "a", "default"))
        assertTrue(s.requestRun("editStarted", "locked", "default"))
        runCurrent()
        assertEquals(
            listOf(
                """{"type":"run.request","trigger":"menu","noteId":"a","spaceId":"default",""" +
                    """"note":{"spaceId":"default","title":"Plan","content":"Milk\n","cursor":2,"revision":7}}""",
                """{"type":"run.request","trigger":"editStarted","noteId":"locked","spaceId":"default"}"""
            ),
            ws.sent.map { it.toString() }
        )
    }

    @Test
    fun `a run asked for while connecting is sent after hello, if its note is still open and in time`() = runTest {
        val s = socket()
        val dropped = ArrayList<DroppedRun>()
        backgroundScope.launch { s.dropped.toList(dropped) }
        s.opened("a", "work")
        s.start()
        runCurrent()
        assertTrue(s.requestRun("noteCreated", "a", "work"))
        // Asked again: one request.
        assertTrue(s.requestRun("noteCreated", "a", "work"))
        assertTrue(s.requestRun("menu", "gone", "work"))
        runCurrent()
        assertEquals(emptyList(), sockets.single().sent)

        advanceTimeBy(9_000)
        sockets.single().hello()
        runCurrent()
        assertEquals(listOf("editor.opened", "run.request"), sockets.single().types())
        assertEquals(listOf(DroppedRun("menu", "gone", "work", DroppedRun.Why.Unreachable)), dropped)

        // Too late: the moment it was for has passed.
        sockets.single().drop()
        assertTrue(s.requestRun("noteCreated", "a", "work"))
        advanceTimeBy(10_001)
        sockets.last().hello()
        runCurrent()
        assertEquals(listOf("editor.opened"), sockets.last().types())
        assertEquals(DroppedRun("noteCreated", "a", "work", DroppedRun.Why.Unreachable), dropped.last())
    }

    @Test
    fun `with no socket wanted, a run asked for is dropped, and stopping drops those held`() = runTest {
        val s = socket()
        val dropped = ArrayList<DroppedRun>()
        backgroundScope.launch { s.dropped.toList(dropped) }
        runCurrent()
        assertFalse(s.requestRun("menu", "a", "work"))
        s.start()
        runCurrent()
        assertTrue(s.requestRun("editStarted", "b", "work"))
        s.stop()
        runCurrent()
        assertEquals(
            listOf(
                DroppedRun("menu", "a", "work", DroppedRun.Why.Off),
                DroppedRun("editStarted", "b", "work", DroppedRun.Why.Off)
            ),
            dropped
        )

        // Reset: another account's, so not said.
        s.start()
        runCurrent()
        s.requestRun("editStarted", "b", "work")
        s.reset()
        advanceTimeBy(20_000)
        assertEquals(2, dropped.size)
    }

    @Test
    fun `a note is written in from its run going until the change is in the editor, or the run ends`() = runTest {
        val s = socket()
        s.start()
        runCurrent()
        val ws = sockets.single()
        ws.hello()
        ws.hear("""{"type":"run","runId":"r1","noteId":"a","trigger":"menu","status":"running"}""")
        ws.hear("""{"type":"run","runId":"r2","noteId":"b","trigger":"menu","status":"running"}""")
        assertEquals(setOf("a", "b"), s.writing.value)

        ws.hear(
            """{"type":"apply","id":"x1","noteId":"a","runId":"r1","before":{"title":"P","content":"x\n"},""" +
                """"after":{"title":"P","content":"y\n"}}"""
        )
        runCurrent()
        assertEquals(setOf("b"), s.writing.value)
        ws.hear("""{"type":"run","runId":"r2","noteId":"b","trigger":"menu","status":"failed","failure":"timeout"}""")
        assertEquals(emptySet(), s.writing.value)

        // A dropped connection: what's still going is unknown.
        ws.hear("""{"type":"run","runId":"r3","noteId":"a","trigger":"menu","status":"running"}""")
        ws.drop()
        assertEquals(emptySet(), s.writing.value)
    }

    @Test
    fun `a connection that can't be made is tried again`() = runTest {
        var tries = 0
        val s = AgentSocket(
            open = { listener ->
                if (++tries == 1) {
                    ApiResult.Unreachable(java.io.IOException("down"))
                } else {
                    ApiResult.Ok(FakeSocket(listener).also { sockets += it })
                }
            },
            editors = Editors(),
            scope = backgroundScope,
            jitter = { 1.0 }
        )
        s.start()
        runCurrent()
        assertEquals(0, sockets.size)
        advanceTimeBy(1_001)
        assertEquals(1, sockets.size)
    }
}
