package notes.core.text

import java.security.MessageDigest

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

internal actual fun isWordCodePoint(cp: Int): Boolean =
    Character.isLetter(cp) || Character.isDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()

internal actual fun isLetterCodePoint(cp: Int): Boolean = Character.isLetter(cp)

internal actual fun isDecimalDigitCodePoint(cp: Int): Boolean =
    Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()

private val PUNCTUATION =
    setOf(
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION
    )

internal actual fun isPunctuationCodePoint(cp: Int): Boolean = Character.getType(cp).toByte() in PUNCTUATION
