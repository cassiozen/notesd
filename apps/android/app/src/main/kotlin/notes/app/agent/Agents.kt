package notes.app.agent

import java.net.URI
import notes.app.AccountCache
import notes.core.api.AgentConfig
import notes.core.api.AiSettings
import notes.core.api.AiSettingsInput
import notes.core.api.AiTestResult
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.map

/** The server's AI provider, which every space's agent uses. Admins change it; everyone sees it. */
class AiSetup(private val api: () -> ApiClient?) {
    val cache = AccountCache { api()?.aiSettings() }

    suspend fun save(input: AiSettingsInput): ApiResult<AiSettings> =
        cache.change({ api()?.saveAiSettings(input) }) { _, saved -> saved }

    suspend fun remove(): ApiResult<Unit> = cache.change({ api()?.deleteAiSettings() }) { old, _ ->
        // Still an admin's: only they can remove it.
        AiSettings(editable = old?.editable ?: true)
    }

    /** Saves nothing. A null [input] tests what's saved. */
    suspend fun test(input: AiSettingsInput?): ApiResult<AiTestResult> =
        api()?.testAiSettings(input) ?: ApiResult.Unauthenticated
}

/** Each space's agent, by space id. Agents run on the server, so this is never on the phone. */
class AgentConfigs(private val api: () -> ApiClient?) {
    val cache = AccountCache { api()?.agentConfigs()?.map { list -> list.associateBy { it.spaceId } } }

    /** A null [referenceNotes] keeps the saved ones. */
    suspend fun save(
        spaceId: String,
        enabled: Boolean,
        prompt: String,
        menuLabel: String,
        triggers: Map<String, String>,
        referenceNotes: List<String>?
    ): ApiResult<AgentConfig> = cache.change({
        api()?.putAgentConfig(spaceId, enabled, prompt, menuLabel, triggers, referenceNotes)
    }) { old, saved -> old.orEmpty() + (spaceId to saved) }
}

/** Where a space's notes go, as people know it: the API address's host. */
fun providerHost(baseUrl: String): String {
    val uri = runCatching { URI(baseUrl) }.getOrNull()
    val host = uri?.host ?: return baseUrl
    return if (uri.port == -1) host else "$host:${uri.port}"
}
