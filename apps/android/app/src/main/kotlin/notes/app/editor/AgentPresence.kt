package notes.app.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.text.NoCopySpan
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.text.style.UpdateLayout
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation

/**
 * The agent at work in the note (docs/contributing/agent-clients.md: What the user sees): a marker under the line
 * where a run is likely to write while it goes, then what its change put in, highlighted and fading. Drawn, never
 * stored: its spans aren't [EditorSpan]s, so saves, undo and the codec never see them.
 */
internal class AgentPresence(private val view: NoteEditText, private val style: EditorStyle) {
    /** Where the marker sits; edits carry it along. */
    private class Anchor : NoCopySpan

    /** Room under the anchor's line for the marker, laid out again as it comes and goes. */
    private inner class Room(private val anchor: Anchor) :
        LineHeightSpan,
        UpdateLayout,
        NoCopySpan {
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            lineHeight: Int,
            fm: Paint.FontMetricsInt
        ) {
            val at = (text as? Spanned)?.getSpanStart(anchor) ?: return
            if (at in start until end || (at == end && end == text.length)) {
                fm.descent += room
                fm.bottom += room
            }
        }
    }

    /** What the agent's last change put in. */
    private class Landed : NoCopySpan

    private var label: String? = null
    private var anchor: Anchor? = null
    private var roomSpan: Room? = null
    private var landed: Landed? = null
    private var landedAt = 0L
    private var still = false

    /** Whether the agent's last change is still the latest thing that happened: Undo would take it back. */
    var hasLanded = false
        private set

    /** Told as [hasLanded] changes. */
    var onLanded: ((Boolean) -> Unit)? = null

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also { it.textSize = style.bodySize * 12.5f / 17f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val pad = style.dp(8f)
    private val dot = style.dp(3.5f)
    private val pillHeight = labelPaint.fontSpacing * 1.3f
    private val room = (pillHeight + style.dp(10f)).toInt()
    private val clear = Runnable { endLanded() }

    /** Shows the marker naming [label] (null: none) where a run will likely write. */
    fun show(label: String?) {
        if (label == this.label) return
        this.label = label
        if (label == null) unmark() else mark()
        view.invalidate()
    }

    /** After a change to the whole text (an undo), or one that took the marker's line away. */
    fun textReplaced() {
        if (label == null) return
        unmark()
        mark()
    }

    /** Put back if a change took it away with the text it was in. */
    fun keep() {
        val t = view.text ?: return
        if (label != null && anchor?.let { t.getSpanStart(it) } == -1) textReplaced()
    }

    private fun mark() {
        val t = view.text ?: return
        val at = likelySpot(t, view.selectionStart, view.selectionEnd)
        val a = Anchor()
        t.setSpan(a, at, at, Spanned.SPAN_POINT_POINT)
        val from = t.lastIndexOf('\n', at - 1) + 1
        val to = t.indexOf('\n', at).let { if (it < 0) t.length else it + 1 }
        val r = Room(a)
        t.setSpan(r, from, to, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        anchor = a
        roomSpan = r
    }

    private fun unmark() {
        val t = view.text ?: return
        anchor?.let(t::removeSpan)
        roomSpan?.let(t::removeSpan)
        anchor = null
        roomSpan = null
    }

    /** The agent's change made [before] into the text now: the part that differs is what it put in. */
    fun landed(before: String) {
        val t = view.text ?: return
        val after = t.toString()
        var p = 0
        val max = minOf(before.length, after.length)
        while (p < max && before[p] == after[p]) p++
        var s = 0
        while (s < max - p && before[before.length - 1 - s] == after[after.length - 1 - s]) s++
        landed?.let(t::removeSpan)
        // A deletion has nothing to highlight, but can still be undone.
        landed = if (after.length - s > p) Landed().also { t.setSpan(it, p, after.length - s, SPAN) } else null
        landedAt = SystemClock.uptimeMillis()
        still =
            Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        view.removeCallbacks(clear)
        view.postDelayed(clear, LANDED_MS)
        // The change ends the wait.
        show(null)
        if (!hasLanded) {
            hasLanded = true
            onLanded?.invoke(true)
        }
        view.invalidate()
    }

    /** The user changed the text: the change is theirs to keep now, and Undo would take back their typing first. */
    fun userChanged() = endLanded()

    private fun endLanded() {
        view.removeCallbacks(clear)
        landed?.let { view.text?.removeSpan(it) }
        landed = null
        if (!hasLanded) return
        hasLanded = false
        onLanded?.invoke(false)
        view.invalidate()
    }

    /** The highlight, under the text. */
    fun drawUnder(canvas: Canvas, layout: Layout, pill: (Canvas, Layout, Spanned, Int, Int, Paint) -> Unit) {
        val t = view.text ?: return
        val span = landed ?: return
        val start = t.getSpanStart(span)
        val end = t.getSpanEnd(span)
        if (start < 0 || end <= start) return
        val level = highlightLevel()
        if (level <= 0f) return
        fill.color = ColorUtils.setAlphaComponent(style.accent, (HIGHLIGHT_ALPHA * level).toInt())
        canvas.withTranslation(view.totalPaddingLeft.toFloat(), view.totalPaddingTop.toFloat()) {
            pill(this, layout, t, start, end, fill)
        }
        if (!still) view.postInvalidateOnAnimation()
    }

    /** The marker, in the room under its line. */
    fun drawOver(canvas: Canvas, layout: Layout) {
        val t = view.text ?: return
        val text = label ?: return
        val at = anchor?.let { t.getSpanStart(it) }?.takeIf { it >= 0 } ?: return
        val line = layout.getLineForOffset(at)
        val width = labelPaint.measureText(text) + dot * 2 + pad * 2.5f
        val maxX = (view.width - view.totalPaddingLeft - view.totalPaddingRight).toFloat() - width
        val x = layout.getPrimaryHorizontal(at).coerceAtMost(maxX).coerceAtLeast(0f)
        val top = layout.getLineBottom(line) - room + style.dp(4f)
        labelPaint.color = style.ink2
        canvas.withTranslation(view.totalPaddingLeft.toFloat(), view.totalPaddingTop.toFloat()) {
            rect.set(x, top, x + width, top + pillHeight)
            fill.color = ColorUtils.setAlphaComponent(style.accent, HIGHLIGHT_ALPHA)
            drawRoundRect(rect, pillHeight / 2, pillHeight / 2, fill)
            fill.color = style.accent
            drawCircle(x + pad + dot, rect.centerY(), dot, fill)
            val baseline = rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2
            drawText(text, x + pad + dot * 2 + pad / 2, baseline, labelPaint)
        }
    }

    /** How strongly the change shows: from 1 as it lands to 0, easing out; held at 1 with animations off. */
    internal fun highlightLevel(): Float {
        if (landed == null) return 0f
        if (still) return 1f
        val left = 1f - ((SystemClock.uptimeMillis() - landedAt) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        return left * left
    }

    /** For tests: the marker's label and offset, and what's highlighted. */
    internal val marker: Pair<String, Int>? get() = label?.let { l ->
        anchor?.let { l to view.text!!.getSpanStart(it) }
    }
    internal val highlighted: IntRange? get() = landed?.let {
        view.text!!.getSpanStart(it) until
            view.text!!.getSpanEnd(it)
    }

    companion object {
        /** How long the change is highlighted; and how long its Undo is offered. */
        const val FADE_MS = 3_000L
        const val LANDED_MS = 6_000L
        private const val HIGHLIGHT_ALPHA = 56
        private const val SPAN = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

        /** Where a run on the note will most likely write: the caret, or the top of the body when it's in the title. */
        fun likelySpot(t: CharSequence, selStart: Int, selEnd: Int): Int {
            val titleEnd = t.indexOf('\n').let { if (it < 0) t.length else it }
            if (selStart == selEnd && selStart > titleEnd) return selStart.coerceAtMost(t.length)
            return minOf(titleEnd + 1, t.length)
        }
    }
}
