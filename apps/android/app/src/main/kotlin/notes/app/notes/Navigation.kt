package notes.app.notes

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import notes.app.AppGraph
import notes.app.account.Account
import notes.app.account.OnboardingScreen
import notes.app.account.OnboardingViewModel
import notes.app.settings.NewSpaceDialog
import notes.app.settings.SettingsScreen
import notes.app.ui.KeyboardBar
import notes.app.ui.KeyboardBarSlot
import notes.app.ui.LocalKeyboardBar
import notes.app.ui.LocalReduceMotion
import notes.app.ui.hideKeyboard
import notes.app.ui.springSlide
import notes.core.api.ApiClient
import notes.core.db.NoteFilter
import notes.core.text.Uuid7
import notes.core.text.tagKey

/** All notes, Untagged and the tag tree: a screen under the list, unless the window is wide enough for a sidebar. */
@Serializable
data object TagsKey : NavKey

@Serializable
data object NotesKey : NavKey

/**
 * A note by id; a new note gets its id up front and exists once it has text.
 * [startTag]: a new note starts with this tag (the list's selected one).
 */
@Serializable
data class EditorKey(val id: String, val startTag: String? = null) : NavKey

@Serializable
data object SettingsKey : NavKey

/** Connecting to a server; with [signInAgain], signing back in to the one these notes belong to. */
@Serializable
data class OnboardingKey(val signInAgain: Boolean = false) : NavKey

private const val MOTION_MS = 320

/** From this wide, the tags sit in a sidebar beside the list and the note. */
private const val SIDEBAR_DP = 1000

