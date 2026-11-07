package notes.app.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UpdateAppearance
import android.view.View
import kotlin.math.roundToInt
import notes.core.doc.Container
import notes.core.doc.Leaf
import notes.core.markdown.Fence

/** Spans the editor owns. The codec reads only these; anything else in the text is ignored. */
interface EditorSpan

/** What the margin of an item's first line shows. */
sealed interface Marker {
    data class Bullet(val level: Int) : Marker

    data class Number(val n: Int) : Marker

    data class Box(val checked: Boolean) : Marker
}

/**
 * The one span every block (paragraph, heading, title, raw block) carries,
 * covering its text and the "\n" that ends it. Holds the block's container path
 * and type, and draws list markers and quote bars in its leading margin.
 * [marker] is derived by the normaliser (numbers are assigned there, not at
 * draw time).
 */
class BlockSpan(val path: List<Container>, val leaf: Leaf, val marker: Marker?, private val style: EditorStyle) :
    LeadingMarginSpan,
    LineBackgroundSpan,
    EditorSpan {
    fun sameAs(path: List<Container>, leaf: Leaf, marker: Marker?) =
        this.path == path && this.leaf == leaf && this.marker == marker

    private val fence by lazy { (leaf as? Leaf.Raw)?.let { Fence.parse(it.source) } }

    override fun getLeadingMargin(first: Boolean): Int = (margin() + if (leaf is Leaf.Raw) style.rawPad else 0f).toInt()

    fun margin(): Float = path.sumOf {
        (if (it is Container.Quote) style.quoteIndent else style.itemIndent).toDouble()
    }.toFloat()

    /** Where the innermost item's marker column starts, from the margin edge. */
    private fun markerOffset(): Float = margin() - style.itemIndent

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout?
    ) {
        val savedColor = p.color
        val savedStyle = p.style
        var offset = 0f
        for (container in path) {
            if (container is Container.Quote) {
                p.color = style.ink3
                p.style = Paint.Style.FILL
                val left = if (dir > 0) x + offset else x - offset - style.quoteBar
                c.drawRect(left, top.toFloat(), left + style.quoteBar, bottom.toFloat(), p)
                offset += style.quoteIndent
            } else {
                offset += style.itemIndent
            }
        }
        val spanned = text as? Spanned
        if (marker != null && first && spanned != null && spanned.getSpanStart(this) == start) {
            drawMarker(c, p, x, dir, baseline)
        }
        p.color = savedColor
        p.style = savedStyle
    }

    private fun drawMarker(c: Canvas, p: Paint, x: Int, dir: Int, baseline: Int) {
        val column = markerOffset()
        val center = markerCenterX(x.toFloat(), dir, column)
        val fm = p.fontMetrics
        val midY = baseline + (fm.ascent + fm.descent) / 2f
        when (val m = marker) {
            is Marker.Bullet -> {
                p.color = style.ink2
                p.style = if (m.level % 2 == 1) Paint.Style.STROKE else Paint.Style.FILL
                p.strokeWidth = style.dp(1.2f)
                c.drawCircle(center, midY, style.dp(2.6f), p)
            }

            is Marker.Number -> {
                p.color = style.ink2
                p.style = Paint.Style.FILL
                val label = "${m.n}."
                val width = p.measureText(label)
                // Right-aligned against the text, like a list's number column.
                val gap = style.dp(7f)
                val left = if (dir >
                    0
                ) {
                    x + column + style.itemIndent - gap - width
                } else {
                    x - column - style.itemIndent + gap
                }
                c.drawText(label, left, baseline.toFloat(), p)
            }

            is Marker.Box -> drawBox(c, p, center, midY, m.checked)

            null -> Unit
        }
    }

    private fun drawBox(c: Canvas, p: Paint, cx: Float, cy: Float, checked: Boolean) {
        val r = style.boxSize / 2f
        p.style = Paint.Style.FILL
        if (checked) {
            p.color = style.accent
            c.drawCircle(cx, cy, r, p)
            p.color = style.onAccent
            c.drawCircle(cx, cy, r * 0.45f, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = style.dp(1.5f)
        p.color = if (checked) style.accentDeep else style.ink3
        c.drawCircle(cx, cy, r - p.strokeWidth / 2f, p)
    }

    private fun markerCenterX(x: Float, dir: Int, column: Float): Float {
        val inColumn = style.itemIndent * 0.42f
        return if (dir > 0) x + column + inColumn else x - column - inColumn
    }

    /** The task box's centre in layout coordinates, for hit testing taps. */
    fun boxCenter(layout: Layout, line: Int): Pair<Float, Float>? {
        if (marker !is Marker.Box) return null
        val dir = layout.getParagraphDirection(line)
        val x = if (dir > 0) 0f else layout.width.toFloat()
        val baseline = layout.getLineBaseline(line)
        val fm = layout.paint.fontMetrics
        return markerCenterX(x, dir, markerOffset()) to baseline + (fm.ascent + fm.descent) / 2f
    }

    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int
    ) {
        if (leaf !is Leaf.Raw) return
        val saved = paint.color
        paint.color = if (fence != null) style.code else style.hover
        val margin = margin()
        val first = (text as? Spanned)?.getSpanStart(this) == start
        val boxTop = top + if (first) style.rawGap else 0f
        canvas.drawRect(left + margin, boxTop, right.toFloat(), bottom.toFloat(), paint)
        paint.color = saved
        // The language label sits in the room LeafStyleSpan leaves above the first line.
        val lang = fence?.lang.orEmpty()
        if (lang.isEmpty() || !first) return
        val label = TextPaint(paint)
        label.color = style.codeDim
        label.typeface = style.typeface
        label.textSize = paint.textSize * style.codeLabelScale
        label.letterSpacing = 0.02f
        canvas.drawText(lang, left + margin + style.rawPad, boxTop + style.rawGap - label.ascent(), label)
    }
}

