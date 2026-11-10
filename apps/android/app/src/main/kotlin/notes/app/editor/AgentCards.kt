package notes.app.editor

import android.text.NoCopySpan
import android.text.Spannable
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import notes.core.agent.Overlay
import notes.core.agent.Quotes
import notes.core.agent.madeTo
import notes.core.doc.Leaf

/**
 * What runs show beside the note (docs/contributing/agent-clients.md: Overlays): cards between its blocks, never
 * in its text. Each is anchored where its place says, follows the text around it as the user types, and has room
 * made for it under its block ([EditorStyle.roomBelow]); the card views sit in [attach]'s layer over the editor.
 */
internal class AgentCards(private val view: NoteEditText, private val style: EditorStyle) {
    /** Where a card placed by the cursor or a quote is; edits carry it along, keeping it in its block. */
    private class Anchor : NoCopySpan

    private class Shown(val overlay: Overlay, var anchor: Anchor?, val card: AgentCardView) {
        var height = 0
        var width = 0
    }

    private var layer: FrameLayout? = null
    private var shown: List<Shown> = emptyList()
    private var label = ""

    /** The user answered a card: `dismiss`, `accept` or `reject`. */
    var onAnswer: ((Overlay, String) -> Unit)? = null

    /** The layer the card views go in: over the editor, the same size, scrolling with it. */
    fun attach(layer: FrameLayout?) {
        this.layer?.removeAllViews()
        this.layer = layer
        for (s in shown) add(s)
    }

    /** Shows [list], all of it: any card not in it goes. Those already shown stay where they're anchored. */
    fun show(list: List<Overlay>, label: String) {
        val t = view.text ?: return
        val relabel = label != this.label
        this.label = label
        val was = shown.associateBy { it.overlay.id }
        val next = list.map { o ->
            val old = was[o.id]
            if (old != null && !relabel && old.overlay == o) return@map old
            // Its anchor carries over: it was shown, and stays where it was.
            old?.let { (it.card.parent as? ViewGroup)?.removeView(it.card) }
            Shown(o, old?.anchor ?: anchorFor(t, o), card(o)).also(::add)
        }
        for (s in shown) if (next.none { it === s || (it.anchor === s.anchor && s.anchor != null) }) remove(s)
        shown = next
        view.reflow()
    }

    /** The theme changed: the cards are made again in its colours, where they are. */
    fun restyle() {
        shown = shown.map { s ->
            (s.card.parent as? ViewGroup)?.removeView(s.card)
            Shown(s.overlay, s.anchor, card(s.overlay)).also(::add)
        }
        view.reflow()
    }

    private fun card(o: Overlay) = AgentCardView(view.context, style, o, label) { answer ->
        onAnswer?.invoke(o, answer)
    }

