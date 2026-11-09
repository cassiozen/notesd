package notes.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import notes.core.api.ApiResult

/**
 * One copy of something the server says about the signed-in account, for the whole app: fetched by [refresh],
 * replaced by what a save answers, dropped by [forget] at sign-in and sign-out. Null while unknown.
 */
class AccountCache<T : Any>(private val fetch: suspend () -> ApiResult<T>?) {
    private val _value = MutableStateFlow<T?>(null)
    val value: StateFlow<T?> = _value

    private val _failed = MutableStateFlow(false)

    /** The last fetch failed, with nothing loaded. */
    val failed: StateFlow<Boolean> = _failed

    /** Moves on with each [forget]: an answer to a request made before it is for an account that's gone. */
    @Volatile private var generation = 0

    /** Moves on with each save [change] applies: a fetch sent before it may hold what the save replaced. */
    @Volatile private var saves = 0

    /** Fetches it again; a failure keeps what was known. */
    suspend fun refresh() {
        var asked: Int
        var saved: Int
        var r: ApiResult<T>?
        // An answer for an account that's gone: ask again, for the one there now.
        do {
            asked = generation
            saved = saves
            r = fetch()
        } while (asked != generation)
        if (saved != saves) return
        if (r is ApiResult.Ok) {
            _value.value = r.value
            _failed.value = false
        } else {
            _failed.value = _value.value == null
        }
    }

    /** After a fetch failed: nothing else asks again, and what hangs on it would stay off. */
    suspend fun retryIfFailed() {
        if (_failed.value) refresh()
    }

    /** Makes [call], and has its answer change what's known, unless the account changed meanwhile. */
    suspend fun <R> change(call: suspend () -> ApiResult<R>?, next: (T?, R) -> T?): ApiResult<R> {
        val asked = generation
        val r = call() ?: ApiResult.Unauthenticated
        if (r is ApiResult.Ok && asked == generation) {
            saves++
            _value.value = next(_value.value, r.value)
            _failed.value = false
        }
        return r
    }

    fun forget() {
        generation++
        _value.value = null
        _failed.value = false
    }
}
