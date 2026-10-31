package notes.core.crypto

import notes.core.api.LockKey

/**
 * Keyring logic shared by every way of unlocking (§10.1), apart from storage
 * and the network. The locked-notes key never leaves memory unwrapped.
 */
object Keyring {
    /** A brand-new key and its password entry, to upload with [verifier]. */
    class Created(val key: ByteArray, val entry: LockKey, val verifier: String)

    fun create(password: String, iterations: Int = LockedNotes.PASSWORD_ITERATIONS): Created {
        val key = LockedNotes.newKey()
        return Created(key, passwordEntry(key, password, iterations), LockedNotes.verifier(key))
    }

    /** The password entry for [key]: a new salt, wrapped under [password]. */
    fun passwordEntry(key: ByteArray, password: String, iterations: Int = LockedNotes.PASSWORD_ITERATIONS): LockKey {
        val salt = LockedNotes.randomBytes(16)
        val kek = LockedNotes.passwordKek(password, salt, iterations)
        return LockKey(
            id = WrapKind.Password.wire,
            kind = WrapKind.Password.wire,
            salt = LockedNotes.encode(salt),
            iterations = iterations,
            wrapped = LockedNotes.wrap(kek, key, WrapKind.Password)
        )
    }

    /** The key, from the password entry. [LockedException] means a wrong password (or no lock password yet). */
    fun unlock(keys: List<LockKey>, password: String): ByteArray {
        val entry =
            keys.firstOrNull { it.kind == WrapKind.Password.wire } ?: throw LockedException("No lock password set")
        val kek = LockedNotes.passwordKek(password, LockedNotes.decode(entry.salt), entry.iterations)
        return LockedNotes.unwrap(kek, entry.wrapped, WrapKind.Password)
    }
}
