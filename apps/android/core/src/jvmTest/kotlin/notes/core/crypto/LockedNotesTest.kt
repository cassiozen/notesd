package notes.core.crypto

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** shared/crypto-fixtures/locked.json, the vectors the web app checks too. */
class LockedNotesTest {
    @Serializable
    private data class Kdf(
        val name: String,
        val password: String,
        val salt: String,
        val iterations: Int,
        val key: String
    )

    @Serializable
    private data class Hkdf(val name: String, val prf: String, val salt: String, val key: String)

    @Serializable
    private data class Wrap(val name: String, val kind: String, val kek: String, val key: String, val wrapped: String)

    @Serializable
    private data class Envelope(val name: String, val key: String, val envelope: String, val plaintext: String = "")

    @Serializable
    private data class Image(val name: String, val key: String, val plain: String? = null, val sealed: String)

    @Serializable
    private data class Verifier(val name: String, val key: String, val verifier: String)

    @Serializable
    private data class Suite(
        val kdf: List<Kdf>,
        val hkdf: List<Hkdf>,
        val wraps: List<Wrap>,
        val envelopes: List<Envelope>,
        val invalid: List<Envelope>,
        val images: List<Image>,
        val verifiers: List<Verifier>
    )

    private val fixtures: Suite by lazy {
        val dir = System.getProperty("notes.sharedDir") ?: error("notes.sharedDir not set (run through Gradle)")
        json.decodeFromString(File(dir, "crypto-fixtures/locked.json").readText())
    }

    private fun b(s: String) = LockedNotes.decode(s)

    @Test
    fun kdf() {
        for (v in fixtures.kdf) {
            assertEquals(
                v.key,
                LockedNotes.encode(LockedNotes.passwordKek(v.password, b(v.salt), v.iterations)),
                v.name
            )
        }
    }

    @Test
    fun hkdf() {
        for (v in fixtures.hkdf) {
            assertEquals(v.key, LockedNotes.encode(LockedNotes.passkeyKek(b(v.prf), b(v.salt))), v.name)
        }
    }

    @Test
    fun unwrap() {
        for (v in fixtures.wraps) {
            val kind = WrapKind.of(v.kind)!!
            assertEquals(v.key, LockedNotes.encode(LockedNotes.unwrap(b(v.kek), v.wrapped, kind)), v.name)
            val other = if (kind == WrapKind.Password) WrapKind.Recovery else WrapKind.Password
            assertFailsWith<LockedException>(v.name) { LockedNotes.unwrap(b(v.kek), v.wrapped, other) }
            val rewrapped = LockedNotes.wrap(b(v.kek), b(v.key), kind)
            assertEquals(v.key, LockedNotes.encode(LockedNotes.unwrap(b(v.kek), rewrapped, kind)), v.name)
        }
    }

    @Test
    fun envelopes() {
        for (v in fixtures.envelopes) {
            assertTrue(LockedNotes.isLocked(v.envelope), v.name)
            assertEquals(v.plaintext, LockedNotes.decrypt(b(v.key), v.envelope), v.name)
            val again = LockedNotes.encrypt(b(v.key), v.plaintext)
            assertNotEquals(v.envelope, again, "fresh nonce: ${v.name}")
            assertEquals(v.plaintext, LockedNotes.decrypt(b(v.key), again), v.name)
        }
    }

    @Test
    fun refusesInvalid() {
        for (v in fixtures.invalid) {
            assertFailsWith<LockedException>(v.name) { LockedNotes.decrypt(b(v.key), v.envelope) }
        }
    }

    @Test
    fun images() {
        for (v in fixtures.images) {
            val plain = v.plain
            if (plain == null) {
                assertFailsWith<LockedException>(v.name) { LockedNotes.decryptImage(b(v.key), b(v.sealed)) }
                continue
            }
            assertEquals(plain, LockedNotes.encode(LockedNotes.decryptImage(b(v.key), b(v.sealed))), v.name)
            val again = LockedNotes.encryptImage(b(v.key), b(plain))
            assertNotEquals(v.sealed, LockedNotes.encode(again), "fresh nonce: ${v.name}")
            assertEquals(plain, LockedNotes.encode(LockedNotes.decryptImage(b(v.key), again)), v.name)
        }
    }

    @Test
    fun verifiers() {
        for (v in fixtures.verifiers) assertEquals(v.verifier, LockedNotes.verifier(b(v.key)), v.name)
    }

    @Test
    fun recoveryCodes() {
        val code = LockedNotes.newRecoveryCode()
        assertTrue(Regex("^([2-9A-HJ-NP-Z]{3}-){7}[2-9A-HJ-NP-Z]{3}$").matches(code), code)
        assertEquals(code.replace("-", ""), LockedNotes.normalizeRecoveryCode(code.lowercase().replace('-', ' ')))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