/**
 * Type size, face and colour for a block, plus the space around headings.
 * [quoted] text inside a quote is italic serif, as on the web.
 */
class LeafStyleSpan(val leaf: Leaf, val dim: Boolean, val quoted: Boolean, private val style: EditorStyle) :
    MetricAffectingSpan(),
    LineHeightSpan,
    EditorSpan {
    fun sameAs(leaf: Leaf, dim: Boolean, quoted: Boolean) =
        this.leaf == leaf && this.dim == dim && this.quoted == quoted

    private val fence by lazy { (leaf as? Leaf.Raw)?.let { Fence.parse(it.source) } }

    override fun updateMeasureState(tp: TextPaint) = apply(tp)

    override fun updateDrawState(tp: TextPaint) {
        apply(tp)
        when {
            // A fence's colours come from the codec's spans (SpanCodec.codeSpans).
            leaf is Leaf.Raw -> if (fence == null) tp.color = style.ink2

            dim -> tp.color = style.ink3

            quoted && leaf == Leaf.Paragraph -> tp.color = style.ink2
        }
    }

    private fun apply(tp: TextPaint) {
        when (leaf) {
            Leaf.Title -> {
                tp.textSize *= style.titleScale
                tp.typeface = style.serif
                tp.letterSpacing = -0.015f
            }

            is Leaf.Heading -> {
                tp.textSize *= style.headingScale[leaf.level - 1]
                tp.typeface = if (leaf.level < 3) style.serif else style.semibold
            }

            is Leaf.Raw -> {
                tp.textSize *= style.rawScale
                tp.typeface = style.monospace
            }

            Leaf.Paragraph -> if (quoted) {
                tp.textSize *= style.quoteScale
                tp.typeface = style.serifItalic
            }
        }
    }

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt
    ) {
        val spanned = text as? Spanned ?: return
        val height = fm.descent - fm.ascent
        val above = when (leaf) {
            is Leaf.Heading -> height * 0.3f

            // Room for the language label BlockSpan draws.
            is Leaf.Raw -> style.rawGap + height * if (fence?.lang.isNullOrEmpty()) 0.3f else 1.1f

            else -> 0f
        }.toInt()
        val gap = if (leaf is Leaf.Heading) style.dp(6f).toInt() else 0
        val below = when (leaf) {
            Leaf.Title -> height * 0.05f
            is Leaf.Raw -> height * 0.3f
            else -> 0f
        }.toInt()
        if (above + gap > 0 && start == spanned.getSpanStart(this)) {
            fm.ascent -= above + gap
            fm.top -= above + gap
            // Layout spaces lines by their height; keep the raw block's room out of that.
            if (leaf is Leaf.Raw) fm.descent -= (above * (style.lineSpacing - 1) / style.lineSpacing).toInt()
        }
        // Headings space their lines as on the web (line-height 1.2, then 0.2em
        // before the next block), not by the view's line spacing, which would put
        // half a large line under each. Layout adds that spacing below the descent,
        // so solve for a descent that lands the next line where the heading wants
        // it. The text's last line gets no spacing and has no next line.
        if (leaf is Leaf.Heading && end < text.length) {
            val want = fm.descent + (height * if (end >= spanned.getSpanEnd(this)) 0.27f else 0.1f).toInt()
            val m = style.lineSpacing
            fm.descent = ((want + fm.ascent * (m - 1)) / m).roundToInt()
        }
        if (below > 0 && end >= spanned.getSpanEnd(this)) {
            fm.descent += below
            fm.bottom += below
        }
    }
}

