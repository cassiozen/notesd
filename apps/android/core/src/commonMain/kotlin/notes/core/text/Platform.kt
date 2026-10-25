package notes.core.text

/** Unix ms. */
expect fun nowMillis(): Long

/** SHA-256 of [bytes] as lowercase hex. */
expect fun sha256Hex(bytes: ByteArray): String

/** A letter, a decimal digit or a non-spacing mark: what the server's search tokenizer keeps in a word. */
internal expect fun isWordCodePoint(cp: Int): Boolean
