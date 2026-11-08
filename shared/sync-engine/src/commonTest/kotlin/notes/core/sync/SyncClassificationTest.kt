package notes.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import notes.core.api.ApiResult
import notes.core.api.LockKey
import notes.core.api.MutationResult
import notes.core.api.RemoteInvite
import notes.core.api.RemoteSpace
import notes.core.api.SyncApi
import notes.core.api.SyncPage
import notes.core.api.UPGRADE_REQUIRED_MESSAGE
import notes.core.api.unexpectedMessage
import notes.core.crypto.LockedEnvelope
import notes.core.db.Database
import notes.core.db.NoteStore
import notes.core.db.SyncEvent
import notes.core.db.openTestDatabase

/** How sync treats each kind of answer to a queued change, against a server that answers as told. */
class SyncClassificationTest {
    /** Answers note writes with [answer] (by note id), everything else as an empty, healthy server would. */
    private class Stub : SyncApi {
        var answer: (String, JsonObject) -> ApiResult<MutationResult> = { id, _ -> ok(id) }
        var spaceAnswer: () -> ApiResult<RemoteSpace>? = { null }
        val sent = mutableListOf<Pair<String, String>>()
        var calls = 0

        fun ok(id: String) = ApiResult.Ok(MutationResult(id, "ok", 1, 1, 1, spaceId = "default"))

        private fun note(id: String, body: JsonObject): ApiResult<MutationResult> {
            calls++
            sent += id to body["operationId"]!!.jsonPrimitive.content
            return answer(id, body)
        }

        override suspend fun createNote(body: JsonObject) = note(body["id"]!!.jsonPrimitive.content, body)

        override suspend fun updateNote(id: String, body: JsonObject) = note(id, body)

        override suspend fun deleteNote(id: String, body: JsonObject) = note(id, body)

        override suspend fun sync(since: Long, limit: Int, generation: String?): ApiResult<SyncPage> {
            calls++
            return ApiResult.Ok(SyncPage(emptyList(), since, false, "g"))
        }

        override suspend fun lockKeys(): ApiResult<List<LockKey>> = ApiResult.Ok(emptyList())

        override suspend fun spaces(): ApiResult<List<RemoteSpace>> = ApiResult.Ok(emptyList())

        override suspend fun putSpace(id: String, name: String, icon: String): ApiResult<RemoteSpace> =
            spaceAnswer() ?: ApiResult.Ok(RemoteSpace(id, name, icon))

        override suspend fun deleteSpace(id: String): ApiResult<Unit> = ApiResult.Ok(Unit)

        override suspend fun putImage(id: String, bytes: ByteArray, type: String): ApiResult<Unit> = ApiResult.Ok(Unit)

        override fun inSpace(spaceId: String): SyncApi = this

        override suspend fun moveNote(id: String, body: JsonObject) = note(id, body)

        override suspend fun invites(): ApiResult<List<RemoteInvite>> = ApiResult.Ok(emptyList())
    }

    private class Env(val test: TestScope, val db: Database, val api: Stub) {
        val notes = NoteStore(db)
        val engine = SyncEngine(db, { api })

        /** As a version before 1.0 could leave it: notes changed here with nothing queued. */
        suspend fun dropOutbox() = db.write { it.exec("DELETE FROM sync_outbox") }