class BoldSpan :
    StyleSpan(Typeface.BOLD),
    EditorSpan

class ItalicSpan :
    StyleSpan(Typeface.ITALIC),
    EditorSpan

class StrikeSpan :
    StrikethroughSpan(),
    EditorSpan

/** A link. Taps are handled by the editor (open / edit / remove), never by the span. */
class LinkSpan(val href: String, val title: String?, private val style: EditorStyle) :
    URLSpan(href),
    EditorSpan {
    override fun onClick(widget: View) = Unit

    override fun updateDrawState(ds: TextPaint) {
        ds.color = style.link
        ds.isUnderlineText = true
    }
}

/**
 * A #tag (architecture §7.5): tinted, still plain editable text. Not an
 * [EditorSpan]: the [Normalizer] re-derives it after every change and it never
 * reaches the document. [NoteEditText] draws the tint as a pill; a span's
 * bgColor would fill the whole line height, line spacing included.
 */
class TagSpan(val name: String) :
    CharacterStyle(),
    UpdateAppearance {
    // Still an UpdateAppearance so adding or moving one redraws the text.
    override fun updateDrawState(tp: TextPaint) = Unit
}

/** Marks a "\n" that is a line break inside a paragraph, not the end of a block. */
class HardBreakSpan : EditorSpan

/** An inline run outside the dialect (code span, image): shown as written, not editable. */
class RawInlineSpan(val source: String, private val style: EditorStyle) :
    MetricAffectingSpan(),
    EditorSpan {
    override fun updateMeasureState(tp: TextPaint) {
        tp.typeface = style.monospace
        tp.textSize *= style.rawScale
    }

    override fun updateDrawState(tp: TextPaint) {
        updateMeasureState(tp)
        tp.color = style.ink2
        tp.bgColor = style.hover
    }
}

/**
 * An invisible character at the very end of the text while the last block is
 * an empty item, quote or heading. Android's Layout gives an empty last line
 * no paragraph spans (so no marker or indent), and hands a zero-length span at
 * the end to the line above instead; one character gives the block a real
 * range. It isn't content: block ranges end before it and the cursor never
 * goes past it.
 */
class EndMarkerSpan : EditorSpan

const val END_MARKER = '\u200B'

/** Where the end marker sits, or -1. */
fun endMarkerStart(text: Spanned): Int {
    val span = text.getSpans(0, text.length, EndMarkerSpan::class.java).firstOrNull() ?: return -1
    val s = text.getSpanStart(span)
    return if (s == text.length - 1 && text.getSpanEnd(span) == text.length && text[s] == END_MARKER) s else -1
}

/**
 * Covers a raw block's text exactly, so edits inside it can be refused. The
 * text is [shown]: the source, or a code fence's code without its fence lines.
 */
class RawBlockSpan(val source: String) : EditorSpan {
    // A space for an empty fence: a span over no text would be dropped.
    val shown: String = Fence.parse(source)?.code?.ifEmpty { " " } ?: source
}
