package notes.core.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import notes.core.api.LockKey

/** How a keyring entry's key comes from its secret (§10.1). */
sealed interface Kdf {
    data class Pbkdf2(val iterations: Int) : Kdf

    data object Hkdf : Kdf

    companion object {
        const val PBKDF2 = "pbkdf2-sha256"
        const val HKDF = "hkdf-sha256"

        /** The wire form, written on every entry this app makes. */
        fun pbkdf2(iterations: Int): JsonObject = buildJsonObject {
            put("name", PBKDF2)
            put("iterations", iterations)
        }

        val hkdf: JsonObject = buildJsonObject { put("name", HKDF) }

        /**
         * What [entry] says to derive with. Without `kdf` (before 1.0) it's implied by the kind.
         * Throws [UnsupportedKdfException] for a function this app doesn't know and
         * [MalformedKeyException] for one that can't be right.
         */
        fun of(entry: LockKey): Kdf {
            val kind = WrapKind.of(entry.kind) ?: throw MalformedKeyException("Unknown kind ${entry.kind}")
            val kdf = entry.kdf
            val derivation = if (kdf == null) {
                if (kind == WrapKind.Passkey) Hkdf else Pbkdf2(entry.iterations)
            } else {
                val name = (kdf["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw MalformedKeyException("No key derivation function named")
                when (name) {
                    PBKDF2 -> Pbkdf2(
                        (kdf["iterations"] as? JsonPrimitive)?.intOrNull
                            ?: throw MalformedKeyException("No iterations")
                    )

                    HKDF -> Hkdf

                    else -> throw UnsupportedKdfException(name)
                }
            }
            when (derivation) {
                is Pbkdf2 -> {
                    if (kind == WrapKind.Passkey) throw MalformedKeyException("A passkey entry derives with HKDF")
                    if (derivation.iterations <= 0) throw MalformedKeyException("No iterations")
                }

                Hkdf -> if (kind != WrapKind.Passkey) throw MalformedKeyException("Only a passkey entry uses HKDF")
            }
            return derivation
        }
    }
}

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
            wrapped = LockedNotes.wrap(kek, key, WrapKind.Password),
            kdf = Kdf.pbkdf2(iterations)
        )
    }

    /**
     * The key, from the password entry. [LockedException] means a wrong password (or no lock password yet);
     * [UnsupportedKdfException] and [MalformedKeyException] are the entry's doing, never the password's.
     */
    fun unlock(keys: List<LockKey>, password: String): ByteArray {
        val entry =
            keys.firstOrNull { it.kind == WrapKind.Password.wire } ?: throw LockedException("No lock password set")
        val kdf = Kdf.of(entry) as? Kdf.Pbkdf2 ?: throw MalformedKeyException("A password entry derives with PBKDF2")
        val kek = LockedNotes.passwordKek(password, LockedNotes.decode(entry.salt), kdf.iterations)
        return LockedNotes.unwrap(kek, entry.wrapped, WrapKind.Password)
    }
}