        fun events(): MutableList<SyncEvent> {
            val seen = mutableListOf<SyncEvent>()
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
                engine.events.collect { seen += it }
            }
            return seen
        }
    }

    private fun env(block: suspend Env.() -> Unit): TestResult = runTest {
        val t = openTestDatabase()
        try {
            Env(this, t.db, Stub()).block()
        } finally {
            t.close()
        }
    }

    private val portal = ApiResult.Unexpected(200)

    @Test
    fun anUnexpectedAnswerKeepsTheChangeAndParksItAfterThreeCycles() = env {
        notes.save("a", "A", "text")
        api.answer = { _, _ -> portal }
        repeat(3) {
            assertEquals(SyncOutcome.Failed, engine.sync())
            assertEquals(SyncStatus.Failed(unexpectedMessage(200)), engine.status.value)
        }
        assertEquals(1, engine.parked())
        assertEquals(1, notes.outbox().size)
        assertEquals(1, notes.pendingNotes())

        // Parked, it no longer holds up the notes behind it.
        notes.save("b", "B", "text")
        api.answer = { id, body -> if (id == "a") portal else api.ok(id) }
        assertEquals(SyncOutcome.Done, engine.sync())
        assertEquals(listOf("a"), notes.outbox().map { it.noteId })
        // That cycle sent something, so "a" is back in line; one more failure parks it again.
        assertEquals(0, engine.parked())
        assertEquals(SyncOutcome.Failed, engine.sync())
        assertEquals(1, engine.parked())

        engine.retryParked()
        assertEquals(0, engine.parked())
        api.answer = { id, _ -> api.ok(id) }
        assertEquals(SyncOutcome.Done, engine.sync())
        assertEquals(0, notes.pendingNotes())
    }

    @Test
    fun anUnknownErrorCodeCountsAsUnexpected() = env {
        notes.save("a", "A", "text")
        api.answer = { _, _ -> ApiResult.Rejected(400, "something_new", "", null) }
        assertEquals(SyncOutcome.Failed, engine.sync())
        assertEquals(SyncStatus.Failed(unexpectedMessage(400)), engine.status.value)
        assertEquals(1, notes.outbox().size)
    }

    @Test
    fun serverErrorsDontCountTowardParking() = env {
        notes.save("a", "A", "text")
        api.answer = { _, _ -> ApiResult.Rejected(502, "http_502", "", null) }
        repeat(4) { assertEquals(SyncOutcome.Failed, engine.sync()) }
        assertEquals(0, engine.parked())
    }

    @Test
    fun aPermanentRefusalDropsTheChangeAndSaysSo() = env {
        notes.save("a", "Big", "text")
        api.answer = { _, _ -> ApiResult.Rejected(400, "invalid_request", "content is too long", "content") }
        val events = events()
        assertEquals(SyncOutcome.Done, engine.sync())
        assertEquals(0, notes.outbox().size)
        val message = "“Big” wasn’t saved on the server: content is too long"
        assertEquals(message, engine.store.state().lastError)
        assertTrue(SyncEvent.Rejected(message) in events)
        // The note keeps the edit, and still counts as not synced.
        assertEquals(1, notes.pendingNotes())
    }

    @Test
    fun aConflictWithoutTheServersVersionKeepsTheChange() = env {
        notes.save("a", "A", "text")
        api.answer = { _, _ -> ApiResult.Conflict("conflict", "revision mismatch", null) }
        assertEquals(SyncOutcome.Failed, engine.sync())
        assertEquals(1, notes.outbox().size)
    }

    @Test
    fun aReusedOperationIdIsSentOnceMoreUnderANewOne() = env {
        notes.save("a", "A", "text")
        var first = true
        api.answer = { id, _ ->
            if (first) {
                first = false
                ApiResult.Rejected(422, "operation_id_reused", "", null)
            } else {
                api.ok(id)
            }
        }
        assertEquals(SyncOutcome.Done, engine.sync())
        assertEquals(2, api.sent.size)
        assertNotEquals(api.sent[0].second, api.sent[1].second)
        assertEquals(0, notes.pendingNotes())

        // Reused again: not a coincidence.
        notes.save("b", "B", "text")
        api.answer = { _, _ -> ApiResult.Rejected(422, "operation_id_reused", "", null) }
        assertEquals(SyncOutcome.Failed, engine.sync())
        assertEquals(SyncStatus.Failed(unexpectedMessage(422)), engine.status.value)
        assertEquals(1, notes.outbox().size)
    }

    @Test
    fun aDeleteOfANoteTheServerDoesntHaveIsDone() = env {
        notes.save("a", "A", "text")
        assertEquals(SyncOutcome.Done, engine.sync())
        notes.delete("a")
        api.answer = { _, _ -> ApiResult.Rejected(404, "not_found", "note not found", null) }
        assertEquals(SyncOutcome.Done, engine.sync())
        assertEquals(0, notes.pendingNotes())
    }

    @Test
    fun anUpgradeStopsSyncKeepsEverythingAndIsntRetried() = env {
        notes.save("a", "A", "text")
        api.answer = { _, _ -> ApiResult.UpgradeRequired("1.2.0") }
        assertEquals(SyncOutcome.UpgradeRequired, engine.sync())
        assertEquals(SyncStatus.UpgradeRequired("1.2.0"), engine.status.value)
        assertEquals(UPGRADE_REQUIRED_MESSAGE, engine.store.state().lastError)
        assertEquals(1, notes.outbox().size)
        assertEquals(0, engine.parked())
        val calls = api.calls
        assertEquals(SyncOutcome.UpgradeRequired, engine.sync())
        assertEquals(calls, api.calls)
    }

    @Test
    fun openingQueuesEditsOlderVersionsDropped() = env {
        notes.save("a", "A", "text")
        notes.save("gone", "G", "text")
        assertEquals(SyncOutcome.Done, engine.sync())
        notes.save("a", "A", "edited")
        notes.delete("gone")
        // As a version before 1.0 left them: dirty, nothing queued.
        dropOutbox()
        assertEquals(2, notes.pendingNotes())
        val fresh = SyncEngine(db, { api })
        fresh.open()
        assertEquals(setOf("a" to "update", "gone" to "delete"), notes.outbox().map { it.noteId to it.type }.toSet())
        assertEquals(SyncOutcome.Done, fresh.sync())
        assertEquals(0, notes.pendingNotes())
    }

    @Test
    fun aSpaceChangeIsParkedOrDroppedLikeANote() = env {
        engine.spaces.create("Work", id = "w")
        api.spaceAnswer = { portal }
        repeat(3) { assertEquals(SyncOutcome.Failed, engine.sync()) }
        assertEquals(1, engine.parked())
        // Parked, the notes go.
        notes.save("a", "A", "text")
        assertEquals(SyncOutcome.Done, engine.sync())
        assertEquals(0, notes.pendingNotes())

        api.spaceAnswer = { ApiResult.Rejected(400, "invalid_request", "name taken", "name") }
        engine.retryParked()
        assertEquals(SyncOutcome.Done, engine.sync())
        assertEquals(0, engine.parked())
        assertEquals("The space “Work” wasn’t saved: name taken", engine.store.state().lastError)
    }

    @Test
    fun aNoteWaitsForItsParkedSpace() = env {
        engine.spaces.create("Work", id = "w")
        api.spaceAnswer = { portal }
        repeat(3) { assertEquals(SyncOutcome.Failed, engine.sync()) }
        notes.save("a", "In work", "text", spaceId = "w")
        notes.save("b", "Elsewhere", "text")
        // Sent now, "a" would be filed in the default space.
        assertEquals(SyncOutcome.Done, engine.sync())
        assertEquals(listOf("b"), api.sent.map { it.first })
        assertEquals(listOf("a"), notes.outbox().map { it.noteId })

        api.spaceAnswer = { null }
        engine.retryParked()
        assertEquals(SyncOutcome.Done, engine.sync())
        assertEquals(listOf("b", "a"), api.sent.map { it.first })
        assertEquals(0, notes.pendingNotes())
    }

    @Test
    fun aNoteWaitsForItsParkedImage() = env {
        val image = "c".repeat(64)
        // Opening gives parked changes another try: parked after it, as a failing upload would leave it.
        engine.open()
        db.write {
            it.exec(
                "INSERT INTO pending_images (id, content_type, created_at, attempts, parked) VALUES (?, 'image/png', 0, 3, 1)",
                image
            )
        }
        notes.save("plain", "Plain", "![](notesd:image/$image?w=1&h=1)\n")
        notes.save("locked", "Locked", LockedEnvelope.PREFIX + "c2VhbGVk", imageIds = listOf(image))
        // Mentioned in text only, not as a reference: no wait.
        notes.save("other", "Other", "notesd:image/$image\n")
        assertEquals(SyncOutcome.Done, engine.sync())
        assertEquals(listOf("other"), api.sent.map { it.first })
        assertEquals(setOf("plain", "locked"), notes.outbox().map { it.noteId }.toSet())
        // That cycle sent something, so the image is back in line, and its notes behind it.
        assertEquals(0, engine.parked())
        assertEquals(SyncOutcome.Done, engine.sync())
        assertEquals(0, notes.pendingNotes())
    }
}
