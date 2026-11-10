package notes.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import notes.core.agent.AgentOp
import notes.core.agent.Overlay
import notes.core.doc.Document
import notes.core.markdown.Markdown

/**
 * One overlay as a card beside the note: who shows it, its answers, and a card's Markdown or a suggestion's reason
 * and change. Its buttons never take focus, so the caret and the keyboard stay in the note.
 */
@SuppressLint("ViewConstructor")
class AgentCardView internal constructor(
    context: Context,
    private val style: EditorStyle,
    val shown: Overlay,
    label: String,
    onAnswer: (String) -> Unit
) : LinearLayout(context) {
    /** What it is, for screen readers: "{label}: a note" or "…: a suggested change". */
    private val what = if (shown.kind == "card") "$label: a note" else "$label: a suggested change"
    private val who: TextView
    private val bodyStyle = EditorStyle(
        style.density,
        style.tokens,
        style.typeface,
        style.serif,
        style.monospace,
        bodySize = style.bodySize * 14f / 17f
    )

    init {
        orientation = VERTICAL
        val dp = style::dp
        setPadding(dp(12f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(10f).toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(10f)
            setColor(style.chip)
            setStroke(dp(1f).toInt().coerceAtLeast(1), style.hairStrong)
        }

        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(style.accent)
            }
        }
        head.addView(dot, LayoutParams(dp(7f).toInt(), dp(7f).toInt()).apply { marginEnd = dp(7f).toInt() })
        who = TextView(context).apply {
            text = label
            setTextColor(style.ink2)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyStyle.bodySize * 13f / 14f)
            typeface = style.typeface
            contentDescription = what
        }
        head.addView(who, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (shown.kind == "card") {
            head.addView(button("×", "Dismiss") { onAnswer("dismiss") })
        } else {
            head.addView(button("✓", "Accept the change") { onAnswer("accept") })
            head.addView(button("✗", "Reject the change") { onAnswer("reject") })
        }
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        if (shown.kind == "card") {
            addView(text(markdown(shown.body)).apply { links() })
        } else {
            if (shown.body.isNotBlank()) addView(text(shown.body))
            shown.change?.let { addView(text(change(it))) }
        }
    }

    private fun button(glyph: String, name: String, onClick: () -> Unit) = TextView(context).apply {
        text = glyph
        contentDescription = name
        gravity = Gravity.CENTER
        setTextColor(style.ink2)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, style.bodySize * 1.1f)
        minWidth = style.dp(48f).toInt()
        minHeight = style.dp(48f).toInt()
        isClickable = true
        // Tapping it leaves the caret and the keyboard where they are.
        isFocusable = false
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
        setOnClickListener { onClick() }
    }

    private fun text(content: CharSequence) = TextView(context).apply {
        text = content
        setTextColor(style.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyStyle.bodySize)
        typeface = style.typeface
        setLineSpacing(0f, 1.3f)
        setPadding(0, 0, style.dp(8f).toInt(), 0)
    }

    private fun TextView.links() {
        movementMethod = LinkMovementMethod.getInstance()
        // A link's movement method makes the text focusable and clickable: tapping it would take the note's focus,
        // and TalkBack would offer the whole text as a button. Its links still open, and TalkBack lists them.
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isLongClickable = false
    }

    /** A card's Markdown as the editor shows it, with links only where the editor allows them. */
    private fun markdown(body: String): CharSequence {
        val sb = SpanCodec.toSpannable(Document.forNote("", Markdown.parse(body)), bodyStyle)
        Normalizer(bodyStyle).normalize(sb)
        // The note's empty title, which a card hasn't.
        if (sb.isNotEmpty() && sb[0] == '\n') sb.delete(0, 1)
        for (span in sb.getSpans(0, sb.length, Any::class.java)) {
            if (sb.getSpanStart(span) == sb.getSpanEnd(span)) sb.removeSpan(span)
        }
        for (link in sb.getSpans(0, sb.length, LinkSpan::class.java)) {
            val start = sb.getSpanStart(link)
            val end = sb.getSpanEnd(link)
            sb.removeSpan(link)
            if (allowedLink(
                    link.href
                )
            ) {
                sb.setSpan(URLSpan(link.href.trim()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    /** A suggestion's change in words: what goes, struck through, then what comes. */
    private fun change(op: AgentOp): CharSequence {
        val sb = SpannableStringBuilder()
        fun gone(text: String) = sb.append(text, StrikethroughSpan(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE).also {
            sb.setSpan(
                ForegroundColorSpan(style.ink3),
                sb.length - text.length,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        fun comes(text: String) =
            sb.append(text, ForegroundColorSpan(style.accentDeep), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        when (op.kind) {
            "replace" -> {
                if (op.find.isNotEmpty()) gone(op.find)
                if (op.find.isNotEmpty() && op.replace.isNotEmpty()) sb.append(" → ")
                if (op.replace.isNotEmpty()) comes(op.replace)
            }

            "title" -> {
                sb.append("Title: ")
                comes(op.title)
            }

            else -> comes(op.text)
        }
        return sb
    }

    /** TalkBack reads the note as one item, so a card says where in it it is: [where], as "under “Bread”". */
    internal fun describe(where: String) {
        val said = "$what, $where"
        if (who.contentDescription != said) who.contentDescription = said
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Said as it appears, so a card that comes while someone listens isn't missed.
        ViewCompat.setAccessibilityPaneTitle(this, what)
    }

    // A tap on the card isn't one on the text under it.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    internal companion object {
        private val linkProtocols = Regex("^(https?:|mailto:)", RegexOption.IGNORE_CASE)

        fun allowedLink(href: String) = linkProtocols.containsMatchIn(href.trim())
    }
}
