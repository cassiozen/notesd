package notes.core.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The body failed to open: wrong key, tampering, or not an envelope at all. */
class LockedException(message: String = "Could not open the locked note") : Exception(message)

/** Which secret a keyring entry is wrapped under (§10.1). */
enum class WrapKind(val wire: String) {
    Password("password"),
    Recovery("recovery"),
    Passkey("passkey");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Locked notes crypto (architecture §10.1), pinned by
 * shared/crypto-fixtures/locked.json. The web app implements the same rules
 * in src/locks/crypto.ts; keep the two in step.
 *
 * A locked note's content is [PREFIX] + base64url(nonce ‖ ciphertext ‖ tag)
 * of its Markdown body under the 32-byte locked-notes key. The keyring holds
 * that key wrapped the same way under keys derived from the lock password,
 * the recovery code or a passkey.
 */
object LockedNotes {
    const val PREFIX = LockedEnvelope.PREFIX
    const val PASSWORD_ITERATIONS = 600_000
    const val RECOVERY_ITERATIONS = 10_000
    const val KEY_BYTES = 32

    private const val BODY_AAD = "notesd:locked:v1"
    private const val IMAGE_AAD = "notesd:image:v1"
    private const val NONCE = 12
    private const val TAG = 16
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()
    private val BASE64URL = (('A'..'Z') + ('a'..'z') + ('0'..'9') + '-' + '_').toSet()

    fun isLocked(content: String) = LockedEnvelope.isLocked(content)

    fun newKey(): ByteArray = randomBytes(KEY_BYTES)

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    fun encode(bytes: ByteArray): String = b64.encodeToString(bytes)

    fun decode(s: String): ByteArray {
        if (!s.all { it in BASE64URL }) {
            throw LockedException("Not base64url")
        }
        return try {
            unb64.decode(s)
        } catch (_: IllegalArgumentException) {
            throw LockedException("Not base64url")
        }
    }

    /** Encrypts a note's Markdown body into its stored content. */
    fun encrypt(key: ByteArray, markdown: String): String = PREFIX + encode(seal(key, markdown.toByteArray(), BODY_AAD))

    /** Decrypts stored content; throws [LockedException] unless it is an envelope [key] sealed. */
    fun decrypt(key: ByteArray, content: String): String {
        if (!isLocked(content)) throw LockedException("Not a locked note")
        val plain = open(key, decode(content.substring(PREFIX.length)), BODY_AAD)
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(plain)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw LockedException("Not UTF-8")
        }
    }

    /** Encrypts a locked note's image file (§7.6): nonce ‖ ciphertext ‖ tag, binary. */
    fun encryptImage(key: ByteArray, file: ByteArray): ByteArray = seal(key, file, IMAGE_AAD)

    /** Decrypts a locked note's image; throws [LockedException] unless [key] sealed it as an image. */
    fun decryptImage(key: ByteArray, sealed: ByteArray): ByteArray = open(key, sealed, IMAGE_AAD)

    /** PBKDF2-HMAC-SHA256 of the NFC-normalized password. */
    fun passwordKek(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = Normalizer.normalize(password, Normalizer.Form.NFC).toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    /** HKDF-SHA256 (RFC 5869) of a passkey's PRF output. */
    fun passkeyKek(prf: ByteArray, salt: ByteArray): ByteArray {
        val prk = hmac(salt, prf)
        return hmac(prk, "notesd:passkey:v1".toByteArray() + 1)
    }

    fun wrap(kek: ByteArray, key: ByteArray, kind: WrapKind): String =
        encode(seal(kek, key, "notesd:lnk:v1:${kind.wire}"))

    /** The locked-notes key; [LockedException] means the password (or code) was wrong. */
    fun unwrap(kek: ByteArray, wrapped: String, kind: WrapKind): ByteArray {
        val key = open(kek, decode(wrapped), "notesd:lnk:v1:${kind.wire}")
        if (key.size != KEY_BYTES) throw LockedException("Bad key length")
        return key
    }

    /** HMAC(key, "notesd:verifier:v1"): proves to the server that this client holds the key, without revealing it. */
    fun verifier(key: ByteArray): String = encode(hmac(key, "notesd:verifier:v1".toByteArray()))

    private const val RECOVERY_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** A random 120-bit recovery code, shown once: eight groups of three. */
    fun newRecoveryCode(): String = randomBytes(24).map { RECOVERY_ALPHABET[it.toInt() and 31] }
        .chunked(3).joinToString("-") { it.joinToString("") }

    /** Recovery codes are typed by hand: ignore case, spaces and dashes. */
    fun normalizeRecoveryCode(code: String) = code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun seal(key: ByteArray, plain: ByteArray, aad: String): ByteArray {
        val nonce = randomBytes(NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
        cipher.updateAAD(aad.toByteArray())
        return nonce + cipher.doFinal(plain)
    }

    private fun open(key: ByteArray, sealed: ByteArray, aad: String): ByteArray {
        if (sealed.size < NONCE + TAG) throw LockedException("Too short")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, sealed, 0, NONCE))
            cipher.updateAAD(aad.toByteArray())
            cipher.doFinal(sealed, NONCE, sealed.size - NONCE)
        } catch (_: GeneralSecurityException) {
            throw LockedException()
        }
    }
}