    private fun add(s: Shown) {
        val layer = layer ?: return
        (s.card.parent as? ViewGroup)?.removeView(s.card)
        layer.addView(
            s.card,
            FrameLayout.LayoutParams(
                s.width.takeIf {
                    it > 0
                } ?: 0,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun remove(s: Shown) {
        (s.card.parent as? ViewGroup)?.removeView(s.card)
        s.anchor?.let { view.text?.removeSpan(it) }
    }

    /** Where a card newly shown is anchored; none for the top or the end, or when what places it isn't there. */
    private fun anchorFor(t: Spanned, o: Overlay): Anchor? {
        val place = o.place
        val at = when {
            place.after.isNotEmpty() -> Quotes.find(view.document(), place.after)?.let { i ->
                quotable(t).getOrNull(i)?.start
            }

            place.at == "cursor" -> view.selectionEnd.takeIf { it >= 0 && inQuotable(t, it) }

            else -> null
        } ?: return null
        return Anchor().also { (t as? Spannable)?.setSpan(it, at, at, Spanned.SPAN_MARK_MARK) }
    }

    /** The blocks a quote can be in, as [Quotes.blocks] counts them: not the title, nor raw blocks. */
    private fun quotable(t: Spanned) = Blocks.of(t).filter { it.span.leaf != Leaf.Title && it.span.leaf !is Leaf.Raw }

    private fun inQuotable(t: Spanned, at: Int): Boolean {
        val b = Blocks.at(t, at) ?: return false
        return b.span.leaf != Leaf.Title && b.span.leaf !is Leaf.Raw
    }

    /** Put back, by its place, any anchor a change took away with the text it was in. */
    fun keep() {
        val t = view.text ?: return
        var moved = false
        for (s in shown) {
            val a = s.anchor ?: continue
            if (t.getSpanStart(a) >= 0) continue
            s.anchor = anchorFor(t, s.overlay)
            moved = true
        }
        if (moved) view.reflow()
    }

    /** Before the whole text is replaced (an undo): where each anchor is, to map through the change. */
    fun offsets(): List<Int> {
        val t = view.text ?: return emptyList()
        return shown.map { s -> s.anchor?.let { t.getSpanStart(it) } ?: -1 }
    }

    /** After the whole text went from [was] to what it is: each anchor moved as the text around it did. */
    fun remap(was: String, at: List<Int>) {
        val t = view.text ?: return
        val now = t.toString()
        var p = 0
        val max = minOf(was.length, now.length)
        while (p < max && was[p] == now[p]) p++
        var s = 0
        while (s < max - p && was[was.length - 1 - s] == now[now.length - 1 - s]) s++
        for ((i, shownCard) in shown.withIndex()) {
            val a = shownCard.anchor ?: continue
            val old = at.getOrNull(i)?.takeIf { it >= 0 } ?: continue
            val new = when {
                old <= p -> old
                old >= was.length - s -> old + now.length - was.length
                else -> p
            }.coerceIn(0, t.length)
            t.setSpan(a, new, new, Spanned.SPAN_MARK_MARK)
        }
        view.reflow()
    }

    /** The text loaded again (a note opening): anchored afresh. */
    fun reanchor() {
        val t = view.text ?: return
        for (s in shown) s.anchor = anchorFor(t, s.overlay)
        view.reflow()
    }

    /**
     * The offset whose line a card goes under: the end of its anchor's block, the end of the title for the top, or
     * the end of the text.
     */
    private fun spotOf(t: Spanned, s: Shown): Int {
        val at = s.anchor?.let { t.getSpanStart(it) } ?: -1
        if (at >= 0) {
            val block = t.getSpans(at, at, BlockSpan::class.java).firstOrNull { b ->
                val start = t.getSpanStart(b)
                val end = t.getSpanEnd(b)
                start <= at && (at < end || end == t.length)
            }
            if (block != null && block.leaf != Leaf.Title) {
                val end = t.getSpanEnd(block)
                return if (end > 0 && end <= t.length && t[end - 1] == '\n') end - 1 else end
            }
        } else if (s.overlay.place.after.isEmpty() && s.overlay.place.at == "end") {
            return t.length
        }
        return t.indexOf('\n').let { if (it < 0) t.length else it }
    }

    private fun inLine(spot: Int, start: Int, end: Int, length: Int) =
        spot in start until end || (spot == end && end == length)

    /** Room under a line for the cards that go there ([EditorStyle.roomBelow]). */
    fun roomBelow(text: Spanned, start: Int, end: Int): Int {
        var room = 0
        for (s in shown) if (inLine(spotOf(text, s), start, end, text.length)) room += s.height + gaps
        return room
    }

    private companion object {
        const val EXCERPT = 40
    }

    private val gapAbove = style.dp(6f).toInt()
    private val gapBelow = style.dp(10f).toInt()
    private val gaps = gapAbove + gapBelow

    /** Measures and positions the card views over the room made for them: on every layout pass. */
    fun place() {
        if (shown.isEmpty()) return
        val layout = view.layout ?: return
        val t = view.text ?: return
        val width = view.width - view.totalPaddingLeft - view.totalPaddingRight
        if (width <= 0) return
        var resized = false
        for (s in shown) {
            if (s.width != width) {
                s.width = width
                (s.card.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.width = width
                    it.leftMargin = view.totalPaddingLeft
                    s.card.layoutParams = it
                }
            }
            s.card.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            if (s.card.measuredHeight != s.height) {
                s.height = s.card.measuredHeight
                resized = true
            }
        }
        if (resized) {
            view.reflow()
            return
        }
        // In the order shown, stacked under their line.
        val bySpot = shown.groupBy { spotOf(t, it) }
        for ((spot, cards) in bySpot) {
            val line = layout.getLineForOffset(spot)
            var y = view.totalPaddingTop + layout.getLineBottom(line) - cards.sumOf { it.height + gaps }
            val where = whereIs(t, spot)
            for (s in cards) {
                y += gapAbove
                if (s.card.translationY != y.toFloat()) s.card.translationY = y.toFloat()
                y += s.height + gapBelow
                s.card.describe(where)
            }
        }
        readInOrder(bySpot.toSortedMap().values.flatten())
    }

    /** Where in the note a spot is, in words: TalkBack reads the note as one item, and the cards after it. */
    private fun whereIs(t: Spanned, spot: Int): String {
        if (spot >= t.length) return "at the end"
        val block = Blocks.at(t, spot)
        if (block == null || block.span.leaf == Leaf.Title) return "at the top"
        val text = t.substring(block.start, block.contentEnd).trim()
        if (text.isEmpty()) return "under an empty line"
        val short = if (text.length > EXCERPT) text.take(EXCERPT).trimEnd() + "…" else text
        return "under “$short”"
    }

    /** The card views in the layer in the order they show, top to bottom: the order TalkBack reads them in. */
    private fun readInOrder(order: List<Shown>) {
        val layer = layer ?: return
        if ((0 until layer.childCount).map(layer::getChildAt) == order.map { it.card }) return
        for (s in order) {
            layer.removeView(s.card)
            layer.addView(s.card)
        }
    }

    /**
     * Whether a card placed by a quote still has it: the block it's anchored in holds it as it did. Text the user
     * changed there, or typed elsewhere, doesn't move it.
     */
    fun stillQuoted(id: String): Boolean {
        val s = shown.firstOrNull { it.overlay.id == id } ?: return false
        val quote = s.overlay.place.after
        if (quote.isEmpty()) return true
        val t = view.text ?: return false
        val at = s.anchor?.let { t.getSpanStart(it) }?.takeIf { it >= 0 } ?: return false
        val block = Blocks.at(t, at) ?: return false
        val i = quotable(t).indexOfFirst { it.start == block.start }
        if (i < 0) return false
        return Quotes.blocks(view.document()).getOrNull(i)?.let { Quotes.holds(it, quote) } ?: false
    }

    /** Where an insert at the cursor goes for a card: where it's anchored now, in code points into the content. */
    fun cursorFor(id: String): Int? {
        val t = view.text ?: return null
        val at = shown.firstOrNull { it.overlay.id == id }?.anchor?.let { t.getSpanStart(it) }?.takeIf { it >= 0 }
            ?: return null
        return AgentReading.read(t, at, at).cursor
    }

    /** Whether a suggestion could still be made where it shows: its quote there, and its change fitting the text. */
    fun canStillMake(o: Overlay): Boolean {
        val op = o.change ?: return false
        return stillQuoted(o.id) && madeTo(op, view.readForAgent().fields, cursorFor(o.id)) != null
    }

    /** For tests: each card's overlay, and the offset whose line it's under. */
    internal val placed: List<Pair<String, Int>>
        get() = view.text?.let { t -> shown.map { it.overlay.id to spotOf(t, it) } }.orEmpty()

    /** For tests: the card view showing an overlay. */
    internal fun cardFor(id: String): AgentCardView? = shown.firstOrNull { it.overlay.id == id }?.card
}
