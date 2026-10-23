package notes.app.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.ServerAddress
import notes.core.api.SignIn
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class Step { Server, Account, SignIn }

data class OnboardingState(
    val step: Step,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    /** About the whole step, shown above its button. */
    val error: String? = null,
    val fieldErrors: Map<Field, String> = emptyMap(),
    /** Rate limited until this time (ms); the sign-in button waits. */
    val retryAt: Long? = null,
    val done: Boolean = false
)

enum class Field { Server, Username, Password, Confirm }

/**
 * Server address → new account (when the server has none yet) or sign in. With [lockedServer] it's "sign in again": straight to sign-in on
 * the server these notes belong to.
 *
 * Signed out, only the person whose notes these are can sign in: anyone else's first sync would take this phone's notes into their
 * account. Someone else removes all data first.
 */
class OnboardingViewModel(
    private val accounts: AccountStore,
    private val deviceName: String,
    private val clientFor: (HttpUrl) -> ApiClient,
    val lockedServer: String? = null,
    username: String = "",
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {
    /** Whose notes this phone holds while signed out; nobody else may sign in. */
    val lockedUsername: String? = (accounts.account.value as? Account.SignedOut)?.username

    var state by mutableStateOf(
        OnboardingState(
            step = if (lockedServer != null) Step.SignIn else Step.Server,
            server = lockedServer.orEmpty(),
            username = lockedUsername ?: username
        )
    )
        private set

    private var url: HttpUrl? = lockedServer?.toHttpUrl()

    fun edit(change: OnboardingState.() -> OnboardingState) {
        state = state.change().copy(error = null, fieldErrors = emptyMap())
    }

    /** Back within the flow. False when there's no earlier step to go to. */
    fun back(): Boolean {
        val previous = when (state.step) {
            Step.Server -> null
            Step.Account -> Step.Server
            Step.SignIn -> if (lockedServer == null) Step.Server else null
        } ?: return false
        state = state.copy(step = previous, error = null, fieldErrors = emptyMap(), password = "", confirm = "")
        return true
    }

    fun submitServer() = run {
        val address = ServerAddress.parse(state.server)
        if (address is ServerAddress.Invalid) return@run fail(Field.Server to address.message)
        address as ServerAddress.Valid
        val api = clientFor(address.url)
        when (val health = api.health()) {
            is ApiResult.Ok -> Unit

            is ApiResult.Unreachable -> return@run fail(
                message = "Can't reach a server at ${address.url.host}. Check the address, and that this phone " +
                    "is on the same network if the server is at home."
            )

            else -> return@run fail(message = messageFor(health))
        }
        when (val status = api.status()) {
            is ApiResult.Ok -> {
                url = address.url
                state = state.copy(step = if (status.value.setupRequired) Step.Account else Step.SignIn)
            }

            else -> fail(message = messageFor(status))
        }
    }

    fun submitAccount() = run {
        val s = state
        val errors = buildMap {
            if (s.username.isBlank()) put(Field.Username, "Choose a username.")
            if (s.password.length < 8) {
                put(Field.Password, "Use at least 8 characters.")
            } else if (s.confirm != s.password) {
                put(Field.Confirm, "The passwords don't match.")
            }
        }
        if (errors.isNotEmpty()) return@run fail(*errors.toList().toTypedArray())
        // Before setup: an account made under another name would be left on the server, unused.
        if (!isLockedUser(s.username)) return@run fail(message = otherUserMessage())
        val api = clientFor(url!!)
        when (val r = api.setup(s.username.trim(), s.password)) {
            is ApiResult.Ok -> Unit

            is ApiResult.Rejected if r.error == "invalid_request" && r.field != null -> {
                val field = if (r.field == "username") Field.Username else Field.Password
                return@run fail(field to (r.message.replaceFirstChar { it.uppercase() } + "."))
            }

            is ApiResult.Conflict -> {
                state = state.copy(step = Step.SignIn, password = "", confirm = "")
                return@run fail(message = "This server already has an account. Sign in instead.")
            }

            else -> return@run fail(message = messageFor(r))
        }
        when (val login = api.login(s.username.trim(), s.password, deviceName)) {
            is ApiResult.Ok -> finish(login.value)

            // The account exists now; plain sign-in takes it from here.
            else -> {
                state = state.copy(step = Step.SignIn, password = "", confirm = "")
                fail(message = messageFor(login))
            }
        }
    }

    fun submitSignIn() = run {
        val s = state
        if (s.username.isBlank() || s.password.isEmpty()) return@run fail(message = "Enter your username and password.")
        if (!isLockedUser(s.username)) return@run fail(message = otherUserMessage())
        when (val r = clientFor(url!!).login(s.username.trim(), s.password, deviceName)) {
            is ApiResult.Ok -> finish(r.value)

            is ApiResult.Rejected if r.error == "invalid_credentials" -> fail(message = "Wrong username or password.")

            is ApiResult.RateLimited -> {
                state = state.copy(retryAt = clock() + r.retryAfterSeconds * 1000)
                fail(message = messageFor(r))
            }

            else -> fail(message = messageFor(r))
        }
    }

    private suspend fun finish(signIn: SignIn) {
        // The server's spelling of the username, in case it compares names differently than this phone.
        if (!isLockedUser(signIn.username)) return fail(message = otherUserMessage())
        accounts.signedIn(url.toString(), signIn)
        state = state.copy(done = true, password = "", confirm = "")
    }

    /** Usernames compare as the server compares them: trimmed, ignoring case. */
    private fun isLockedUser(username: String) =
        lockedUsername == null || username.trim().equals(lockedUsername.trim(), ignoreCase = true)

    private fun otherUserMessage() =
        "This phone has $lockedUsername's notes. To sign in as someone else, remove all data from this device in Settings first."

    private fun run(block: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, error = null, fieldErrors = emptyMap())
        viewModelScope.launch {
            try {
                block()
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    private fun fail(vararg fields: Pair<Field, String>, message: String? = null) {
        state = state.copy(error = message, fieldErrors = fields.toMap())
    }

    private fun messageFor(r: ApiResult<*>): String = when (r) {
        is ApiResult.Unreachable -> "Can't reach the server. Check your connection and try again."

        is ApiResult.RateLimited -> "Too many attempts. Wait a moment and try again."

        is ApiResult.Rejected if r.error == ApiClient.UNEXPECTED_RESPONSE || r.status == 404 ->
            "There's no notes server at that address."

        is ApiResult.Rejected -> r.message.ifEmpty { "The server refused that (error ${r.status})." }

        is ApiResult.Conflict -> r.message

        else -> "Something went wrong. Try again."
    }
}
