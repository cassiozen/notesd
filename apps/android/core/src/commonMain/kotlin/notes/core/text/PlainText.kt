package notes.core.text

// Markdown → plain text, ported from the server (internal/search/extract.go)
// via the web app (apps/web/src/notes/plaintext.ts) and pinned by
// shared/markdown-fixtures/plaintext.json. Keep the three in step.

/** The text indexed for search: one line per block, Markdown syntax removed. */
fun plainText(markdown: String): String {
    val out = ArrayList<String>()
    eachLine(markdown) { text, code -> out += if (code) text.trimEnd(' ', '\t') else inline(text).trim(::isJsSpace) }
    return collapseBlankLines(out)
}

/**
 * Calls [fn] for every line: code lines inside fences as they are, other lines
 * with block markers removed and inline syntax left in. Fence delimiters are
 * skipped and thematic breaks become "".
 */
internal fun eachLine(markdown: String, fn: (text: String, code: Boolean) -> Unit) {
    val md = markdown.replace(Regex("\r\n?"), "\n")
    var fence = "" // the opening fence while inside a fenced code block
    for (line in md.split('\n')) {
        val trimmed = line.trimStart(' ', '\t')
        if (fence.isNotEmpty()) {
            if (trimmed.startsWith(fence) && trimAll(trimmed, fence[0]).isEmpty()) {
                fence = ""
                continue
            }
            fn(line, true)
            continue
        }
        val opener = fenceOpener(trimmed)
        if (opener.isNotEmpty()) {
            fence = opener
            continue
        }
        if (isRule(trimmed)) {
            fn("", false)
            continue
        }
        fn(stripBlockMarkers(trimmed), false)
    }
}

private const val PREVIEW_CHARS = 160

/** The list preview the server sends (notes.preview): one line per block, ≤160 code points. */
fun preview(markdown: String): String = previewOf(plainText(markdown))

/** [preview] for text already extracted: one block per line. */
fun previewOf(text: String): String {
    val lines =
        text
            .split('\n')
            .map { l -> l.split(UNICODE_SPACE).filter { it.isNotEmpty() }.joinToString(" ") }
            .filter { it.isNotEmpty() }
    val joined = lines.joinToString("\n")
    val count = joined.codePointLength()
    if (count <= PREVIEW_CHARS) return joined
    val cut = joined.substring(0, joined.offsetOfCodePoint(PREVIEW_CHARS))
    return "${cut.trim(::isJsSpace)}…"
}

// JavaScript's /\s+/u.
private val UNICODE_SPACE = Regex("$JS_SPACE_CLASS+")

private fun trimAll(s: String, c: Char): String = s.trim(c)

private fun fenceOpener(s: String): String {
    for (c in listOf("```", "~~~")) {
        if (s.startsWith(c)) return c[0].toString().repeat(runLen(s, 0, c[0]))
    }
    return ""
}

/** Thematic breaks and setext underlines: only -, *, _ or = (≥3, or ≥2 for =), ignoring spaces. */
private fun isRule(s: String): Boolean {
    val compact = s.replace(" ", "")
    if (compact.length < 2) return false
    val c = compact[0]
    if (c != '-' && c != '*' && c != '_' && c != '=') return false
    if (trimAll(compact, c).isNotEmpty()) return false
    return c == '=' || compact.length >= 3
}

private fun stripBlockMarkers(input: String): String {
    var s = input
    while (true) {
        val before = s
        if (s.startsWith(">")) {
            s = s.substring(1).trimStart(' ', '\t')
        } else if (s.length >= 2 && s[0] in "-*+" && (s[1] == ' ' || s[1] == '\t')) {
            s = s.substring(2).trimStart(' ', '\t')
        } else if (s == "-" || s == "*" || s == "+") {
            s = ""
        } else {
            var n = orderedMarker(s)
            if (n == 0) n = taskBox(s)
            if (n > 0) {
                s = s.substring(n).trimStart(' ', '\t')
            } else {
                n = headingMarker(s)
                if (n > 0) s = trimClosingHashes(s.substring(n).trimStart(' ', '\t'))
            }
        }
        if (s == before) return s
    }
}

private fun orderedMarker(s: String): Int {
    var i = 0
    while (i < s.length && i < 9 && s[i] in '0'..'9') i++
    if (i == 0 || i >= s.length || (s[i] != '.' && s[i] != ')')) return 0
    if (i + 1 == s.length) return i + 1
    if (s[i + 1] == ' ' || s[i + 1] == '\t') return i + 2
    return 0
}

private fun taskBox(s: String): Int {
    if (s.length >= 3 && s[0] == '[' && s[2] == ']' && s[1] in " xX") {
        if (s.length == 3 || s[3] == ' ' || s[3] == '\t') return 3
    }
    return 0
}

