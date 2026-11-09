package notes.core.api

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import notes.core.sync.TestServer

/** The agent's and AI's calls against a real notesd: what the phone apps send is what it takes. */
class AgentApiTest {
    private val server = TestServer()

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

    @AfterTest
    fun stop() = server.close()

    @Test
    fun `AI is connected, its key kept, tested and disconnected`() = runBlocking<Unit> {
        assertEquals(AiSettings(editable = true), assertIs<ApiResult.Ok<AiSettings>>(api.aiSettings()).value)

        val bad = api.saveAiSettings(AiSettingsInput("not an address", "m", "sk-test-1234", null))
        assertEquals("baseUrl", assertIs<ApiResult.Rejected>(bad).field)

        // Nothing listens there: saving doesn't call the provider, testing does.
        val input = AiSettingsInput("http://127.0.0.1:1/v1", "small-model", "sk-test-1234", 5_000)
        val saved = assertIs<ApiResult.Ok<AiSettings>>(api.saveAiSettings(input)).value
        assertEquals(AiSettings(true, "http://127.0.0.1:1/v1", "small-model", "1234", 5_000, true), saved)
        val kept = api.saveAiSettings(input.copy(model = "other", apiKey = null, dailyTokenCap = null))
        assertEquals("1234", assertIs<ApiResult.Ok<AiSettings>>(kept).value.keyHint)

        val failed = assertIs<ApiResult.Rejected>(api.testAiSettings(null))
        assertEquals(502 to "ai_test_failed", failed.status to failed.error)
        assertEquals("ai_test_failed", assertIs<ApiResult.Rejected>(api.testAiSettings(input)).error)

        assertEquals(ApiResult.Ok(Unit), api.deleteAiSettings())
        assertEquals(false, assertIs<ApiResult.Ok<AiSettings>>(api.aiSettings()).value.configured)
    }

    @Test
    fun `a space's agent is saved, listed, and its references kept when left out`() = runBlocking<Unit> {
        assertEquals(emptyList(), assertIs<ApiResult.Ok<List<AgentConfig>>>(api.agentConfigs()).value)

        val put = api.putAgentConfig(
            "default",
            enabled = false,
            prompt = "Keep it tidy.",
            menuLabel = "Tidy",
            triggers = mapOf("editDone" to "Add tags.", "menu" to "Tidy this note."),
            referenceNotes = listOf("Tags")
        )
        val config = assertIs<ApiResult.Ok<AgentConfig>>(put).value
        assertEquals(mapOf("editDone" to "Add tags.", "menu" to "Tidy this note."), config.triggers)
        assertEquals(listOf("Tags"), config.referenceNotes)

        val again = api.putAgentConfig("default", false, "Keep it tidy.", "Tidy", emptyMap(), referenceNotes = null)
        assertEquals(listOf("Tags"), assertIs<ApiResult.Ok<AgentConfig>>(again).value.referenceNotes)
        val listed = assertIs<ApiResult.Ok<List<AgentConfig>>>(api.agentConfigs()).value.single()
        assertEquals("default" to emptyMap(), listed.spaceId to listed.triggers)

        val missing = api.putAgentConfig("nowhere", false, "", "", emptyMap(), null)
        assertEquals(404, assertIs<ApiResult.Rejected>(missing).status)
    }

    @Test
    fun `activity, usage and undo with no runs yet`() = runBlocking<Unit> {
        val page = assertIs<ApiResult.Ok<RunsPage>>(api.agentRuns(space = "default", limit = 10)).value
        assertEquals(RunsPage(), page)
        val usage = assertIs<ApiResult.Ok<AgentUsage>>(api.agentUsage("default")).value
        assertEquals(0L to AiSettings.DEFAULT_DAILY_TOKEN_CAP, usage.tokens to usage.dailyTokenCap)
        assertTrue(usage.day.isNotEmpty())

        assertEquals(404, assertIs<ApiResult.Rejected>(api.agentRun("nope")).status)
        assertEquals(404, assertIs<ApiResult.Rejected>(api.undoAgentRun("nope")).status)
    }

    @Test
    fun `the account's time zone is chosen, and an unknown one refused`() = runBlocking<Unit> {
        assertIs<ApiResult.Ok<AccountSettings>>(api.accountSettings())
        val set = assertIs<ApiResult.Ok<AccountSettings>>(api.setTimeZone("America/Sao_Paulo")).value
        assertEquals(AccountSettings("America/Sao_Paulo", "user"), set)
        assertEquals("timeZone", assertIs<ApiResult.Rejected>(api.setTimeZone("Mars/Olympus")).field)
    }
}
