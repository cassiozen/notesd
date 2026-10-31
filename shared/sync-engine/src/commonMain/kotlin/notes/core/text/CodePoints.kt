package notes.core.text

// Code point helpers for common code, which lacks Java's String and Character ones.

internal fun charCount(cp: Int): Int = if (cp >= 0x10000) 2 else 1

/** The code point at [i]; a lone surrogate is itself. */
internal fun String.codePointAtIndex(i: Int): Int {
    val c = this[i]
    if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
        return 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
    }
    return c.code
}

/** The code point ending just before [i]. */
internal fun String.codePointBeforeIndex(i: Int): Int {
    val c = this[i - 1]
    if (c.isLowSurrogate() && i >= 2 && this[i - 2].isHighSurrogate()) return codePointAtIndex(i - 2)
    return c.code
}

internal fun String.codePointArray(): IntArray {
    val out = IntArray(length)
    var n = 0
    var i = 0
    while (i < length) {
        val cp = codePointAtIndex(i)
        out[n++] = cp
        i += charCount(cp)
    }
    return out.copyOf(n)
}

internal fun String.codePointLength(): Int {
    var n = 0
    var i = 0
    while (i < length) {
        i += charCount(codePointAtIndex(i))
        n++
    }
    return n
}

/** The char index [count] code points into the string. */
internal fun String.offsetOfCodePoint(count: Int): Int {
    var i = 0
    repeat(count) { i += charCount(codePointAtIndex(i)) }
    return i
}

internal fun codePointString(cps: IntArray, offset: Int, count: Int): String = buildString {
    for (k in offset until offset + count) {
        val cp = cps[k]
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            append(Char(0xD800 + (v shr 10)))
            append(Char(0xDC00 + (v and 0x3FF)))
        } else {
            append(Char(cp))
        }
    }
}

internal fun isIsoControl(c: Char): Boolean = c.code <= 0x1F || c.code in 0x7F..0x9F

/** Unicode general category L*. */
internal expect fun isLetterCodePoint(cp: Int): Boolean

/** Unicode general category Nd. */
internal expect fun isDecimalDigitCodePoint(cp: Int): Boolean

/** Unicode general category P* (Pc, Pd, Ps, Pe, Pi, Pf, Po). */
internal expect fun isPunctuationCodePoint(cp: Int): Boolean
