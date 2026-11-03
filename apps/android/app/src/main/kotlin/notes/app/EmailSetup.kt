package notes.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.EmailSettings

/**
 * The server's email settings as last seen: whether the share sheet offers
 * Email, and what Settings shows. Null while unknown (never connected, not
 * loaded yet, signed out).
 */
class EmailSetup(private val api: () -> ApiClient?) {
    private val _settings = MutableStateFlow<EmailSettings?>(null)
    val settings: StateFlow<EmailSettings?> = _settings

    /** Moves on with each [forget]: an answer to a request made before it is for an account that's gone. */
    @Volatile private var generation = 0

    /** Re-reads them; they can change from another device. Failures keep what was known; false says one happened. */
    suspend fun refresh(): Boolean {
        val asked = generation
        val r = api()?.emailSettings() ?: return false
        // An answer for an account that's gone isn't one for this account.
        if (r !is ApiResult.Ok || asked != generation) return false
        _settings.value = r.value
        return true
    }

    /** A null [apiKey] keeps the saved one. */
    suspend fun save(from: String, apiKey: String?): ApiResult<EmailSettings> {
        val asked = generation
        val r = api()?.saveEmailSettings(from, apiKey) ?: ApiResult.Unauthenticated
        if (r is ApiResult.Ok && asked == generation) _settings.value = r.value
        return r
    }

    suspend fun remove(): ApiResult<Unit> {
        val asked = generation
        val r = api()?.deleteEmailSettings() ?: ApiResult.Unauthenticated
        // Still an admin's: only they can remove them.
        if (r is ApiResult.Ok && asked == generation) _settings.value = EmailSettings(editable = true)
        return r
    }

    fun forget() {
        generation++
        _settings.value = null
    }
}
