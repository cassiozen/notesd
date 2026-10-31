package notes.core.text

import kotlin.uuid.Uuid

/** UUIDv7 (RFC 9562): note ids that sort by creation time, so new notes sort chronologically. */
object Uuid7 {
    fun generate(nowMillis: Long = notes.core.text.nowMillis()): String {
        // A random UUID's bytes come from the platform's secure random; its version bits are replaced below.
        val bytes = Uuid.random().toByteArray()
        for (i in 0 until 6) bytes[i] = (nowMillis ushr (40 - 8 * i)).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        return Uuid.fromByteArray(bytes).toString()
    }
}

private val SERVER_ID = Regex("[A-Za-z0-9_-]{1,64}")

/** An id the server accepts for a note or space; another is refused at sync, and what it names lost. */
internal fun isServerId(id: String): Boolean = SERVER_ID.matches(id)
