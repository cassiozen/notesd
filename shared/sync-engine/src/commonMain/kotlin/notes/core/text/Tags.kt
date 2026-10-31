package notes.core.text

// #tags (architecture §7.5), ported from the server (internal/search/tags.go)
// and pinned by shared/markdown-fixtures/tags.json. Keep the three in step.

/** A tag in a note. [key] identifies it: [name] lowercased. */
data class Tag(val key: String, val name: String)

/** A tag in a line of text; [start] and [end] are char offsets covering the # marks. */
data class TagMatch(val name: String, val start: Int, val end: Int)

/** A note's tags, first spelling of each key, in order of appearance. */
fun tags(title: String, markdown: String): List<Tag> {
    val out = ArrayList<Tag>()
    val seen = HashSet<String>()
    fun scan(line: String) {
        for (m in scanTags(line)) {
            val key = m.name.lowercase()
            if (seen.add(key)) out += Tag(key, m.name)
        }
    }
    scan(title)
    eachLine(markdown) { text, code -> if (!code) scan(inline(text, opaque = true)) }
    return out
}

/** The key for a tag name given with or without its # marks, or "". */
fun tagKey(name: String): String = normalize(name.removePrefix("#").removeSuffix("#")).lowercase()

/** How to write a tag in a note: closed with # when it needs it (spaces, digits only, punctuation). */
fun tagText(name: String): String {
    val open = "#$name"
    val m = scanTags(open).singleOrNull()
    return if (m != null && m.name == name && m.end == open.length) open else "$open#"
}

/** The tags in one line of visible text, where [OBJECT] stands for code, links and anything else tags can't be part of. */
fun scanTags(line: String): List<TagMatch> {
    val r = line.codePointArray()
    val at = IntArray(r.size + 1)
    for (k in r.indices) at[k + 1] = at[k] + charCount(r[k])

    val out = ArrayList<TagMatch>()
    var i = 0
    while (i < r.size) {
        if (r[i] != '#'.code || (i > 0 && !opensTag(r[i - 1]))) {
            i++
            continue
        }
        val next = r.getOrNull(i + 1)
        if (next == null || isSpace(next) || next == '#'.code || next == OBJECT.code) {
            i++
            continue
        }
        val close = closingHash(r, i)
        if (close > 0) {
            val name = normalize(codePointString(r, i + 1, close - i - 1))
            if (name.isNotEmpty()) out += TagMatch(name, at[i], at[close + 1])
            i = close + 1
            continue
        }
        var end = i + 1
        while (end < r.size && inWord(r[end])) end++
        val name = normalize(codePointString(r, i + 1, end - i - 1))
        if (name.isNotEmpty() && !numeric(name)) out += TagMatch(name, at[i], at[end])
        i = end
    }
    return out
}

/** The # closing a multi-word tag opened at r[open]: the next # on the line, if it follows a non-space and ends a word. */
private fun closingHash(r: IntArray, open: Int): Int {
    for (j in open + 2 until r.size) {
        if (r[j] == OBJECT.code) return 0
        if (r[j] == '#'.code) {
            if (isSpace(r[j - 1]) || (j + 1 < r.size && inWord(r[j + 1]))) return 0
            return j
        }
    }
    return 0
}

private fun opensTag(c: Int): Boolean = isSpace(c) || c == '('.code || c == '['.code || c == '{'.code

/** Anything but spaces, [OBJECT] and punctuation, except - _ and the / between nested tags. */
private fun inWord(c: Int): Boolean = when {
    c == '-'.code || c == '_'.code || c == '/'.code -> true
    c == OBJECT.code || isSpace(c) -> false
    c < 0x80 -> c.toChar().isLetterOrDigit()
    else -> !isPunctuationCodePoint(c)
}

// JavaScript's /\s/u has no characters outside the BMP.
private fun isSpace(c: Int): Boolean = c < 0x10000 && isJsSpace(c.toChar())

/** Trims each /-separated level, collapses inner spaces, drops empty levels. */
private fun normalize(s: String): String = s
    .split('/')
    .map { level -> level.split(Regex("$JS_SPACE_CLASS+")).filter { it.isNotEmpty() }.joinToString(" ") }
    .filter { it.isNotEmpty() }
    .joinToString("/")

/** Names like 1 or 2024/10 are tags only when closed with #, so "#1 priority" stays text. */
private fun numeric(name: String): Boolean = name.all { it in '0'..'9' || it in "-_/" }
