package notes.app.notes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import notes.app.ui.BackLink
import notes.app.ui.Backdrop
import notes.app.ui.Banner
import notes.app.ui.BannerKind
import notes.app.ui.ButtonKind
import notes.app.ui.IconAction
import notes.app.ui.Icons
import notes.app.ui.Kicker
import notes.app.ui.LocalHaze
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.Reveal
import notes.app.ui.Sheet
import notes.app.ui.SheetItem
import notes.app.ui.ToastHost
import notes.app.ui.Type
import notes.app.ui.glass
import notes.app.ui.hairline
import notes.app.ui.pressScale
import notes.core.db.NoteFilter
import notes.core.db.Search
import notes.core.db.Space
import notes.core.db.Spaces
import notes.core.db.SyncEvent
import notes.core.sync.SyncStatus

/** The note a row's more-menu is open for; [elsewhere] is its space when it's a match from another. */
private data class SheetFor(val id: String, val title: String, val elsewhere: String? = null)

/**
 * The notes list. [selected] is the note open beside it in two-pane layouts.
 * [beside]: a note pane sits to the right, so the list draws the line between them.
 */
@Composable
fun NotesListScreen(
    vm: NotesListViewModel,
    signedOut: Boolean,
    selected: String?,
    beside: Boolean,
    onSignIn: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    /** To the tags screen; null when the tags sit in a sidebar beside the list. */
    onTags: (() -> Unit)?,
    /** Every space, and the one shown (§7.7): search names the space, and finds notes in the others. */
    spaces: List<Space> = emptyList(),
    space: String = Spaces.DEFAULT,
    /** A search result from another space: switch to its space, then open it. */
    onOpenElsewhere: (id: String, space: String) -> Unit = { id, _ -> onOpen(id) }
) {
    val t = LocalTokens.current
    val state by vm.state.collectAsStateWithLifecycle()
    val many = spaces.size > 1
    val spaceName = spaces.nameOf(space)
    // The other spaces' matches, listed once asked for, for these words.
    var elsewhereFor by remember { mutableStateOf<String?>(null) }
    val pending by vm.deletions.pending.collectAsStateWithLifecycle()
    val notSynced by vm.notSynced.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sheetFor by remember { mutableStateOf<SheetFor?>(null) }
    var syncSheet by remember { mutableStateOf(false) }
    val toasts = remember { SnackbarHostState() }
    val haze = rememberHazeState()
    val list = rememberLazyListState()

    // One toast for the latest delete; it goes away when that delete is committed or undone.
    val latest = pending.lastOrNull()
    LaunchedEffect(latest) {
        if (latest == null) return@LaunchedEffect
        val result = toasts.showSnackbar("Note deleted", actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) vm.deletions.undo(latest)
    }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            val message = when (event) {
                is SyncEvent.ConflictedCopy -> "Changed in two places. Your version was kept as “${event.title}”."

                is SyncEvent.RecoveredCopy ->
                    "A note you edited was deleted elsewhere. It was kept as “${event.title}”."

                is SyncEvent.Restored ->
                    "“${event.title.ifBlank { "New note" }}” changed on another device and wasn't deleted."
            }
            toasts.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.deletions.commitAll()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.deletions.commitAll()
        }
    }

    val collapsed by remember {
        derivedStateOf {
            list.firstVisibleItemIndex > 0 ||
                list.firstVisibleItemScrollOffset > 120
        }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val searching = state.query.isNotBlank()
    val scope = when (val f = state.filter) {
        null -> null
        NoteFilter.Untagged -> "Untagged"
        NoteFilter.Locked -> "Locked"
        is NoteFilter.Tag -> "#${f.name}"
    }

    CompositionLocalProvider(LocalHaze provides haze) {
        Box(
            Modifier
                .fillMaxSize()
                .background(t.bg)
                .imePadding()
                .then(
                    if (beside) {
                        Modifier.drawWithContent {
                            drawContent()
                            drawRect(t.hairStrong, Offset(size.width - 1f, 0f), Size(1f, size.height))
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            Box(Modifier.fillMaxSize().hazeSource(haze)) {
                Backdrop()
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(top = top + 56.dp, bottom = bottom + 96.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(key = "header", contentType = "header") {
                        Header(
                            notSynced?.pending,
                            scope,
                            icon = spaces.iconOf(space),
                            onClear = { vm.filter(null) },
                            onNotSynced = { syncSheet = true }
                        )
                    }
                    // Nothing to search before the first note.
                    if (state.notes.isNotEmpty() || query.isNotEmpty() || scope != null) {
                        item(key = "search", contentType = "search") {
                            SearchField(query, scope ?: spaceName.takeIf { many }, onChange = {
                                query = it
                                vm.search(it)
                            })
                        }
                    }
                    if (signedOut) {
                        item(key = "signedOut", contentType = "banner") {
                            Banner(
                                "Signed out. Changes stay on this phone until you sign in.",
                                BannerKind.Warn,
                                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                action = "Sign in",
                                onAction = onSignIn
                            )
                        }
                    }
                    when {
                        !state.loaded -> Unit

                        !searching && state.notes.isEmpty() && scope != null -> item(key = "emptyScope") {
                            Empty(
                                kicker = scope,
                                title = "Nothing here yet",
                                text = when (state.filter) {
                                    NoteFilter.Untagged -> "Every note has a tag."
                                    NoteFilter.Locked -> "Lock a note from its menu to keep it here."
                                    else -> "Notes you write while $scope is open start with the tag."
                                },
                                action = "Write a note",
                                onAction = onNew
                            )
                        }

                        searching && state.hits.isEmpty() && state.elsewhereCount == 0 -> item(key = "noMatch") {
                            Empty(
                                kicker = "Search",
                                title = "No matches",
                                text = "Nothing in your notes matches “${state.query.trim()}”."
                            )
                        }

                        !searching && state.notes.isEmpty() -> item(key = "empty") {
                            Empty(
                                kicker = if (many) "No notes in $spaceName yet" else "No notes yet",
                                action = "Write a note",
                                onAction = onNew
                            )
                        }

                        searching -> {
                            if (state.hits.isNotEmpty()) item(key = "results") { GroupLabel(results(state.hits.size)) }
                            items(state.hits, key = { it.id }, contentType = { "row" }) { hit ->
                                NoteRow(
                                    title = hit.title,
                                    time = listTime(hit.updatedAt),
                                    preview = highlight(
                                        hit.excerpt,
                                        SpanStyle(background = t.accent.copy(alpha = 0.45f))
                                    ),
                                    locked = hit.locked,
                                    selected = hit.id == selected,
                                    onOpen = { onOpen(hit.id) },
                                    onMore = { sheetFor = SheetFor(hit.id, hit.title) },
                                    onDelete = { vm.deletions.request(hit.id) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                            val others = state.elsewhere
                            if (state.elsewhereCount > 0 && elsewhereFor != state.query) {
                                item(key = "elsewhere") {
                                    val where = spaces.filter { it.id != space }.singleOrNull()?.name ?: "other spaces"
                                    NotesButton(
                                        "${state.elsewhereCount} more in $where",
                                        { elsewhereFor = state.query },
                                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        kind = ButtonKind.Chip,
                                        small = true
                                    )
                                }
                            }
                            if (others.isNotEmpty() && elsewhereFor == state.query) {
                                item(key = "elsewhere:label") { GroupLabel("Other spaces") }
                                items(others, key = { "elsewhere:${it.id}" }, contentType = { "row" }) { hit ->
                                    NoteRow(
                                        title = hit.title,
                                        time = listTime(hit.updatedAt),
                                        preview = buildAnnotatedString {
                                            withStyle(SpanStyle(color = t.tint)) { append(spaces.nameOf(hit.spaceId)) }
                                            append(" · ")
                                            append(
                                                highlight(
                                                    hit.excerpt,
                                                    SpanStyle(background = t.accent.copy(alpha = 0.45f))
                                                )
                                            )
                                        },
                                        locked = hit.locked,
                                        selected = hit.id == selected,
                                        onOpen = { onOpenElsewhere(hit.id, hit.spaceId) },
                                        onMore = { sheetFor = SheetFor(hit.id, hit.title, hit.spaceId) },
                                        onDelete = { vm.deletions.request(hit.id) },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }

                        else -> for (group in groupByDate(state.notes, { it.updatedAt })) {
                            item(key = "group:${group.label}", contentType = "group") {
                                GroupLabel(group.label, Modifier.animateItem())
                            }
                            items(group.items, key = { it.id }, contentType = { "row" }) { note ->
                                NoteRow(
                                    title = note.title,
                                    time = listTime(note.updatedAt),
                                    preview = AnnotatedString(
                                        note.preview.split('\n').joinToString(" · ").ifEmpty { "No additional text" }
                                    ),
                                    locked = note.locked,
                                    selected = note.id == selected,
                                    onOpen = { onOpen(note.id) },
                                    onMore = { sheetFor = SheetFor(note.id, note.title) },
                                    onDelete = { vm.deletions.request(note.id) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }

            TopBar(collapsed, scope ?: "All Notes", onTags, Modifier.align(Alignment.TopCenter))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                ToastHost(toasts, Modifier.align(Alignment.CenterHorizontally))
                Footer(state.notes.size, searching, onNew)
            }
        }
    }

    sheetFor?.let { (id, title, elsewhere) ->
        Sheet(onDismiss = { sheetFor = null }, title = title.trim().ifEmpty { "New note" }) {
            SheetItem(Icons.Edit, "Open", {
                sheetFor = null
                // A match from another space opens in its space, as tapping the row does.
                if (elsewhere != null) onOpenElsewhere(id, elsewhere) else onOpen(id)
            })
            SheetItem(Icons.Trash, "Delete note", {
                sheetFor = null
                vm.deletions.request(id)
            }, danger = true)
        }
    }

    val stuck = notSynced
    if (syncSheet && stuck != null) {
        Sheet(onDismiss = { syncSheet = false }) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(notSyncedLabel(stuck.pending), style = Type.title, color = t.ink)
                Text(
                    "They're saved on this phone and sync on their own once the server can be reached.",
                    style = Type.body,
                    color = t.ink2
                )
                if (stuck.error != null) Text("The server said: ${stuck.error}", style = Type.small, color = t.ink3)
                Spacer(Modifier.height(4.dp))
                NotesButton("Sync now", {
                    syncSheet = false
                    vm.syncNow()
                }, Modifier.fillMaxWidth())
            }
        }
    }
}

private fun notSyncedLabel(n: Int) = if (n == 1) "1 note not synced" else "$n notes not synced"

private fun results(n: Int) = if (n == 1) "1 result" else "$n results"

/** Transparent over the large title; once it scrolls away, a glass bar with the title small. */
@Composable
private fun TopBar(collapsed: Boolean, title: String, onTags: (() -> Unit)?, modifier: Modifier) {
    val t = LocalTokens.current
    val shown by animateFloatAsState(if (collapsed) 1f else 0f, tween(200), label = "bar")
    Box(modifier.fillMaxWidth()) {
        if (shown > 0f) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = shown }.glass().hairline(t.hair))
        }
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                title,
                style = Type.title.copy(fontSize = 20.sp),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 96.dp)
                    .graphicsLayer { alpha = shown }
                    .clearAndSetSemantics { },
                textAlign = TextAlign.Center
            )
            if (onTags != null) BackLink(null, onTags, Modifier.align(Alignment.CenterStart), description = "Sidebar")
        }
    }
}

/**
 * The large title: what the list shows, All Notes or the filter, with a way back to all notes.
 * [icon]: the space's, leading the title, so the space shows even with the sidebar away.
 * [notSynced]: notes stuck on this phone; a quiet icon at the end, and nothing when sync is fine.
 */
@Composable
private fun Header(notSynced: Int?, scope: String?, icon: String, onClear: () -> Unit, onNotSynced: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpaceIconImage(icon, 28.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            scope ?: "All Notes",
            style = Type.display,
            color = t.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() }
        )
        if (scope != null) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onClear)
                    .semantics { contentDescription = "Show all notes" },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(t.on), contentAlignment = Alignment.Center) {
                    Icon(Icons.Close, null, Modifier.size(13.dp), tint = t.ink2)
                }
            }
        }
        if (notSynced != null) {
            Spacer(Modifier.weight(1f))
            IconAction(Icons.CloudOff, notSyncedLabel(notSynced), onNotSynced, tint = t.ink3)
        }
    }
}