/**
 * The app's screens. From 600dp wide (tablets, an unfolded foldable) the list
 * and the open note sit side by side, split around a folding hinge; narrower,
 * one screen at a time. The tags are a screen under the list, or from
 * [SIDEBAR_DP] a sidebar.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun NotesNavigation(graph: AppGraph, newNote: Flow<Unit> = emptyFlow(), emailNote: Flow<String> = emptyFlow()) {
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= SIDEBAR_DP.dp
    val start = remember {
        val firstRun = graph.accounts.account.value == Account.None && !graph.onboardingDismissed.value
        when {
            firstRun -> arrayOf<NavKey>(OnboardingKey())

            wide -> arrayOf<NavKey>(NotesKey)

            // Opens on the list last shown, with the tags a back away.
            else -> arrayOf(TagsKey, NotesKey)
        }
    }
    val backStack = rememberNavBackStack(*start)
    val view = LocalView.current
    val account by graph.accounts.account.collectAsStateWithLifecycle()
    val spaces by graph.spaces.collectAsStateWithLifecycle()
    val space by graph.space.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current

    val directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo())
        .let { it.copy(horizontalPartitionSpacerSize = 0.dp, defaultPanePreferredWidth = 340.dp) }
    val twoPane = directive.maxHorizontalPartitions > 1
    val listDetail = rememberListDetailSceneStrategy<NavKey>(directive = directive)

    // The keyboard belongs to the window, not the screen being left: hide it as back starts, not after the
    // exit animation, or it hangs over the list.
    fun pop() {
        view.hideKeyboard()
        backStack.removeLastOrNull()
    }

    /** To the notes list, with only the tags screen behind it. */
    fun home() {
        view.hideKeyboard()
        backStack.add(NotesKey)
        while (backStack.size > 1) backStack.removeAt(0)
        if (!wide) backStack.add(0, TagsKey)
    }

    /**
     * Back to the tags screen, under the list. Beside a note (two panes) the tags
     * go on top instead: they take the list's pane and the note stays open.
     */
    fun tags() {
        view.hideKeyboard()
        if (twoPane) {
            backStack.remove(TagsKey)
            backStack += TagsKey
            return
        }
        if (TagsKey !in backStack) backStack.add(0, TagsKey)
        while (backStack.last() != TagsKey) backStack.removeAt(backStack.lastIndex)
    }

    fun show(filter: NoteFilter?) {
        graph.listFilter.value = filter
        if (backStack.lastOrNull() != TagsKey) return
        // Tags over the list (two panes): back to the list beneath them.
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else backStack += NotesKey
    }

    /** Shows another space (§7.7): its list, without the note that was open beside it. */
    fun switchSpace(id: String) {
        // The one shown: nothing to reset.
        if (id == graph.space.value) return
        graph.switchSpace(id)
        backStack.removeAll { it is EditorKey }
        show(null)
    }

    // A note open: coming back after a while doesn't move the list to the default space from under it.
    val editorOpen by remember { derivedStateOf { backStack.any { it is EditorKey } } }
    LaunchedEffect(editorOpen) { graph.editorOpen = editorOpen }

    // Narrower than the sidebar, the tags always sit under the list: back from the list reaches them,
    // including after they were shown on top of it and closed.
    val tagsMissing by remember { derivedStateOf { TagsKey !in backStack && NotesKey in backStack } }
    LaunchedEffect(tagsMissing, wide) {
        if (tagsMissing && !wide) backStack.add(0, TagsKey)
    }

    // Folded while the tags were over the list: close them rather than show them over the note full screen.
    LaunchedEffect(twoPane) {
        if (!twoPane && backStack.size > 1 && backStack.last() == TagsKey) backStack.removeAt(backStack.lastIndex)
    }

    // Folded, unfolded, resized: the tags move between sidebar and screen.
    LaunchedEffect(wide) {
        if (wide) {
            if (backStack.lastOrNull() == TagsKey) backStack += NotesKey
            backStack.removeAll { it == TagsKey }
        }
    }

    /** From the list: beside it, the open note is swapped rather than stacked. */
    fun open(id: String, startTag: String? = null) {
        if (backStack.lastOrNull() is EditorKey) {
            if ((backStack.last() as EditorKey).id == id) return
            view.hideKeyboard()
            backStack[backStack.lastIndex] = EditorKey(id, startTag)
        } else {
            backStack += EditorKey(id, startTag)
        }
    }

    /** A new note, starting with the list's selected tag. */
    fun new() = open(Uuid7.generate(), (graph.listFilter.value as? NoteFilter.Tag)?.name)

    LaunchedEffect(newNote) {
        newNote.collect {
            // From the launcher: a fresh note over the list, whatever was open.
            home()
            open(Uuid7.generate())
        }
    }

    // A note whose share sheet "Email" was chosen; its editor opens the email dialog.
    var emailFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(emailNote) {
        emailNote.collect { id ->
            open(id)
            emailFor = id
        }
    }
    val email by graph.email.settings.collectAsStateWithLifecycle()

    // New space, from the sidebar's switcher: made, then shown.
    var creatingSpace by remember { mutableStateOf(false) }
    if (creatingSpace) {
        NewSpaceDialog(graph, onDismiss = { creatingSpace = false }) { id ->
            creatingSpace = false
            switchSpace(id)
        }
    }

    val ms = if (reduce) 0 else MOTION_MS
    val keyboardBar = remember { KeyboardBar() }
    CompositionLocalProvider(LocalKeyboardBar provides keyboardBar) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (wide && backStack.lastOrNull() !is OnboardingKey) {
                    TagsPane(
                        vm = viewModel(key = "tags:sidebar") {
                            TagsViewModel(graph.store, graph.listFilter, graph.space)
                        },
                        state = graph.tagTree,
                        docked = true,
                        onShow = ::show,
                        onSettings = { if (backStack.lastOrNull() != SettingsKey) backStack += SettingsKey },
                        modifier = Modifier.width(280.dp),
                        spaces = spaces,
                        space = space,
                        onSpace = ::switchSpace,
                        onNewSpace = { creatingSpace = true }
                    )
                }
                Box(Modifier.weight(1f)) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = { pop() },
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator()
                        ),
                        sceneStrategies = listOf(listDetail, SinglePaneSceneStrategy()),
                        // Forward: the new screen slides in a quarter-width and fades up; the old one drifts back. Springs move, tweens fade.
                        transitionSpec = {
                            ContentTransform(
                                slideInHorizontally(springSlide(reduce)) { it / 4 } + fadeIn(tween(ms / 2)),
                                slideOutHorizontally(springSlide(reduce)) { -it / 10 } + fadeOut(tween(ms))
                            )
                        },
                        popTransitionSpec = {
                            slideInHorizontally(springSlide(reduce)) {
                                -it / 10
                            } + fadeIn(tween(ms)) togetherWith
                                slideOutHorizontally(springSlide(reduce)) { it / 4 } + fadeOut(tween(ms / 2))
                        },
                        // The back gesture shrinks the screen toward the edge it came from, revealing the one beneath.
                        predictivePopTransitionSpec = {
                            slideInHorizontally(springSlide(reduce)) { -it / 10 } + fadeIn(tween(ms)) togetherWith
                                scaleOut(springSlide(reduce), targetScale = 0.9f) + fadeOut(tween(ms))
                        },
                        entryProvider = entryProvider {
                            entry<TagsKey>(
                                metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = {
                                    NothingOpen(hasNotes = true, onNew = ::new)
                                })
                            ) {
                                TagsPane(
                                    vm = viewModel { TagsViewModel(graph.store, graph.listFilter, graph.space) },
                                    state = graph.tagTree,
                                    docked = false,
                                    onShow = ::show,
                                    onSettings = { backStack += SettingsKey },
                                    spaces = spaces,
                                    space = space,
                                    onSpace = ::switchSpace,
                                    onNewSpace = { creatingSpace = true }
                                )
                            }
                            entry<NotesKey>(
                                metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = {
                                    NothingOpen(hasNotes = true, onNew = ::new)
                                })
                            ) {
                                NotesListScreen(
                                    vm = viewModel {
                                        NotesListViewModel(
                                            graph.store,
                                            graph.deletions,
                                            graph.sync,
                                            graph.listFilter,
                                            graph.space,
                                            graph.scheduler::request
                                        )
                                    },
                                    signedOut = account is Account.SignedOut,
                                    beside = twoPane,
                                    selected = if (twoPane) (backStack.lastOrNull() as? EditorKey)?.id else null,
                                    onSignIn = { backStack += OnboardingKey(signInAgain = true) },
                                    onOpen = { open(it) },
                                    onNew = ::new,
                                    onTags = if (wide) null else ({ tags() }),
                                    spaces = spaces,
                                    space = space,
                                    onOpenElsewhere = { id, s ->
                                        graph.switchSpace(s)
                                        open(id)
                                    }
                                )
                            }
                            entry<EditorKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                                // Email can be set up from another device; check before the share sheet needs it.
                                LaunchedEffect(key.id) { graph.email.refresh() }
                                EditorScreen(
                                    vm = viewModel(key = key.id) {
                                        EditorViewModel(
                                            graph.store,
                                            key.id,
                                            graph.scope,
                                            graph.locks,
                                            key.startTag,
                                            graph.images,
                                            graph.space.value
                                        )
                                    },
                                    locks = graph.locks,
                                    fingerprint = graph.fingerprint,
                                    images = graph.imageLoader,
                                    showBack = !twoPane,
                                    emailFrom = email?.takeIf { it.configured && account is Account.SignedIn }?.from,
                                    emailRequested = emailFor == key.id,
                                    onEmailRequestHandled = { emailFor = null },
                                    sendEmail = { to -> graph.mailer.send(key.id, to) },
                                    onBack = { pop() },
                                    onDelete = {
                                        graph.deletions.request(key.id)
                                        pop()
                                    },
                                    onOpen = { backStack += EditorKey(it) },
                                    spaces = spaces,
                                    onMoved = { pop() },
                                    onOpenTag = { name ->
                                        graph.listFilter.value = NoteFilter.Tag(tagKey(name), name)
                                        // One pane: the list is a screen away.
                                        if (!twoPane) home()
                                    }
                                )
                            }
                            entry<SettingsKey> {
                                val from = backStack.getOrNull(backStack.lastIndexOf(SettingsKey) - 1)
                                SettingsScreen(
                                    graph,
                                    // The sidebar screen has no name to go back to.
                                    backLabel = if (from == TagsKey) null else "Notes",
                                    onBack = { pop() },
                                    onConnect = { backStack += OnboardingKey() },
                                    onSignIn = { backStack += OnboardingKey(signInAgain = true) },
                                    onSpaceCreated = { id ->
                                        graph.switchSpace(id)
                                        home()
                                    }
                                )
                            }
                            entry<OnboardingKey> { key ->
                                val signedOut = account as? Account.SignedOut
                                val firstRun = backStack.size == 1
                                OnboardingScreen(
                                    vm = viewModel {
                                        OnboardingViewModel(
                                            graph.accounts,
                                            graph.deviceName,
                                            { ApiClient(it) },
                                            lockedServer = signedOut?.server?.takeIf { key.signInAgain },
                                            username = signedOut?.username.orEmpty()
                                        )
                                    },
                                    onDone = { home() },
                                    onSkip = if (firstRun && account == Account.None) {
                                        {
                                            graph.setOnboardingDismissed(true)
                                            home()
                                        }
                                    } else {
                                        null
                                    },
                                    onExit = if (firstRun) null else ({ pop() })
                                )
                            }
                        }
                    )
                }
            }
            KeyboardBarSlot(keyboardBar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
