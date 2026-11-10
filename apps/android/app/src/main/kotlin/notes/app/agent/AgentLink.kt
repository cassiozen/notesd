package notes.app.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import notes.app.account.Account
import notes.app.account.AccountStore
import notes.app.notes.OpenNotes
import notes.core.agent.AgentEditors
import notes.core.agent.AgentSocket
import notes.core.api.ApiClient
import notes.core.api.ApiResult

/**
 * Keeps the agent socket (docs/contributing/agent-clients.md) open while someone could use it: signed in, in
 * the foreground, online, with AI set up and the agent on in the space shown. Tells it which notes are open.
 */
class AgentLink(
    private val accounts: AccountStore,
    private val ai: AiSetup,
    private val agents: AgentConfigs,
    private val space: StateFlow<String>,
    private val foreground: StateFlow<Boolean>,
    private val online: StateFlow<Boolean>,
    private val openNotes: OpenNotes,
    editors: AgentEditors,
    api: () -> ApiClient?,
    private val scope: CoroutineScope,
    timing: AgentSocket.Timing = AgentSocket.Timing()
) {
    val socket = AgentSocket({ api()?.agentSocket(it) ?: ApiResult.Unauthenticated }, editors, scope, timing)

    private enum class Want { Open, Closed, Background }

    fun start() {
        openNotes.listener = object : OpenNotes.Listener {
            override fun opened(id: String, spaceId: String) = socket.opened(id, spaceId)

            override fun closed(id: String, revision: Long?) = socket.closed(id, revision)
        }
        scope.launch { online.collect { socket.online(it) } }
        val signedIn = accounts.account.map { it is Account.SignedIn }.distinctUntilChanged()
        // Another account's notes and runs must never reach this one's socket.
        scope.launch {
            accounts.account.map { (it as? Account.SignedIn)?.let { a -> a.server to a.username } }
                .distinctUntilChanged()
                .drop(1)
                .collect { socket.reset() }
        }
        // The agent may have been turned on from another device while away.
        scope.launch {
            combine(signedIn, foreground) { a, f -> a && f }.distinctUntilChanged().filter { it }.collect {
                launch { ai.cache.refresh() }
                launch { agents.cache.refresh() }
            }
        }
        scope.launch {
            combine(signedIn, foreground, ai.cache.value, agents.cache.value, space) { a, f, settings, configs, s ->
                when {
                    !a || settings?.configured != true || configs?.get(s)?.enabled != true -> Want.Closed
                    !f -> Want.Background
                    else -> Want.Open
                }
            }.distinctUntilChanged().collect {
                when (it) {
                    Want.Open -> socket.start()

                    Want.Closed -> socket.stop()

                    // Its notes stay open, so there's no close to wait for; the server keeps their sessions.
                    Want.Background -> socket.stop(waitForCloses = false)
                }
            }
        }
    }
}
