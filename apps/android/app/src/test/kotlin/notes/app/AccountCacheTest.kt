package notes.app

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import notes.core.api.ApiResult
import org.junit.Test

class AccountCacheTest {
    private var answer: ApiResult<String> = ApiResult.Ok("a")
    private val cache = AccountCache { answer }

    @Test
    fun `a failed fetch keeps what was known, and is retried only when nothing was`() = runBlocking {
        answer = ApiResult.Unreachable(IOException())
        cache.refresh()
        assertNull(cache.value.value)
        assertTrue(cache.failed.value)

        answer = ApiResult.Ok("a")
        cache.retryIfFailed()
        assertEquals("a", cache.value.value)
        assertFalse(cache.failed.value)

        answer = ApiResult.Unreachable(IOException())
        cache.refresh()
        assertEquals("a", cache.value.value)
        assertFalse(cache.failed.value)
    }

    @Test
    fun `an answer for an account that's gone is ignored`() = runBlocking {
        val gate = CompletableDeferred<ApiResult<String>>()
        var fetches = 0
        val slow = AccountCache { if (++fetches == 1) gate.await() else ApiResult.Ok("new") }
        val fetch = async { slow.refresh() }
        yield()
        slow.forget()
        gate.complete(ApiResult.Ok("old"))
        fetch.await()
        assertEquals("new", slow.value.value)

        slow.forget()
        val save = CompletableDeferred<ApiResult<String>>()
        val saving = async { slow.change({ save.await() }) { old, saved -> old + saved } }
        yield()
        slow.forget()
        save.complete(ApiResult.Ok("old"))
        assertEquals(ApiResult.Ok("old"), saving.await())
        assertNull(slow.value.value)
    }

    @Test
    fun `a save's answer replaces what's known`() = runBlocking {
        cache.refresh()
        cache.change({ ApiResult.Ok(2) }) { old, n -> old + n }
        assertEquals("a2", cache.value.value)
    }
}
