package notes.app.editor

import android.text.SpannableStringBuilder
import android.text.Spanned
import notes.core.agent.Fields
import notes.core.doc.Leaf
import notes.core.markdown.Markdown

/** The note as an agent reads it: the text it would save, and the caret as code points into the content. */
data class AgentRead(val fields: Fields, val cursor: Int?)

object AgentReading {
    // A private-use character, which Markdown writes as itself: where it lands in the saved text is the caret.
    private const val MARK = ''

    /**
     * [text] as saved, and the caret: null with a selection, in the title, in a raw block (its source isn't
     * what's shown), or wherever the mark doesn't come through as itself. As the web's readForAgent; the cases
     * are shared/agent-fixtures/cursor.json.
     */
    fun read(text: Spanned, selStart: Int, selEnd: Int): AgentRead {
        val fields = fieldsOf(text)
        if (selStart != selEnd) return AgentRead(fields, null)
        val block = Blocks.at(text, selStart)?.span
        val leaf = block?.leaf
        if (leaf == null || leaf == Leaf.Title || leaf is Leaf.Raw) return AgentRead(fields, null)
        val marked = SpannableStringBuilder(text).apply {
            insert(selStart, MARK.toString())
            // The last block's span ends where its text does, exclusive, so a mark at its end lands outside it.
            if (getSpanEnd(block) <= selStart) setSpan(block, getSpanStart(block), selStart + 1, getSpanFlags(block))
        }
        val content = fieldsOf(marked).content
        // Where the two first differ, not where the mark first is: the note may hold it already.
        var at = 0
        while (at < fields.content.length && fields.content[at] == content.getOrNull(at)) at++
        if (content.getOrNull(at) != MARK) return AgentRead(fields, null)
        return AgentRead(fields, content.codePointCount(0, at))
    }

    fun fieldsOf(text: Spanned): Fields {
        val doc = SpanCodec.toDocument(text)
        return Fields(doc.title.orEmpty(), Markdown.serialize(doc))
    }
}
