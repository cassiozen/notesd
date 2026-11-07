package notes.app.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Layout
import android.text.Selection
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.MetricAffectingSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import kotlin.math.abs
import notes.app.ui.Tokens
import notes.core.doc.Container
import notes.core.doc.Document
import notes.core.doc.Leaf
import notes.core.doc.ListKind
import notes.core.doc.Mark
import notes.core.images.ImageRef
import notes.core.text.tagText

/** What the formatting bar shows for the current selection. */
data class FormatState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val link: Boolean = false,
    val heading: Int = 0,
    val list: ListKind? = null,
    val quote: Boolean = false,
    val canIndent: Boolean = false,
    val canOutdent: Boolean = false,
    val inTitle: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Text is selected: the bar shows marks only. */
    val selecting: Boolean = false,
    /** The tag the cursor is in, if any. */
    val tag: TagAtCursor? = null
)

/**
 * A tag around the cursor, from its # to its end. [query] is what's typed after
 * the # when the cursor is at the end of an unclosed tag: what to complete.
 */
data class TagAtCursor(val name: String, val start: Int, val end: Int, val query: String?)

/** A tap on a link: its target and where it sits in the text. */
data class LinkTap(val href: String, val title: String?, val start: Int, val end: Int)

/**
 * The note editor: one [AppCompatEditText] holding the whole note as a single
 * Spannable (title line first), so selection, IME, autocorrect, context menus
 * and accessibility are the platform's. Structure lives in spans (see
 * [BlockSpan]); after every change the [Normalizer] re-derives it.
 *
 * Text is never replaced while the user types. [setDocument] is for loading a
 * note or applying a remote change, and keeps the selection by offset.
 */
