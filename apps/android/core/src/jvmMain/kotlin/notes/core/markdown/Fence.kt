package notes.core.markdown

/**
 * A raw block that is a fenced code block, split for display: the language
 * from the info string and the code without its fence lines (spec §7.1,
 * shared/markdown-fixtures/fences.json). Display only: the block still saves
 * its source unchanged.
 */
data class Fence(val lang: String, val code: String) {
    enum class Token { Comment, Str, Number, Keyword }

    data class Run(val start: Int, val end: Int, val token: Token)

    companion object {
        private val open = Regex("^( {0,3})(`{3,}|~{3,})(.*)$")

        fun parse(source: String): Fence? {
            val lines = source.split('\n')
            val m = open.matchEntire(lines[0]) ?: return null
            val (indent, marker, info) = m.destructured
            // A backtick fence's info string can't hold a backtick (CommonMark §4.5).
            if (marker[0] == '`' && '`' in info) return null
            val body = lines.drop(1).toMutableList()
            val close = Regex("^ {0,3}${if (marker[0] == '`') "`" else "~"}{${marker.length},}\\s*$")
            if (body.isNotEmpty() && close.matches(body.last())) body.removeAt(body.lastIndex)
            // Content lines lose up to as many leading spaces as the opening fence had.
            val strip = Regex("^ {0,${indent.length}}")
            return Fence(
                info.trim().split(Regex("\\s+"))[0],
                body.joinToString("\n") { strip.replaceFirst(it, "") }
            )
        }

        /**
         * The colored runs of [code] in [lang]: comments, strings, numbers and
         * keywords. Empty for a language it doesn't know, which stays plain.
         */
        // ponytail: a keyword scanner, not a grammar (the web uses highlight.js).
        // Python's triple quotes and nested comments come out partly wrong; a real
        // highlighter would mean a bundled grammar set per platform.
        fun highlight(code: String, lang: String): List<Run> {
            val spec = languages[lang.lowercase()] ?: return emptyList()
            val out = ArrayList<Run>()
            var i = 0

            // ASCII letters, digits and _, and anything past ASCII (as on iOS, which scans UTF-16).
            fun word(at: Int) = at < code.length &&
                code[at].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it.code >= 0x80 }
            while (i < code.length) {
                val start = i
                val c = code[i]
                val block = spec.block
                when {
                    block != null && code.startsWith(block.first, i) -> {
                        val end = code.indexOf(block.second, i + block.first.length)
                        i = if (end < 0) code.length else end + block.second.length
                        out += Run(start, i, Token.Comment)
                    }

                    spec.line.any { code.startsWith(it, i) } -> {
                        i = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                        out += Run(start, i, Token.Comment)
                    }

                    c in spec.quotes -> {
                        i++
                        while (i < code.length && code[i] != c && (code[i] != '\n' || c == '`')) {
                            i += if (code[i] == '\\') 2 else 1
                        }
                        i = minOf(i + 1, code.length)
                        out += Run(start, i, Token.Str)
                    }

                    c in '0'..'9' -> {
                        while (word(i) || (i < code.length && code[i] == '.')) i++
                        out += Run(start, i, Token.Number)
                    }

                    word(i) -> {
                        while (word(i)) i++
                        if (code.substring(start, i) in spec.keywords) out += Run(start, i, Token.Keyword)
                    }

                    else -> i++
                }
            }
            return out
        }

        private class Spec(
            val keywords: Set<String>,
            val line: List<String> = listOf("//"),
            val block: Pair<String, String>? = "/*" to "*/",
            val quotes: String = "\"'"
        )

        private fun words(s: String) = s.split(' ').toSet()

        private val cLike = "break case const continue default do else enum for goto if return sizeof static " +
            "struct switch typedef union void while true false"

        private val languages: Map<String, Spec> = buildMap {
            fun add(names: String, spec: Spec) = names.split(' ').forEach { put(it, spec) }
            add(
                "go golang",
                Spec(
                    words(
                        "break case chan const continue default defer else fallthrough for func go goto if " +
                            "import interface map package range return select struct switch type var nil true false"
                    ),
                    quotes = "\"'`"
                )
            )
            add(
                "js javascript jsx mjs ts typescript tsx",
                Spec(
                    words(
                        "as async await break case catch class const continue default delete do else export " +
                            "extends false finally for from function if import in instanceof interface let new " +
                            "null of return static super switch this throw true try type typeof undefined var " +
                            "void while yield"
                    ),
                    quotes = "\"'`"
                )
            )
            add(
                "kotlin kt kts",
                Spec(
                    words(
                        "as break class companion const continue data do else enum false for fun if import in " +
                            "interface is null object override package private public return sealed super this " +
                            "throw true try typealias val var when while"
                    )
                )
            )
            add(
                "swift",
                Spec(
                    words(
                        "actor as async await break case catch class continue default defer do else enum " +
                            "extension false for func guard if import in init let nil private protocol public " +
                            "return self static struct super switch throw throws true try var where while"
                    )
                )
            )
            add(
                "java",
                Spec(
                    words(
                        "abstract boolean break case catch char class continue default do double else enum " +
                            "extends false final finally float for if implements import int interface long new " +
                            "null package private protected public return static super switch this throw throws " +
                            "true try void while"
                    )
                )
            )
            add("c h", Spec(words("$cLike char double extern float int long short signed unsigned NULL")))
            add(
                "cpp c++ cc hpp",
                Spec(
                    words(
                        "$cLike auto bool catch char class delete double float int long namespace new nullptr " +
                            "private public template this throw try using virtual"
                    )
                )
            )
            add(
                "cs csharp",
                Spec(
                    words(
                        "abstract as async await bool break case catch class const continue default do else " +
                            "enum false finally for foreach if in int interface namespace new null override " +
                            "private public return static string struct this throw true try using var void while"
                    )
                )
            )
            add(
                "rust rs",
                Spec(
                    words(
                        "as async await break const continue crate else enum false fn for if impl in let loop " +
                            "match mod move mut pub ref return self Self static struct trait true type use where while"
                    )
                )
            )
            add(
                "py python",
                Spec(
                    words(
                        "and as assert async await break class continue def del elif else except False finally " +
                            "for from global if import in is lambda None nonlocal not or pass raise return True " +
                            "try while with yield"
                    ),
                    line = listOf("#"),
                    block = null
                )
            )
            add(
                "rb ruby",
                Spec(
                    words(
                        "begin break case class def do else elsif end ensure false for if in module next nil " +
                            "not or rescue return self super then true unless until when while yield"
                    ),
                    line = listOf("#"),
                    block = null
                )
            )
            add(
                "sh bash zsh shell console",
                Spec(
                    words("case do done elif else esac export fi for function if in local return then until while"),
                    line = listOf("#"),
                    block = null
                )
            )
            add(
                "sql",
                Spec(
                    words(
                        "and as by create delete desc drop from group having in index insert into join key " +
                            "left limit not null on or order primary select set table update values where " +
                            "AND AS BY CREATE DELETE DESC DROP FROM GROUP HAVING IN INDEX INSERT INTO JOIN KEY " +
                            "LEFT LIMIT NOT NULL ON OR ORDER PRIMARY SELECT SET TABLE UPDATE VALUES WHERE"
                    ),
                    line = listOf("--")
                )
            )
            add("json", Spec(words("true false null"), line = emptyList(), block = null, quotes = "\""))
            add("yaml yml", Spec(words("true false null yes no"), line = listOf("#"), block = null))
        }
    }
}
