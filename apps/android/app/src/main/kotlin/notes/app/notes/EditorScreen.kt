package notes.app.notes

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.chooser.ChooserAction
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.WeakHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import notes.app.MainActivity
import notes.app.R
import notes.app.editor.FormatState
import notes.app.editor.FormattingBar
import notes.app.editor.ImageSource
import notes.app.editor.LinkDialog
import notes.app.editor.LinkSheet
import notes.app.editor.LinkTap
import notes.app.editor.NoteEditText
import notes.app.editor.TagStrip
import notes.app.editor.keepCursorInView
import notes.app.locks.FingerprintUnlock
import notes.app.locks.LockNoteDialog
import notes.app.locks.LockSetupDialog
import notes.app.locks.LockedNote
import notes.app.ui.Banner
import notes.app.ui.BannerKind
import notes.app.ui.IconAction
import notes.app.ui.Icons
import notes.app.ui.KeyboardBarContent
import notes.app.ui.LocalKeyboardBar
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.Reveal
import notes.app.ui.Sheet
import notes.app.ui.SheetItem
import notes.app.ui.Type
import notes.app.ui.hairline
import notes.app.ui.pressScale
import notes.app.ui.windowInsetsController
import notes.core.crypto.LockSession
import notes.core.db.Space
import notes.core.doc.Document
import notes.core.markdown.Markdown
import notes.core.sync.EmailOutcome

private const val SAVE_DELAY_MS = 700L