private fun headingMarker(s: String): Int {
    var n = 0
    while (n < s.length && s[n] == '#') n++
    if (n == 0 || n > 6) return 0
    if (n == s.length || s[n] == ' ' || s[n] == '\t') return n
    return 0
}

private fun trimClosingHashes(s: String): String {
    val t = s.trimEnd(' ', '\t')
    val u = t.trimEnd('#')
    if (u == t) return s
    if (u.isEmpty() || u.endsWith(' ') || u.endsWith('\t')) return u.trimEnd(' ', '\t')
    return s
}

/** U+FFFC: stands for code, links and anything else scanners mustn't look inside. */
const val OBJECT = '\uFFFC'

/**
 * Removes inline syntax. With [opaque], code spans, links, images and
 * autolinks become a single [OBJECT] instead of their text.
 */
internal fun inline(s: String, opaque: Boolean = false): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length && isAsciiPunct(s[i + 1])) {
            out.append(s[i + 1])
            i += 2
        } else if (c == '`') {
            val n = runLen(s, i, '`')
            val ticks = s.substring(i, i + n)
            val end = s.indexOf(ticks, i + n)
            if (end >= 0) {
                if (opaque) out.append(OBJECT) else out.append(s.substring(i + n, end).trim(::isJsSpace))
                i = end + n
            } else {
                out.append(ticks)
                i += n
            }
        } else if (c == '!' && i + 1 < s.length && s[i + 1] == '[') {
            val l = link(s.substring(i + 1))
            if (l != null) {
                out.append(if (opaque) OBJECT.toString() else inline(l.first))
                i += 1 + l.second
            } else {
                out.append(c)
                i++
            }
        } else if (c == '[') {
            val l = link(s.substring(i))
            if (l != null) {
                out.append(if (opaque) OBJECT.toString() else inline(l.first))
                i += l.second
            } else {
                out.append(c)
                i++
            }
        } else if (c == '<') {
            val end = s.indexOf('>', i)
            if (end - i > 1 && isAutolink(s.substring(i + 1, end))) {
                if (opaque) out.append(OBJECT) else out.append(s, i + 1, end)
                i = end + 1
            } else {
                out.append(c)
                i++
            }
        } else if (c == '*' || c == '_' || c == '~') {
            val n = runLen(s, i, c)
            if ((c == '~' && n < 2) || !isDelimiter(s, i, n, c)) out.append(s, i, i + n)
            i += n
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/** "[text](destination)" at the start of s: the text and how many chars it spans. */
private fun link(s: String): Pair<String, Int>? {
    var depth = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\') {
            i++
        } else if (c == '[') {
            depth++
        } else if (c == ']') {
            depth--
            if (depth != 0) {
                i++
                continue
            }
            if (i + 1 >= s.length || s[i + 1] != '(') return null
            var pd = 0
            var j = i + 1
            while (j < s.length) {
                val d = s[j]
                if (d == '\\') {
                    j++
                } else if (d == '(') {
                    pd++
                } else if (d == ')' && --pd == 0) {
                    return s.substring(1, i) to j + 1
                }
                j++
            }
            return null
        }
        i++
    }
    return null
}

private fun isAutolink(s: String): Boolean {
    if (s.any { it == ' ' || it == '\t' || it == '<' }) return false
    val colon = s.indexOf(':')
    if (colon >= 2) return s.substring(0, colon).all { it.isAsciiLetterOrDigit() || it in "+.-" }
    val at = s.indexOf('@')
    return at > 0 && s.substring(at).contains('.')
}

/** Emphasis run or literal character, approximating CommonMark flanking (snake_case is literal). */
private fun isDelimiter(s: String, i: Int, n: Int, c: Char): Boolean {
    val before = if (i == 0) ' '.code else s.codePointBeforeIndex(i)
    val after = if (i + n >= s.length) ' '.code else s.codePointAtIndex(i + n)
    if (isUnicodeSpace(before) && isUnicodeSpace(after)) return false
    if (c == '_' && isAlnum(before) && isAlnum(after)) return false
    return true
}

private fun isUnicodeSpace(cp: Int): Boolean = cp < 0x10000 && UNICODE_SPACE.matches(cp.toChar().toString())

private fun isAlnum(cp: Int): Boolean = isLetterCodePoint(cp) || isDecimalDigitCodePoint(cp)

private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun runLen(s: String, i: Int, c: Char): Int {
    var n = 0
    while (i + n < s.length && s[i + n] == c) n++
    return n
}

private fun isAsciiPunct(c: Char): Boolean = c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private fun collapseBlankLines(lines: List<String>): String {
    val out = StringBuilder()
    var blank = true // suppress leading blank lines
    for (l in lines) {
        if (l.isEmpty()) {
            blank = true
            continue
        }
        if (out.isNotEmpty()) out.append(if (blank) "\n\n" else "\n")
        out.append(l)
        blank = false
    }
    return out.toString()
}
