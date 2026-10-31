package notes.core.db

import notes.core.text.isWordCodePoint

/** Search query building and excerpt markers, ported from the server (internal/search/search.go). */
object Search {
    const val TITLE_WEIGHT = 10.0
    const val CONTENT_WEIGHT = 1.0
    const val MAX_TERMS = 16

    /** Private-use code points around each match in an excerpt; note text can't contain a marker by accident. */
    const val MARK_OPEN = ""
    const val MARK_CLOSE = ""

    /**
     * Free-form input → an FTS5 MATCH expression: each word becomes a quoted
     * prefix term, ANDed, so FTS5 syntax in the input is inert and no input
     * can produce a query error. "" when there is nothing to search for.
     */
    fun buildQuery(input: String): String {
        val words = ArrayList<String>()
        val word = StringBuilder()
        var i = 0
        while (i <= input.length) {
            val pair = i + 1 < input.length && input[i].isHighSurrogate() && input[i + 1].isLowSurrogate()
            val width = if (pair) 2 else 1
            val cp = when {
                i >= input.length -> -1
                pair -> 0x10000 + ((input[i].code - 0xD800) shl 10) + (input[i + 1].code - 0xDC00)
                else -> input[i].code
            }
            if (cp >= 0 && isWordCodePoint(cp)) {
                word.append(input, i, i + width)
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
            i += width
        }
        // Words hold only letters, digits and combining marks, never a quote; quoting makes OR and NEAR literal.
        return words.take(MAX_TERMS).joinToString(" ") { "\"$it\"*" }
    }
}
