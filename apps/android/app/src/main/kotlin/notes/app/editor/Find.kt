package notes.app.editor

import android.text.NoCopySpan
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ReplacementSpan
import android.text.style.UpdateAppearance

/**
 * A find match, drawn by [NoteEditText] as a pill like a tag's. Not an
 * [EditorSpan], so the codec, normaliser and undo stack never see it, and a
 * [NoCopySpan], so copied text doesn't carry it to the clipboard.
 */
class FindSpan(val current: Boolean, private val ink: Int) :
    CharacterStyle(),
    UpdateAppearance,
    NoCopySpan {
    override fun updateDrawState(tp: TextPaint) {
        // The current match sits on solid accent: the ink keeps it readable in both themes.
        if (current) tp.color = ink
    }
}

/** What the find bar shows: [count] matches, the current one at [index] (-1 for none). */
data class FindState(
    val count: Int = 0,
    val index: Int = -1,
    /** Replace all just replaced this many; shown until the next search. */
    val replaced: Int? = null
)

/** A match's range in the text: [start, end). */
data class FindMatch(val start: Int, val end: Int)

object Find {
    /**
     * Where [query] occurs in [text], ignoring case, in order and not
     * overlapping. Pictures are drawn over their source: nothing matches inside one.
     */
    fun matches(text: Spanned, query: String): List<FindMatch> {
        if (query.isEmpty() || query.length > text.length) return emptyList()
        // Folded a char at a time so offsets stay the text's (lowercasing a String can change its length).
        val hay = fold(text)
        val needle = fold(query)
        val pictures = text.getSpans(0, text.length, ReplacementSpan::class.java)
            .map { text.getSpanStart(it) to text.getSpanEnd(it) }
        val out = ArrayList<FindMatch>()
        var from = 0
        while (true) {
            val at = hay.indexOf(needle, from)
            if (at < 0) break
            val end = at + needle.length
            if (pictures.none { (s, e) -> at < e && end > s }) {
                out += FindMatch(at, end)
                from = end
            } else {
                from = at + 1
            }
        }
        return out
    }

    /** Raw text (code, unknown Markdown) is read-only in the editor, so replace leaves it alone. */
    fun readOnly(text: Spanned, match: FindMatch): Boolean =
        text.getSpans(match.start, match.end, EditorSpan::class.java).any {
            (it is RawInlineSpan || it is RawBlockSpan) &&
                text.getSpanStart(it) < match.end && text.getSpanEnd(it) > match.start
        }

    private fun fold(s: CharSequence) = String(CharArray(s.length) { s[it].uppercaseChar().lowercaseChar() })
}
