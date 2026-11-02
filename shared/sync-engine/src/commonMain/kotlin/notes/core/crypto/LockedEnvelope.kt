package notes.core.crypto

/** The shape of a locked body (§10.1), which sync and the index need without the crypto. */
object LockedEnvelope {
    /** Every locked body, of any version: `notesd:locked:<version>:…`. */
    const val FAMILY = "notesd:locked:"

    /** The one version this app opens. */
    const val VERSION = "v1"
    const val PREFIX = "$FAMILY$VERSION:"

    private val VERSION_SHAPE = Regex("^v[1-9][0-9]*$")

    /** Locked by any version, including ones newer than this app: never indexed, merged or saved over as text. */
    fun isLocked(content: String) = content.startsWith(FAMILY)

    /** The version a locked body names (`v1`, `v2`…); null when it isn't locked or names none that parses. */
    fun lockVersion(content: String): String? {
        if (!isLocked(content)) return null
        val end = content.indexOf(':', FAMILY.length)
        if (end < 0) return null
        return content.substring(FAMILY.length, end).takeIf { VERSION_SHAPE.matches(it) }
    }

    /** Locked by a version this app can't open. */
    fun isUnsupported(content: String) = isLocked(content) && lockVersion(content) != VERSION
}
