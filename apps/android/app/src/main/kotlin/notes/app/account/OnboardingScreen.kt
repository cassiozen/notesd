package notes.app.account

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import notes.app.ui.Backdrop
import notes.app.ui.Banner
import notes.app.ui.BannerKind
import notes.app.ui.Brand
import notes.app.ui.ButtonKind
import notes.app.ui.Heading
import notes.app.ui.Kicker
import notes.app.ui.LocalHaze
import notes.app.ui.LocalReduceMotion
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesField
import notes.app.ui.PasswordMeter
import notes.app.ui.Reveal
import notes.app.ui.Steps
import notes.app.ui.Type
import notes.app.ui.glass
import notes.app.ui.shake
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Connecting to a server. [onSkip] is offered only on first run ("Use without
 * a server"); [onDone] runs once signed in.
 */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel, onDone: () -> Unit, onSkip: (() -> Unit)?, onExit: (() -> Unit)?) {
    val t = LocalTokens.current
    val s = vm.state
    val haze = rememberHazeState()
    val reduce = LocalReduceMotion.current
    var shakes by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.done) { if (s.done) onDone() }
    LaunchedEffect(s.error, s.fieldErrors) { if (s.error != null || s.fieldErrors.isNotEmpty()) shakes++ }
    BackHandler(enabled = s.step != Step.Server && !(vm.lockedServer != null && s.step == Step.SignIn)) { vm.back() }

    val stepIndex = if (s.step == Step.Server) 0 else 1

    CompositionLocalProvider(LocalHaze provides haze) {
        Box(Modifier.fillMaxSize().background(t.bg)) {
            Backdrop(Modifier.hazeSource(haze))
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(Modifier.widthIn(max = 468.dp).fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Brand(large = true)
                        Spacer(Modifier.weight(1f))
                        if (vm.lockedServer == null) Steps(2, stepIndex)
                    }
                    Spacer(Modifier.height(20.dp))
                    val shape = RoundedCornerShape(28.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .shake(shakes)
                            .shadow(30.dp, shape, ambientColor = Color(0x40462D19), spotColor = Color(0x40462D19))
                            .clip(shape)
                            .glass(hi = false)
                            .border(1.dp, t.edge, shape)
                    ) {
                        AnimatedContent(
                            s.step,
                            transitionSpec = {
                                val forward = targetState.ordinal > initialState.ordinal
                                val ms = if (reduce) 0 else 300
                                (
                                    slideInHorizontally(tween(ms)) {
                                        if (forward) it / 6 else -it / 6
                                    } + fadeIn(tween(ms))
                                    ) togetherWith
                                    (
                                        slideOutHorizontally(tween(ms)) { if (forward) -it / 6 else it / 6 } +
                                            fadeOut(tween(ms / 2))
                                        )
                            },
                            label = "step"
                        ) { step ->
                            Column(
                                Modifier.fillMaxWidth().padding(
                                    start = 24.dp,
                                    end = 24.dp,
                                    top = 28.dp,
                                    bottom = 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                StepContent(step, vm, onSkip, onExit)
                            }
                        }
                    }
                    if (s.step != Step.Server && s.server.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            s.server.toHttpUrlOrNull()?.host ?: s.server,
                            style = Type.mono.copy(fontSize = 12.sp, textAlign = TextAlign.Center),
                            color = t.ink3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (s.step == Step.Server && onSkip != null) {
                        Spacer(Modifier.height(20.dp))
                        NotesButton("Use without a server", onSkip, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
                        Text(
                            "Your notes stay on this phone. You can connect later in Settings.",
                            style = Type.small.copy(textAlign = TextAlign.Center),
                            color = t.ink3,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepContent(step: Step, vm: OnboardingViewModel, onSkip: (() -> Unit)?, onExit: (() -> Unit)?) {
    val s = vm.state
    when (step) {
        Step.Server -> {
            Kicker("Welcome")
            Heading("Connect to your server", style = Type.display.copy(fontSize = 32.sp, lineHeight = 34.sp))
            Lede("notesd keeps your writing on a server you run yourself, and in sync on every device you sign in on.")
            Input(
                vm,
                Field.Server,
                "Server address",
                s.server,
                { copy(server = it) },
                placeholder = "notes.example.com",
                mono = true,
                keyboard = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go
                ),
                onDone = vm::submitServer
            )
            Error(s.error)
            Primary(if (s.busy) "Connecting…" else "Continue", s.busy, vm::submitServer)
            if (onSkip == null && onExit != null) Secondary("Not now", onExit)
        }

        Step.Account -> {
            Kicker("New server")
            Heading("Create your account", style = Type.display.copy(fontSize = 32.sp, lineHeight = 34.sp))
            Lede("This server has no account yet. Yours will be the first.")
            Input(
                vm,
                Field.Username,
                "Username",
                s.username,
                { copy(username = it) },
                type = ContentType.NewUsername,
                enabled = vm.lockedUsername == null
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Input(
                    vm,
                    Field.Password,
                    "Password",
                    s.password,
                    { copy(password = it) },
                    placeholder = "At least 8 characters",
                    secret = true,
                    type = ContentType.NewPassword
                )
                Reveal(s.password.isNotEmpty()) { PasswordMeter(s.password) }
            }
            Input(
                vm,
                Field.Confirm,
                "Confirm password",
                s.confirm,
                { copy(confirm = it) },
                secret = true,
                type = ContentType.NewPassword,
                onDone = vm::submitAccount
            )
            Error(s.error)
            Primary(if (s.busy) "Creating account…" else "Create account", s.busy, vm::submitAccount)
            Secondary("Back") { vm.back() }
        }

        Step.SignIn -> {
            Kicker(if (vm.lockedServer != null) "Session ended" else "Welcome back")
            Heading(
                if (vm.lockedServer != null) "Sign in again" else "Sign in",
                style = Type.display.copy(fontSize = 32.sp, lineHeight = 34.sp)
            )
            Input(
                vm,
                Field.Username,
                "Username",
                s.username,
                { copy(username = it) },
                type = ContentType.Username,
                // These notes are this person's; someone else removes all data first.
                enabled = vm.lockedUsername == null
            )
            Input(
                vm,
                Field.Password,
                "Password",
                s.password,
                { copy(password = it) },
                secret = true,
                type = ContentType.Password,
                onDone = vm::submitSignIn
            )
            val wait = rateLimitSeconds(s.retryAt)
            Error(if (wait > 0) "Too many attempts. Try again in $wait s." else s.error)
            Primary(if (s.busy) "Signing in…" else "Sign in", s.busy || wait > 0, vm::submitSignIn)
            if (vm.lockedServer == null) {
                Secondary("Use a different server") { vm.back() }
            } else if (onExit != null) {
                Secondary("Not now", onExit)
            }
        }
    }
}

/** Seconds left until [retryAt], ticking. */
@Composable
private fun rateLimitSeconds(retryAt: Long?): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAt) {
        while (retryAt != null && now < retryAt) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return if (retryAt == null) 0 else ((retryAt - now + 999) / 1000).coerceAtLeast(0)
}

@Composable
private fun Lede(text: String) {
    Text(text, style = Type.lede.copy(fontSize = 15.5.sp), color = LocalTokens.current.ink2)
}

@Composable
private fun Error(text: String?) {
    Reveal(text != null) {
        Banner(text.orEmpty(), BannerKind.Error)
    }
}

@Composable
private fun Primary(label: String, busy: Boolean, onClick: () -> Unit) {
    NotesButton(label, onClick, Modifier.fillMaxWidth().padding(top = 4.dp), busy = busy)
}

@Composable
private fun Secondary(label: String, onClick: () -> Unit) {
    NotesButton(label, onClick, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
}

@Composable
private fun Input(
    vm: OnboardingViewModel,
    field: Field,
    label: String,
    value: String,
    update: OnboardingState.(String) -> OnboardingState,
    placeholder: String? = null,
    secret: Boolean = false,
    mono: Boolean = false,
    type: ContentType? = null,
    keyboard: KeyboardOptions? = null,
    enabled: Boolean = true,
    onDone: (() -> Unit)? = null
) {
    NotesField(
        value = value,
        onValueChange = { v -> vm.edit { update(v) } },
        label = label,
        placeholder = placeholder,
        error = vm.state.fieldErrors[field],
        secret = secret,
        mono = mono,
        enabled = enabled,
        keyboardOptions = keyboard ?: KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next
        ),
        // Only Done and Go submit; Next keeps its default of moving to the next field.
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
        contentType = type
    )
}
