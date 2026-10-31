package notes.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import notes.core.api.ApiResult
import notes.core.api.LockKey
import notes.core.api.MutationResult
import notes.core.api.RemoteSpace
import notes.core.api.SyncApi
import notes.core.api.SyncPage
import notes.core.db.openTestDatabase

class SyncEngineTest {
    @Test
    fun aCycleThatThrowsEndsFailedInsteadOfStayingSyncing() = runTest {
        val t = openTestDatabase()
        try {
            val engine = SyncEngine(t.db, { Throwing })
            assertEquals(SyncOutcome.Failed, engine.sync())
            assertEquals(SyncStatus.Failed("disk I/O error"), engine.status.value)
        } finally {
            t.close()
        }
    }

    private object Throwing : SyncApi {
        override suspend fun createNote(body: JsonObject): ApiResult<MutationResult> = fail()

        override suspend fun updateNote(id: String, body: JsonObject): ApiResult<MutationResult> = fail()

        override suspend fun deleteNote(id: String, body: JsonObject): ApiResult<MutationResult> = fail()

        override suspend fun sync(since: Long, limit: Int, generation: String?): ApiResult<SyncPage> = fail()

        override suspend fun lockKeys(): ApiResult<List<LockKey>> = fail()

        override suspend fun spaces(): ApiResult<List<RemoteSpace>> = fail()

        override suspend fun putSpace(id: String, name: String, icon: String): ApiResult<RemoteSpace> = fail()

        override suspend fun deleteSpace(id: String): ApiResult<Unit> = fail()

        override suspend fun putImage(id: String, bytes: ByteArray, type: String): ApiResult<Unit> = fail()

        private fun fail(): Nothing = throw IllegalStateException("disk I/O error")
    }
}
