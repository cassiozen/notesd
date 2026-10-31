package notes.core.images

import notes.core.text.sha256Hex

/**
 * An image the apps added to a note (architecture §7.6), as the raw inline
 * run holding it: `![alt](notesd:image/<sha256 hex>?w=<px>&h=<px>)`. The web
 * app parses the same form in src/images/ref.ts.
 */
data class ImageRef(val id: String, val width: Int, val height: Int) {
    /** The Markdown for this image; [alt] is escaped so it stays one plain run. */
    fun markdown(alt: String = ""): String {
        val escaped = alt.replace(Regex("\\s+"), " ").replace(ESCAPE) { "\\" + it.value }
        return "![$escaped](notesd:image/$id?w=$width&h=$height)"
    }

    companion object {
        private val SOURCE = Regex(
            "^!\\[(.*)]\\(notesd:image/([0-9a-f]{64})\\?w=([1-9]\\d{0,5})&h=([1-9]\\d{0,5})\\)$",
            RegexOption.DOT_MATCHES_ALL
        )
        private val ESCAPE = Regex("[\\\\\\[\\]*_`~<>!#&]")

        /** The image in a raw inline run's [source]; null for any other run (code, other images). */
        fun parse(source: String): ImageRef? {
            val m = SOURCE.matchEntire(source) ?: return null
            return ImageRef(m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4].toInt())
        }

        /** The alt text, unescaped, for describing the image to TalkBack. */
        fun alt(source: String): String {
            val written = SOURCE.matchEntire(source)?.groupValues?.get(1) ?: return ""
            return written.replace(Regex("\\\\(.)"), "$1")
        }

        /** An image reference's destination in Markdown, from its "](": the id is group 1. */
        internal val REFERENCE = Regex("\\]\\(notesd:image/([0-9a-f]{64})\\?w=[1-9]\\d{0,5}&h=[1-9]\\d{0,5}\\)")

        /**
         * The images [markdown] shows, each once: what a locked note's save
         * lists, since the server can't read them from the ciphertext. One
         * written inside code counts too, which only keeps that image longer.
         */
        fun ids(markdown: String): List<String> =
            REFERENCE.findAll(markdown).map { it.groupValues[1] }.distinct().toList()

        fun sha256(bytes: ByteArray): String = sha256Hex(bytes)
    }
}