class NoteEditText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatEditText(context, attrs),
    ImageHost {
    override val editorStyle: EditorStyle = EditorStyle.from(context)
    private val imageSpans = ImageSpans(this)
    private val normalizer = Normalizer(editorStyle, imageSpans)
    private val undoStack = UndoStack()

    /** >0 while the editor changes its own text: filters and watchers stand aside. */
    private var guard = 0
    private var editing = false
    private var recorded = false
    private var changeStart = 0
    private var changeEnd = 0

    // Marks toggled with a collapsed selection, applied to what's typed next.
    private var pendingStart = -1
    private var pendingEnd = -1
    private val pendingOn = HashSet<Mark>()
    private val pendingOff = HashSet<Mark>()

    private var boxDown = -1
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** Pictures for image runs; without one they show as placeholders. */
    override var imageSource: ImageSource? = null
        set(value) {
            field = value
            invalidate()
        }

    override val imageMaxWidth: Int get() = width - totalPaddingLeft - totalPaddingRight

    override val imageLineSpacing: Float get() = lineSpacingMultiplier

    override fun imageReady(id: String) {
        val t = text ?: return
        imageSpans.refresh(t, id)
    }

    var onChange: (() -> Unit)? = null
    var onFormatState: ((FormatState) -> Unit)? = null
    var onLinkTap: ((LinkTap) -> Unit)? = null

    private val ready: Boolean

    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        isSingleLine = false
        maxLines = Int.MAX_VALUE
        gravity = Gravity.TOP or Gravity.START
        background = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setLineSpacing(0f, editorStyle.lineSpacing)
        typeface = editorStyle.typeface
        applyColors()
        filters = arrayOf(EditorFilter())
        addTextChangedListener(Watcher())
        ready = true
        setDocument(Document.forNote("", Document.EMPTY))
    }

    /** A space's theme: the view's own colours change here, and spans read theirs as they draw. */
    fun applyTokens(tokens: Tokens) {
        if (editorStyle.tokens == tokens) return
        editorStyle.tokens = tokens
        applyColors()
        invalidate()
    }

    // A changed text, hint or link colour also rebuilds TextView's cached drawing of the text, where the spans'
    // colours are; the link colour comes from the accent, so even a theme with the same ink redraws them.
    private fun applyColors() {
        setTextColor(editorStyle.ink)
        setHintTextColor(editorStyle.ink3)
        setLinkTextColor(editorStyle.link)
        highlightColor = ColorUtils.setAlphaComponent(editorStyle.accent, 0x66)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The web app's caret is the tint colour; handles match it.
            textCursorDrawable?.mutate()?.setTint(editorStyle.tint)
            for (handle in listOf(textSelectHandle, textSelectHandleLeft, textSelectHandleRight)) {
                handle?.mutate()?.setTint(editorStyle.accentDeep)
            }
        }
    }

    // --- content -------------------------------------------------------------

    /** The note as a document: title line first. Cheap enough per save, not per keystroke. */
    fun document(): Document = SpanCodec.toDocument(text!!)

    // Coloured as they draw: the theme can change between draws.
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val findPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val findCurrentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillMetrics = TextPaint()
    private val pillRect = RectF()

    override fun onDraw(canvas: Canvas) {
        drawPills(canvas)
        super.onDraw(canvas)
    }

    /** Tag and find pills, under the text. */
    private fun drawPills(canvas: Canvas) {
        val t = text ?: return
        val layout = layout ?: return
        val tags = t.getSpans(0, t.length, TagSpan::class.java)
        val found = t.getSpans(0, t.length, FindSpan::class.java)
        if (tags.isEmpty() && found.isEmpty()) return
        tagPaint.color = editorStyle.tagBg
        findPaint.color = editorStyle.findBg
        findCurrentPaint.color = editorStyle.findCurrentBg
        canvas.withTranslation(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat()) {
            for (tag in tags) drawPill(this, layout, t, t.getSpanStart(tag), t.getSpanEnd(tag), tagPaint)
            for (f in found) {
                val pill = if (f.current) findCurrentPaint else findPaint
                drawPill(this, layout, t, t.getSpanStart(f), t.getSpanEnd(f), pill)
            }
        }
    }

    /** The web's tag pill: as tall as the letters, not the line, one per line the range wraps across. */
    private fun drawPill(canvas: Canvas, layout: Layout, t: Spanned, start: Int, end: Int, pill: Paint) {
        if (start < 0 || end <= start) return
        // Headings scale their text: measure with the spans that size it.
        pillMetrics.set(paint)
        for (s in t.getSpans(start, end, MetricAffectingSpan::class.java)) s.updateMeasureState(pillMetrics)
        val fm = pillMetrics.fontMetrics
        for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end)) {
            val from = maxOf(start, layout.getLineStart(line))
            val to = minOf(end, layout.getLineEnd(line))
            if (to <= from) continue
            val left = layout.getPrimaryHorizontal(from)
            // To the line's end means it wraps there, where that offset would measure at the next line's start.
            val wraps = to == layout.getLineEnd(line) && line < layout.lineCount - 1
            val right = if (wraps) layout.getLineRight(line) else layout.getPrimaryHorizontal(to)
            val baseline = layout.getLineBaseline(line).toFloat()
            pillRect.set(
                left - editorStyle.tagPadX,
                baseline + fm.ascent,
                right + editorStyle.tagPadX,
                baseline + fm.descent
            )
            canvas.drawRoundRect(pillRect, editorStyle.tagRadius, editorStyle.tagRadius, pill)
        }
    }

    /** Loads [doc]. With [keepSelection], the cursor stays at the same offset (a remote change). */
    fun setDocument(doc: Document, keepSelection: Boolean = false) {
        val selStart = selectionStart
        val selEnd = selectionEnd
        val sb = SpanCodec.toSpannable(doc, editorStyle)
        normalizer.normalize(sb)
        guard++
        try {
            setText(sb, BufferType.EDITABLE)
        } finally {
            guard--
        }
        val len = text!!.length
        if (keepSelection) setSelection(selStart.coerceIn(0, len), selEnd.coerceIn(0, len))
        if (!keepSelection) undoStack.clear()
        clearPending()
        refreshFind()
        publishState()
    }

    // --- formatting commands -------------------------------------------------

    fun toggleBold() = toggleMark(Mark.Bold)

    fun toggleItalic() = toggleMark(Mark.Italic)

    fun toggleStrike() = toggleMark(Mark.Strike)

    fun toggleHeading(level: Int) = blockCommand { edits, sel -> edits.toggleHeading(sel, level) }

    fun toggleList(kind: ListKind) = blockCommand { edits, sel -> edits.toggleList(sel, kind) }

    fun toggleQuote() = blockCommand { edits, sel -> edits.toggleQuote(sel) }

    fun indent() = blockCommand { edits, sel -> sel.forEach(edits::indent) }

    fun outdent() = blockCommand { edits, sel -> sel.forEach(edits::outdent) }

    /** Links the selection, or inserts [label] (default: the address) as a link at the cursor. */
    fun setLink(href: String, label: String? = null) = command { text ->
        val target = normalizeHref(href) ?: return@command
        var start = selectionStart
        var end = selectionEnd
        Marks.linkAt(text, start)?.takeIf { start == end }?.let {
            start = text.getSpanStart(it)
            end = text.getSpanEnd(it)
        }
        if (start == end) {
            val shown = label?.takeIf { it.isNotBlank() } ?: target
            text.insert(start, shown)
            end = start + shown.length
        }
        Marks.remove(text, Mark.Link("", null), start, end, editorStyle)
        Marks.add(text, Mark.Link(target, null), start, end, editorStyle)
        setSelection(end)
    }

    /** Replaces [start] until [end] (a tag being typed) with [name], closed if it needs it, and a space. */
    fun insertTag(start: Int, end: Int, name: String) = command { text ->
        val written = tagText(name)
        val spaced = end < text.length && text[end] == ' '
        text.replace(start, end, if (spaced) written else "$written ")
        setSelection(start + written.length + 1)
    }

    private fun tagAt(t: Editable, cursor: Int): TagAtCursor? {
        val span = t.getSpans(cursor, cursor, TagSpan::class.java)
            .firstOrNull { t.getSpanStart(it) < cursor && cursor <= t.getSpanEnd(it) } ?: return null
        val s = t.getSpanStart(span)
        val e = t.getSpanEnd(span)
        val query = if (cursor == e && t[e - 1] != '#') t.substring(s + 1, e) else null
        return TagAtCursor(span.name, s, e, query)
    }

    fun removeLink(start: Int, end: Int) = command { text ->
        Marks.remove(text, Mark.Link("", null), start, end, editorStyle)
    }

    /** The selected text and any link on it, for the link dialog. */
    fun linkContext(): Pair<String, String?> {
        val t = text!!
        val start = selectionStart
        val end = selectionEnd
        val link = Marks.linkAt(t, start)
        return t.substring(start, end) to link?.href
    }

    /**
     * Puts an image run ([source], an app image reference) on its own line:
     * in the empty line the cursor is on, or after the cursor's line.
     */
    fun insertImage(source: String) = command { text ->
        val block = Blocks.at(text, selectionStart)
        val at: Int
        val prefix: String
        if (block != null && block.start == block.contentEnd && block.span.leaf != Leaf.Title) {
            at = block.start
            prefix = ""
        } else {
            at = block?.contentEnd ?: text.length
            prefix = "\n"
        }
        val run = SpannableString(prefix + source)
        run.setSpan(RawInlineSpan(source, editorStyle), prefix.length, run.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.insert(at, run)
        setSelection(at + run.length)
    }

    /** The ids of the pictures in the note. */
    fun imageIds(): Set<String> {
        val t = text ?: return emptySet()
        val runs = t.getSpans(0, t.length, RawInlineSpan::class.java)
        return runs.mapNotNull { ImageRef.parse(it.source)?.id }.toSet()
    }

    /** Points image runs at other images (old id → new id), as a lock change does; the rest of the text is untouched. */
    fun replaceImages(moved: Map<String, String>) {
        if (moved.isEmpty()) return
        command { text ->
            val runs = text.getSpans(0, text.length, RawInlineSpan::class.java)
                .sortedByDescending { text.getSpanStart(it) }
            for (raw in runs) {
                val ref = ImageRef.parse(raw.source) ?: continue
                val next = moved[ref.id] ?: continue
                val s = text.getSpanStart(raw)
                val e = text.getSpanEnd(raw)
                val source = raw.source.replace("notesd:image/${ref.id}", "notesd:image/$next")
                val run = SpannableString(source)
                run.setSpan(RawInlineSpan(source, editorStyle), 0, run.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.removeSpan(raw)
                text.replace(s, e, run)
            }
        }
    }

    fun insertHardBreak() {
        val t = text ?: return
        val br = SpannableString("\n")
        br.setSpan(HardBreakSpan(), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        t.replace(selectionStart, selectionEnd, br)
    }

    fun undo() = restore(undoStack.undo(currentState()))

    fun redo() = restore(undoStack.redo(currentState()))

    private fun toggleMark(mark: Mark) {
        val start = selectionStart
        val end = selectionEnd
        if (start == end) {
            val active = (Marks.at(text!!, start) + pendingOn) - pendingOff
            if (pendingStart != start) clearPending()
            pendingStart = start
            pendingEnd = start
            if (mark in active) {
                pendingOn -= mark
                pendingOff += mark
            } else {
                pendingOff -= mark
                pendingOn += mark
            }
            publishState()
            return
        }
        command { text ->
            if (Marks.covers(text, mark, start, end)) {
                Marks.remove(text, mark, start, end, editorStyle)
            } else {
                Marks.add(text, mark, start, end, editorStyle)
            }
        }
    }

    private fun blockCommand(change: (BlockEdits, List<Int>) -> Unit) = command { text ->
        val edits = BlockEdits(text, editorStyle)
        change(edits, edits.selected(selectionStart, selectionEnd))
        edits.commit()
    }

    private fun command(change: (Editable) -> Unit) {
        val t = text ?: return
        undoStack.record(UndoStack.Kind.Command, SystemClock.uptimeMillis(), ::currentState)
        guard++
        try {
            change(t)
            normalizer.normalize(t, composingStart(t), composingEnd(t))
        } finally {
            guard--
        }
        changed()
    }

    private fun changed() {
        refreshFind()
        onChange?.invoke()
        publishState()
    }

    private fun currentState() = UndoStack.snapshot(text!!, selectionStart, selectionEnd)

    private fun restore(state: UndoStack.State?) {
        val t = text ?: return
        if (state == null) return
        guard++
        val saved = t.filters
        try {
            t.filters = emptyArray()
            for (span in t.getSpans(0, t.length, EditorSpan::class.java)) t.removeSpan(span)
            t.replace(0, t.length, state.text)
            Selection.setSelection(t, state.selStart.coerceIn(0, t.length), state.selEnd.coerceIn(0, t.length))
            normalizer.normalize(t)
        } finally {
            t.filters = saved
            guard--
        }
        clearPending()
        context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
        changed()
    }

    private fun normalizeHref(href: String): String? {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return null
        if (Regex("^(https?:|mailto:)", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return trimmed
        if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return null
        return if ('@' in trimmed && '/' !in trimmed) "mailto:$trimmed" else "https://$trimmed"
    }

    // --- find ----------------------------------------------------------------

    /** The find bar's query; null while not finding. */
    private var findQuery: String? = null

    /** Where the current match is looked for from: the cursor when finding started, then the last match moved to. */
    private var findFrom = 0
    private var matches = emptyList<FindMatch>()
    private var current = -1

    var onFindState: ((FindState) -> Unit)? = null

    /** Ctrl+F on a hardware keyboard. */
    var onFindRequest: (() -> Unit)? = null

    val finding: Boolean get() = findQuery != null

    /** The selection, when it's text on one line: what to find. */
    fun selectedLine(): String? {
        val t = text ?: return null
        val s = minOf(selectionStart, selectionEnd)
        val e = maxOf(selectionStart, selectionEnd)
        if (s < 0 || s == e) return null
        return t.substring(s, e).takeIf { it.isNotBlank() && '\n' !in it }
    }

    /** Highlights [query]'s matches; the current one is the first at or after where finding started. */
    fun find(query: String) {
        if (findQuery == null) findFrom = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        findQuery = query
        // Looked for from findFrom, not from where the last query's match was.
        clearFindSpans()
        refreshFind()
        revealMatch()
    }

    fun findNext() = step(1)

    fun findPrevious() = step(-1)

    private fun step(by: Int) {
        if (matches.isEmpty()) return
        current = Math.floorMod(current + by, matches.size)
        findFrom = matches[current].start
        paintFind()
        publishFind()
        revealMatch()
    }

    /** Replaces the current match with [with], keeping its formatting, and moves to the next. */
    fun replace(with: String) {
        val t = text ?: return
        val m = matches.getOrNull(current) ?: return
        if (Find.readOnly(t, m)) return findNext()
        clearFindSpans()
        findFrom = m.start + with.length
        command { replaceMatch(it, m, with) }
        revealMatch()
    }

    /** Replaces every match that can be edited, as one undo step. Returns how many. */
    fun replaceAll(with: String): Int {
        val t = text ?: return 0
        val editable = matches.filterNot { Find.readOnly(t, it) }
        if (editable.isEmpty()) return 0
        // From the end, so the offsets of the matches still to go don't move.
        command { e -> editable.asReversed().forEach { replaceMatch(e, it, with) } }
        publishFind(replaced = editable.size)
        return editable.size
    }

    /** Stops finding: the highlights go and the current match is selected. */
    fun endFind() {
        if (findQuery == null) return
        val m = matches.getOrNull(current)
        findQuery = null
        matches = emptyList()
        current = -1
        clearFindSpans()
        if (m != null) setSelection(m.start, m.end)
    }

    /** The current match's range, for scrolling to it. */
    internal fun currentMatch(): FindMatch? = matches.getOrNull(current)

    /** Replaced text takes the formatting of the first character it replaces, as typing over a selection does. */
    private fun replaceMatch(text: Editable, m: FindMatch, with: String) {
        val marks = text.getSpans(m.start, m.start + 1, EditorSpan::class.java)
            .filter { text.getSpanStart(it) <= m.start && text.getSpanEnd(it) > m.start }
            .mapNotNull(SpanCodec::markOf)
        text.replace(m.start, m.end, with)
        val end = m.start + with.length
        if (end == m.start) return
        for (mark in listOf(Mark.Bold, Mark.Italic, Mark.Strike, Mark.Link("", null))) {
            Marks.remove(text, mark, m.start, end, editorStyle)
        }
        for (mark in marks) Marks.add(text, mark, m.start, end, editorStyle)
    }

    /** Matches again after the text changed; the current match stays the one it was, or the next. */
    private fun refreshFind() {
        val query = findQuery ?: return
        val t = text ?: return
        val anchor = t.getSpans(0, t.length, FindSpan::class.java).firstOrNull { it.current }
            ?.let(t::getSpanStart)?.takeIf { it >= 0 } ?: findFrom
        matches = Find.matches(t, query)
        current = matches.indexOfFirst { it.start >= anchor }.let { if (it < 0 && matches.isNotEmpty()) 0 else it }
        paintFind()
        publishFind()
    }

    private fun paintFind() {
        val t = text ?: return
        clearFindSpans()
        matches.forEachIndexed { i, m ->
            t.setSpan(FindSpan(i == current, editorStyle), m.start, m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun clearFindSpans() {
        val t = text ?: return
        for (span in t.getSpans(0, t.length, FindSpan::class.java)) t.removeSpan(span)
    }

    private fun publishFind(replaced: Int? = null) {
        onFindState?.invoke(FindState(matches.size, current, replaced))
    }

    // --- state ---------------------------------------------------------------

    private fun publishState() {
        if (!ready) return
        val listener = onFormatState ?: return
        val t = text ?: return
        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(0)
        val edits = BlockEdits(t, editorStyle)
        val k = edits.indexAt(start)
        val path = edits.path(k)
        val marks = if (start == end) {
            (Marks.at(t, start) + pendingOn) - pendingOff
        } else {
            listOf(Mark.Bold, Mark.Italic, Mark.Strike, Mark.Link("", null))
                .filter { Marks.covers(t, it, start, end) }.toSet()
        }
        val sel = edits.selected(start, end)
        listener(
            FormatState(
                bold = Mark.Bold in marks,
                italic = Mark.Italic in marks,
                strike = Mark.Strike in marks,
                link = marks.any { it is Mark.Link },
                heading = (edits.leaf(k) as? Leaf.Heading)?.level ?: 0,
                list = (path.lastOrNull() as? Container.Item)?.kind,
                quote = path.any { it is Container.Quote },
                canIndent = sel.any(edits::canIndent),
                canOutdent = sel.any(edits::canOutdent),
                inTitle = k == 0,
                canUndo = undoStack.canUndo,
                canRedo = undoStack.canRedo,
                selecting = start != end,
                tag = if (start == end) tagAt(t, start) else null
            )
        )
    }

    private fun clearPending() {
        pendingStart = -1
        pendingEnd = -1
        pendingOn.clear()
        pendingOff.clear()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // Never past the end marker: typing there would land outside the block's content.
        val marker = text?.let(::endMarkerStart) ?: -1
        if (marker in 0 until maxOf(selStart, selEnd)) {
            setSelection(minOf(selStart, marker), minOf(selEnd, marker))
            return
        }
        if (!ready || editing) return
        if (pendingStart >= 0 && (selStart != selEnd || selStart !in pendingStart..pendingEnd)) clearPending()
        publishState()
    }

    private fun composingStart(t: android.text.Spannable) = BaseInputConnection.getComposingSpanStart(t)

    private fun composingEnd(t: android.text.Spannable) = BaseInputConnection.getComposingSpanEnd(t)

    // --- text changes ----------------------------------------------------------

    private inner class Watcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            if (guard > 0) return
            editing = true
            if (recorded) {
                recorded = false
                return
            }
            val kind = if (count > 0 && after == 0) UndoStack.Kind.Deleting else UndoStack.Kind.Typing
            undoStack.record(kind, SystemClock.uptimeMillis(), ::currentState)
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (guard > 0) return
            changeStart = start
            changeEnd = start + count
        }

        override fun afterTextChanged(s: Editable) {
            if (guard > 0) return
            // Typing after a new line is a new undo step.
            if ((changeStart until changeEnd).any { s[it] == '\n' }) undoStack.breakCoalescing()
            guard++
            try {
                normalizer.normalize(s, composingStart(s), composingEnd(s))
                applyPending(s)
            } finally {
                guard--
                editing = false
            }
            changed()
        }
    }

    private fun applyPending(s: Editable) {
        if (pendingStart < 0 || changeEnd <= changeStart || changeStart !in pendingStart..pendingEnd) return
        for (mark in pendingOn) Marks.add(s, mark, changeStart, changeEnd, editorStyle)
        for (mark in pendingOff) Marks.remove(s, mark, changeStart, changeEnd, editorStyle)
        pendingEnd = changeEnd
    }

    /**
     * Rules that must act before a change lands: raw text is read-only, Backspace
     * at the start of a list item or quote removes the marker first, and Enter on
     * an empty item or quote line leaves it instead of adding a line.
     */
    private inner class EditorFilter : InputFilter {
        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: Spanned,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            if (guard > 0 || !ready) return null
            val text = dest as? Editable ?: return null
            val inserted = source.subSequence(start, end)
            rawRule(text, inserted, dstart, dend)?.let { return it }
            if (inserted.isEmpty() && dend - dstart == 1 && text[dstart] == '\n') {
                separatorDeletion(text, dstart)?.let { return it }
            }
            val hardBreak = source is Spanned && source.getSpans(start, end, HardBreakSpan::class.java).isNotEmpty()
            if (inserted.length == 1 && inserted[0] == '\n' && dstart == dend && !hardBreak) {
                enterOnEmpty(text, dstart)?.let { return it }
            }
            return null
        }
    }

    private fun rawRule(text: Editable, inserted: CharSequence, dstart: Int, dend: Int): CharSequence? {
        val unchanged = text.subSequence(dstart, dend)
        for (span in text.getSpans(dstart, dend, EditorSpan::class.java)) {
            if (span !is RawInlineSpan && span !is RawBlockSpan) continue
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            val covers = dstart <= s && dend >= e
            val inside = (dstart in (s + 1) until e) || (dend in (s + 1) until e)
            if (span is RawBlockSpan && inserted.isNotEmpty() && dstart == dend) {
                // Typing beside a raw block starts a line of its own.
                if (dstart == e && !inserted.startsWith("\n")) return "\n" + inserted
                if (dstart == s && !inserted.endsWith("\n")) return inserted.toString() + "\n"
            }
            if (inside && !covers && (inserted.isNotEmpty() || span is RawBlockSpan)) return unchanged
        }
        return null
    }

    private fun separatorDeletion(text: Editable, at: Int): CharSequence? {
        val edits = BlockEdits(text, editorStyle)
        val k = edits.blocks.indexOfFirst { it.start == at + 1 }
        if (k <= 0) return null
        // A raw block never merges with the text beside it.
        if (edits.leaf(k) is Leaf.Raw || edits.leaf(k - 1) is Leaf.Raw) return "\n"
        val backspace = Selection.getSelectionStart(text) == at + 1 && Selection.getSelectionEnd(text) == at + 1
        if (!backspace || !(edits.isItemStart(k) || edits.isQuoteStart(k))) return null
        recordCommand()
        edits.outdent(k)
        edits.commit()
        mainHandler.post(::afterStructureChange)
        return "\n"
    }

    private fun enterOnEmpty(text: Editable, at: Int): CharSequence? {
        val edits = BlockEdits(text, editorStyle)
        val k = edits.indexAt(at)
        val block = edits.blocks.getOrNull(k) ?: return null
        if (k == 0 || block.start != block.contentEnd || at != block.start) return null
        if (!edits.isItemStart(k) && edits.path(k).lastOrNull() !is Container.Quote) return null
        recordCommand()
        edits.outdent(k)
        edits.commit()
        mainHandler.post(::afterStructureChange)
        return ""
    }

    // Not View.post: that waits for the view to be attached, so a detached editor (tests) would skip the pass.
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun recordCommand() {
        undoStack.record(UndoStack.Kind.Command, SystemClock.uptimeMillis(), ::currentState)
        recorded = true
    }

    private fun afterStructureChange() {
        recorded = false
        val t = text ?: return
        guard++
        try {
            normalizer.normalize(t, composingStart(t), composingEnd(t))
        } finally {
            guard--
        }
        changed()
    }

    // --- keys, menus, IME ------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_TAB -> {
                if (event.isShiftPressed) outdent() else indent()
                return true
            }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (event.isShiftPressed) {
                insertHardBreak()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_B -> toggleBold()

                KeyEvent.KEYCODE_I -> toggleItalic()

                KeyEvent.KEYCODE_Z -> if (event.isShiftPressed) redo() else undo()

                KeyEvent.KEYCODE_Y -> redo()

                KeyEvent.KEYCODE_F -> onFindRequest?.invoke() ?: return super.onKeyShortcut(keyCode, event)

                KeyEvent.KEYCODE_X -> if (event.isShiftPressed) {
                    toggleStrike()
                } else {
                    return super.onKeyShortcut(
                        keyCode,
                        event
                    )
                }

                else -> return super.onKeyShortcut(keyCode, event)
            }
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.undo -> {
            undo()
            true
        }

        android.R.id.redo -> {
            redo()
            true
        }

        // Pasted text is text: Markdown or HTML from the clipboard isn't parsed into formatting.
        android.R.id.paste -> super.onTextContextMenuItem(android.R.id.pasteAsPlainText)

        else -> super.onTextContextMenuItem(id)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            // The title capitalises each word, the body each sentence.
            override fun getCursorCapsMode(reqModes: Int): Int {
                val t = text ?: return super.getCursorCapsMode(reqModes)
                val title = Blocks.of(t).firstOrNull() ?: return super.getCursorCapsMode(reqModes)
                val at = selectionStart
                return if (at <= title.contentEnd) {
                    TextUtils.getCapsMode(t, at, TextUtils.CAP_MODE_WORDS)
                } else {
                    super.getCursorCapsMode(reqModes)
                }
            }
        }
    }

    // --- touch ---------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                boxDown = taskBoxAt(event.x, event.y)
                if (boxDown >= 0) return true
            }

            MotionEvent.ACTION_UP -> {
                val down = boxDown
                boxDown = -1
                if (down >= 0) {
                    if (taskBoxAt(event.x, event.y) == down) {
                        performClick()
                        toggleTaskAt(down)
                    }
                    return true
                }
                val handled = super.onTouchEvent(event)
                if (isTap(event)) reportLinkTap()
                return handled
            }

            MotionEvent.ACTION_CANCEL -> boxDown = -1
        }
        if (boxDown >= 0) return true
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun isTap(event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        return abs(event.x - downX) < slop && abs(event.y - downY) < slop &&
            event.eventTime - downAt < ViewConfiguration.getLongPressTimeout()
    }

    private fun reportLinkTap() {
        val t = text ?: return
        if (selectionStart != selectionEnd) return
        val link = Marks.linkAt(t, selectionStart) ?: return
        onLinkTap?.invoke(LinkTap(link.href, link.title, t.getSpanStart(link), t.getSpanEnd(link)))
    }

    /** The start offset of the task item whose box is under (x, y), or -1. Targets are at least 48dp. */
    private fun taskBoxAt(x: Float, y: Float): Int {
        val layout = layout ?: return -1
        val t = text ?: return -1
        val lx = x - totalPaddingLeft + scrollX
        val ly = y - totalPaddingTop + scrollY
        val line = layout.getLineForVertical(ly.toInt())
        val offset = layout.getLineStart(line)
        val block = Blocks.at(t, offset) ?: return -1
        if (block.start != offset) return -1
        val (cx, cy) = block.span.boxCenter(layout, line) ?: return -1
        val reach = editorStyle.dp(24f)
        return if (abs(lx - cx) <= reach && abs(ly - cy) <= reach) block.start else -1
    }

    /** Checks or unchecks the task item at [offset]. */
    internal fun toggleTaskAt(offset: Int) = command { text ->
        val edits = BlockEdits(text, editorStyle)
        edits.toggleChecked(edits.indexAt(offset))
        edits.commit()
    }
}
