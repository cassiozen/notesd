package notes.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServerAddressTest {
    private fun url(input: String) = assertIs<ServerAddress.Valid>(ServerAddress.parse(input), input).url.toString()

    @Test
    fun `bare hosts get a scheme by where they are`() {
        assertEquals("https://notes.example.com/", url("notes.example.com"))
        assertEquals("https://notes.example.com/", url("  notes.example.com/ "))
        assertEquals("http://192.168.1.5:8080/", url("192.168.1.5:8080"))
        assertEquals("http://10.0.2.2:8080/", url("10.0.2.2:8080"))
        assertEquals("http://nas.local/", url("nas.local"))
        assertEquals("http://localhost:8080/", url("localhost:8080"))
        assertEquals("https://example.com/notes", url("example.com/notes/"))
    }

    @Test
    fun `explicit schemes are kept, but http only on a private network`() {
        assertEquals("https://192.168.1.5/", url("https://192.168.1.5"))
        assertEquals("http://172.20.0.1/", url("http://172.20.0.1"))
        assertIs<ServerAddress.Invalid>(ServerAddress.parse("http://notes.example.com"))
        assertIs<ServerAddress.Invalid>(ServerAddress.parse("http://172.32.0.1"))
        assertIs<ServerAddress.Invalid>(ServerAddress.parse("http://8.8.8.8"))
    }

    @Test
    fun `nonsense is refused`() {
        for (input in listOf("", "   ", "ftp://x", "https://", "a b", "example.com/?q=1")) {
            assertIs<ServerAddress.Invalid>(ServerAddress.parse(input), input)
        }
    }

    @Test
    fun `private ranges`() {
        val private = listOf(
            "10.1.2.3",
            "127.0.0.1",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.0.1",
            "169.254.1.1",
            "::1",
            "fd00::1",
            "fe80::1"
        )
        for (h in private) {
            assertEquals(true, ServerAddress.isPrivate(h), h)
        }
        for (h in listOf(
            "172.15.0.1",
            "172.32.0.1",
            "192.169.0.1",
            "8.8.8.8",
            "example.com",
            "local",
            "2001:db8::1",
            "10.0.0.256"
        )) {
            assertEquals(false, ServerAddress.isPrivate(h), h)
        }
    }
}
