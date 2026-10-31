package notes.core.crypto

/** The shape of a locked body (§10.1), which sync and the index need without the crypto. */
object LockedEnvelope {
    const val PREFIX = "notesd:locked:v1:"

    fun isLocked(content: String) = content.startsWith(PREFIX)
}