/**
 * A note. Saves locally shortly after typing stops, and at once on leaving or
 * backgrounding. [showBack] is false beside the list (two panes), where
 * there's nothing to go back to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    locks: LockSession,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenTag: (String) -> Unit = {},
    showBack: Boolean = true,
    /** The sender's address when the server can email notes; null hides Email from the share sheet. */
    emailFrom: String? = null,
    /** "Email" was chosen in this note's share sheet. */
    emailRequested: Boolean = false,
    onEmailRequestHandled: () -> Unit = {},
    sendEmail: suspend (List<String>) -> EmailOutcome = { EmailOutcome.NotSynced },
    fingerprint: FingerprintUnlock? = null,
    /** Pictures for the note's image runs; null shows placeholders and hides Add picture. */
    images: ImageSource? = null,
    /** Every space (§7.7); with two or more the menu can move the note. */
    spaces: List<Space> = emptyList(),
    /** The note went to another space: leave it. */
    onMoved: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val t = LocalTokens.current
    val loaded by vm.document.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val original by vm.original.collectAsStateWithLifecycle()
    val gone by vm.gone.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val needsUnlock by vm.needsUnlock.collectAsStateWithLifecycle()
    val unreadable by vm.unreadable.collectAsStateWithLifecycle()
    val storedTitle by vm.title.collectAsStateWithLifecycle()
    val imageProblem by vm.imageProblem.collectAsStateWithLifecycle()

    /** Lock note was chosen, and the session needs a password created or entered first. */
    var locking by remember { mutableStateOf<LockSession.State?>(null) }

    /** Locked from this screen: the fingerprint isn't asked for straight back. */
    var lockedHere by remember { mutableStateOf(false) }

    /** Lock now couldn't save this note, so the key stayed: notes are still unlocked. */
    var lockFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<NoteEditText?>(null) }
    var state by remember { mutableStateOf(FormatState()) }
    var edits by remember { mutableIntStateOf(0) }
    var scrolled by remember { mutableStateOf(false) }
    var linkTap by remember { mutableStateOf<LinkTap?>(null) }
    var linkDialog by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var emailing by remember { mutableStateOf(false) }

    fun save() {
        editor?.let { vm.save(it.document()) }
    }

    LaunchedEffect(gone) { if (gone) onBack() }
    LaunchedEffect(emailRequested) {
        if (!emailRequested) return@LaunchedEffect
        emailing = true
        onEmailRequestHandled()
    }
    LaunchedEffect(vm) { vm.incoming.collect { editor?.setDocument(it, keepSelection = true) } }
    LaunchedEffect(edits) {
        if (edits == 0) return@LaunchedEffect
        delay(SAVE_DELAY_MS)
        save()
    }
    // A locked note stays out of screenshots and the recent-apps thumbnail.
    val window = LocalActivity.current?.window
    DisposableEffect(window, locked) {
        if (locked) window?.let(SecureWindows::acquire)
        onDispose { if (locked) window?.let(SecureWindows::release) }
    }
    // Only the prompt straight after "Lock now" is skipped; later relocks ask again.
    LaunchedEffect(needsUnlock) {
        if (!needsUnlock) lockedHere = false else lockFailed = false
    }

    fun setLocked(on: Boolean) {
        val ids = editor?.imageIds() ?: return
        // The session itself, not the collected state: right after a dialog unlocks, that lags a frame.
        val now = locks.state.value
        if (on && now != LockSession.State.Unlocked) {
            locking = now
            return
        }
        scope.launch {
            // The pictures go with the lock (§10.1): stored again encrypted, or plain, before the note is.
            // Applied only once all have moved: a failure partway leaves the note as it was.
            var pending = ids
            val done = HashSet<String>()
            val moved = HashMap<String, String>()
            while (true) {
                moved += vm.moveImages(pending, on) ?: return@launch
                val e = editor ?: return@launch
                done += pending
                // Pictures added while those moved go with the lock too.
                pending = e.imageIds() - done
                if (pending.isEmpty()) break
            }
            val e = editor ?: return@launch
            e.replaceImages(moved)
            vm.setLocked(e.document(), on)
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { vm.addImage(context.contentResolver, uri)?.let { editor?.insertImage(it) } }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) save() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            save()
        }
    }

    val typing = WindowInsets.isImeVisible
    // The keyboard can be up for the list's search beside the note; the toolbars are only for typing here.
    var focused by remember { mutableStateOf(false) }
    val keyboardBar = LocalKeyboardBar.current
    val toolbars: @Composable () -> Unit = {
        Reveal(typing && focused) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.tag?.let { tag ->
                    TagStrip(
                        tag,
                        tags,
                        onPick = { name -> editor?.insertTag(tag.start, tag.end, name) },
                        onShow = {
                            save()
                            onOpenTag(it)
                        }
                    )
                }
                FormattingBar(
                    state,
                    { editor },
                    onLink = { linkDialog = editor?.linkContext() },
                    onImage = images?.let {
                        {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                )
            }
        }
    }
    if (keyboardBar != null) KeyboardBarContent(keyboardBar, toolbars)
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .then(if (scrolled) Modifier.hairline(t.hair) else Modifier)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) BackLink(onBack) else Spacer(Modifier.width(12.dp))
            Spacer(Modifier.weight(1f))
            if (!needsUnlock) {
                if (locked) {
                    IconAction(Icons.Lock, "Lock now", {
                        // Typing from the last moments saves (encrypted) before the key goes.
                        save()
                        lockedHere = true
                        lockFailed = false
                        scope.launch {
                            if (!locks.lock()) {
                                // Still unlocked: a later lock (idle, leaving the app) asks for the fingerprint again.
                                lockedHere = false
                                lockFailed = true
                            }
                        }
                    })
                }
                IconAction(Icons.Undo, "Undo", { editor?.undo() }, enabled = state.canUndo)
                IconAction(Icons.Redo, "Redo", { editor?.redo() }, enabled = state.canRedo)
                IconAction(Icons.More, "More", { menu = true })
            }
        }
        if (needsUnlock || unreadable) {
            LockedNote(
                storedTitle,
                unreadable,
                locks,
                Modifier.weight(1f),
                fingerprint = fingerprint,
                promptFingerprint = !lockedHere
            )
        }
        imageProblem?.let { message ->
            Banner(
                message,
                BannerKind.Error,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                action = "Dismiss",
                onAction = vm::dismissImageProblem
            )
        }
        if (lockFailed && !needsUnlock) {
            Banner(
                "This note couldn't be saved, so locked notes are still unlocked. Try Lock now again.",
                BannerKind.Error,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        original?.let { o ->
            Banner(
                "Kept from a conflict with “${o.title.ifBlank { "New note" }}”",
                BannerKind.Warn,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                action = "Open",
                onAction = { onOpen(o.id) }
            )
        }
        val doc = loaded
        if (doc != null && !needsUnlock) {
            AndroidView(
                factory = { ctx ->
                    // The scroll view, not the EditText, scrolls: TextView's own scrolling has no fling or edge stretch.
                    val scroller = NestedScrollView(ctx).apply {
                        isFillViewport = true
                        isVerticalScrollBarEnabled = true
                        setOnScrollChangeListener { _: NestedScrollView, _: Int, y: Int, _: Int, _: Int ->
                            scrolled = y > 0
                        }
                    }
                    NoteEditText(ctx).apply {
                        val pad = editorStyle.dp(22f).toInt()
                        setPadding(pad, editorStyle.dp(6f).toInt(), pad, editorStyle.dp(40f).toInt())
                        hint = "Title"
                        imageSource = images
                        setDocument(doc)
                        onChange = {
                            vm.edited()
                            edits++
                        }
                        onFormatState = { state = it }
                        onLinkTap = { linkTap = it }
                        setOnFocusChangeListener { _, has -> focused = has }
                        editor = this
                        // A new note opens ready to type the title. Focus alone doesn't raise the keyboard, and
                        // neither works before the view is attached.
                        if (vm.isNew) {
                            doOnAttach {
                                requestFocus()
                                // After focus: gaining focus moves the cursor to the end (the body).
                                setSelection(0)
                                windowInsetsController()?.show(WindowInsetsCompat.Type.ime())
                            }
                        }
                        scroller.addView(this, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                        scroller.keepCursorInView(this)
                    }
                    scroller
                },
                onRelease = { editor = null },
                // Compose doesn't clip a native view: the edge stretch would draw the text over the top bar.
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()
            )
            if (keyboardBar != null) Spacer(Modifier.height(keyboardBar.height)) else toolbars()
            if (!typing) Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (menu) {
        Sheet(onDismiss = { menu = false }, title = editor?.document()?.title?.trim()?.ifEmpty { null } ?: "New note") {
            SheetItem(Icons.Share, "Share", {
                menu = false
                save()
                // The server has only a locked note's ciphertext: it can't email it.
                editor?.document()?.let {
                    share(context, it, emailNoteId = vm.id.takeIf { emailFrom != null && !locked })
                }
            })
            // A blank new note has nothing to lock yet.
            if (status != SaveStatus.Blank) {
                SheetItem(Icons.Lock, if (locked) "Remove lock" else "Lock note", {
                    menu = false
                    setLocked(!locked)
                })
            }
            // Nor anything to move: a new note goes to the space it was written in.
            if (spaces.size > 1 && status != SaveStatus.Blank) {
                SheetItem(Icons.OpenExternal, "Move to space…", {
                    menu = false
                    save()
                    moving = true
                })
            }
            // A blank new note has nothing to delete: leaving discards it.
            if (status != SaveStatus.Blank) {
                SheetItem(Icons.Trash, "Delete note", {
                    menu = false
                    save()
                    onDelete()
                }, danger = true)
            }
        }
    }

    if (moving) {
        SpacePicker(
            "Move to space",
            spaces.filter {
                it.id != vm.spaceId()
            },
            null,
            onDismiss = { moving = false }
        ) { id ->
            moving = false
            vm.move(id)
            onMoved(id)
        }
    }

    linkTap?.let { tap ->
        LinkSheet(
            tap,
            onDismiss = { linkTap = null },
            onOpen = {
                linkTap = null
                open(context, tap.href)
            },
            onEdit = {
                linkTap = null
                editor?.setSelection(tap.start, tap.end)
                linkDialog = editor?.linkContext()
            },
            onRemove = {
                linkTap = null
                editor?.removeLink(tap.start, tap.end)
            }
        )
    }
    linkDialog?.let { (text, href) ->
        LinkDialog(text, href, onDismiss = { linkDialog = null }) { url, label ->
            linkDialog = null
            editor?.setLink(url, label)
        }
    }
    when (locking) {
        LockSession.State.None -> LockSetupDialog(locks, onDismiss = { locking = null }, fingerprint = fingerprint) {
            locking = null
            setLocked(true)
        }

        LockSession.State.Locked -> LockNoteDialog(locks, onDismiss = { locking = null }, fingerprint = fingerprint) {
            locking = null
            setLocked(true)
        }

        else -> Unit
    }
    if (emailing) {
        EmailDialog(emailFrom, onDismiss = { emailing = false }) { to ->
            save()
            vm.saved()
            sendEmail(to)
        }
    }
}

/**
 * FLAG_SECURE is per window, but two editors can share it: during a
 * transition the incoming locked note sets it before the outgoing one is
 * disposed. Counted, so the last locked editor to go clears it. Main thread only.
 */
private object SecureWindows {
    private val holders = WeakHashMap<Window, Int>()

    fun acquire(window: Window) {
        val n = holders[window] ?: 0
        holders[window] = n + 1
        if (n == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        val n = (holders[window] ?: return) - 1
        if (n > 0) {
            holders[window] = n
        } else {
            holders.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** The web's mobile back: a chevron and "Notes" in the tint colour. */
@Composable
private fun BackLink(onClick: () -> Unit) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction, 0.94f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Back" }
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Back, null, Modifier.size(22.dp), tint = t.tint)
        // At very large text the word would crowd the status out of the bar; the chevron says enough.
        if (LocalDensity.current.fontScale <= 1.3f) {
            Text("Notes", style = Type.body.copy(fontSize = 16.sp), color = t.tint)
        }
    }
}

/** The note as one Markdown file: the title as a heading, then the canonical body. */
private fun exportMarkdown(doc: Document): String {
    val title = doc.title.orEmpty().trim()
    val body = Markdown.serialize(doc)
    return if (title.isEmpty()) body else "# $title\n\n$body"
}

/** The system share sheet, plus our own Email action when [emailNoteId] is set (the server can send it). */
private fun share(context: Context, doc: Document, emailNoteId: String?) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, doc.title.orEmpty())
        .putExtra(Intent.EXTRA_TEXT, exportMarkdown(doc))
    val chooser = Intent.createChooser(send, null)
    if (emailNoteId != null) addEmailAction(context, chooser, emailNoteId)
    context.startActivity(chooser)
}

/**
 * Back into this app with the note's id; CLEAR_TOP + SINGLE_TOP reach the
 * running activity under the share sheet instead of starting another.
 */
internal fun emailIntent(context: Context, noteId: String): Intent = Intent(context, MainActivity::class.java)
    .setAction(MainActivity.ACTION_EMAIL_NOTE)
    .putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

internal fun addEmailAction(context: Context, chooser: Intent, noteId: String) {
    val intent = emailIntent(context, noteId)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pending = PendingIntent.getActivity(
            context,
            noteId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = ChooserAction.Builder(
            Icon.createWithResource(context, R.drawable.ic_share_email),
            "Email",
            pending
        )
            .build()
        chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, arrayOf(action))
    } else {
        // Before custom actions, our own target among the apps.
        val target = LabeledIntent(intent, context.packageName, "Email", R.drawable.ic_share_email)
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<Intent>(target))
    }
}

private fun open(context: Context, href: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, href.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing can open it; the sheet already showed the address.
    }
}