@Composable
private fun SearchField(query: String, scope: String?, onChange: (String) -> Unit) {
    val t = LocalTokens.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(t.field)
            .border(1.dp, t.hair, shape)
            .clickable(indication = null, interactionSource = null) { requester.requestFocus() }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Search, null, Modifier.size(18.dp), tint = t.ink3)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    if (scope == null) "Search" else "Search in $scope",
                    style = Type.body.copy(fontSize = 16.sp),
                    color = t.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.body.copy(fontSize = 16.sp, color = t.ink),
                cursorBrush = SolidColor(t.tint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    .semantics { contentDescription = "Search notes" }
            )
        }
        Reveal(query.isNotEmpty()) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) {
                        onChange("")
                        focus.clearFocus()
                    }
                    .semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(t.on), contentAlignment = Alignment.Center) {
                    Icon(Icons.Close, null, Modifier.size(12.dp), tint = t.ink2)
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(label: String, modifier: Modifier = Modifier) {
    Kicker(label, modifier.padding(start = 24.dp, top = 20.dp, bottom = 6.dp).semantics { heading() })
}

/**
 * One note. Tap opens; long-press opens its actions; swiping left deletes
 * (with Undo). TalkBack gets Delete as a custom action, since swipes aren't
 * available to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    title: String,
    time: String,
    preview: AnnotatedString,
    selected: Boolean,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** The body is encrypted: "Locked" stands in for the preview. */
    locked: Boolean = false
) {
    val t = LocalTokens.current
    val haptics = LocalHapticFeedback.current
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onDelete()
        }
    }
    val shape = RoundedCornerShape(16.dp)
    val bg by animateColorAsState(if (selected) t.on else Color.Transparent, label = "selected")
    val shownTitle = title.trim().ifEmpty { "New note" }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        backgroundContent = {
            if (swipe.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val reached = swipe.targetValue == SwipeToDismissBoxValue.EndToStart
            val color by animateColorAsState(if (reached) t.danger else t.dangerBg, label = "swipe")
            Box(
                Modifier.fillMaxSize().clip(shape).background(color).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Trash, null, Modifier.size(22.dp), tint = if (reached) Color.White else t.danger)
            }
        }
    ) {
        val interaction = remember { MutableInteractionSource() }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                // Opaque only while swiped, to cover the delete colour beneath; at rest the backdrop shows through.
                .background(if (swipe.dismissDirection == SwipeToDismissBoxValue.Settled) Color.Transparent else t.bg)
                .background(bg)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    onClickLabel = "Open",
                    onLongClickLabel = "More actions",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMore()
                    },
                    onClick = onOpen
                )
                .semantics {
                    this.selected = selected
                    customActions = listOf(
                        CustomAccessibilityAction("Delete note") {
                            onDelete()
                            true
                        }
                    )
                }
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(shownTitle, style = Type.rowTitle, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(
                    time,
                    style = Type.rowMeta,
                    fontWeight = FontWeight.Medium,
                    color = t.ink2,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                if (locked) {
                    Icon(Icons.Lock, null, Modifier.size(14.dp).align(Alignment.CenterVertically), tint = t.ink3)
                    Spacer(Modifier.width(3.dp))
                    Text("Locked", style = Type.rowMeta, color = t.ink3, maxLines = 1)
                } else {
                    Text(preview, style = Type.rowMeta, color = t.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Empty(
    kicker: String,
    title: String? = null,
    text: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Without a title the kicker is the only thing naming the state for screen readers.
        Kicker(kicker, if (title == null) Modifier.semantics { heading() } else Modifier)
        if (title != null) Text(title, style = Type.heading, color = t.ink, modifier = Modifier.semantics { heading() })
        if (text != null) Text(text, style = Type.lede, color = t.ink2)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            NotesButton(action, onAction, kind = ButtonKind.Solid, icon = Icons.Compose)
        }
    }
}

/** The web's mobile footer: a glass strip with the compose button at the right. */
@Composable
private fun Footer(count: Int, searching: Boolean, onNew: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .glass()
            .navigationBarsPadding()
            .height(72.dp)
            .padding(start = 24.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (searching) {
                ""
            } else if (count == 1) {
                "1 note"
            } else if (count == 0) {
                ""
            } else {
                "$count notes"
            },
            style = Type.small,
            color = t.ink3,
            modifier = Modifier.weight(1f).clearAndSetSemantics { }
        )
        ComposeButton(onNew)
    }
}

@Composable
private fun ComposeButton(onClick: () -> Unit) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(52.dp)
            .pressScale(interaction, 0.9f)
            .clip(shape)
            .background(t.accent)
            .drawBehind {
                drawRect(t.accentDeep, Offset(0f, size.height - 2.5.dp.toPx()), Size(size.width, 2.5.dp.toPx()))
            }
            .clickable(interaction, ripple(color = t.onAccent), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "New note" },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Compose, null, Modifier.size(24.dp), tint = t.onAccent)
    }
}

/** An excerpt with the store's match markers turned into [style]. */
fun highlight(excerpt: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    var rest = excerpt
    while (rest.isNotEmpty()) {
        val open = rest.indexOf(Search.MARK_OPEN)
        if (open < 0) {
            append(rest)
            break
        }
        append(rest.substring(0, open))
        val close = rest.indexOf(Search.MARK_CLOSE, open).let { if (it < 0) rest.length else it }
        withStyle(style) { append(rest.substring(open + 1, close)) }
        rest = if (close < rest.length) rest.substring(close + 1) else ""
    }
}
