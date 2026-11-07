package notes.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeopleLinkTest {
    @Test
    fun `the link is the server's address, the token after #`() {
        assertEquals("https://notes.example.com/setup#abc", setupLinkUrl("https://notes.example.com/", "abc"))
        assertEquals("http://192.168.1.20:8080/setup#t", setupLinkUrl("http://192.168.1.20:8080", "t"))
        // Behind a proxy at a path, the web app is there too.
        assertEquals("https://example.com/notes/setup#t", setupLinkUrl("https://example.com/notes/", "t"))
    }

    @Test
    fun `days left round up, so a new link says 7`() {
        val now = 1_000_000_000L
        val day = 86_400_000L
        assertEquals("link expires in 7 days", linkExpiry(now + 7 * day, now))
        assertEquals("link expires in 7 days", linkExpiry(now + 7 * day - 1_000, now))
        assertEquals("link expires in 2 days", linkExpiry(now + day + 5, now))
        assertEquals("link expires in 1 day", linkExpiry(now + 5, now))
        assertEquals("link expired", linkExpiry(now, now))
        assertEquals("link expired", linkExpiry(null, now))
    }

    @Test
    fun `the shared message says what the link is for, then the link`() {
        val invite = shareText("notes.example.com", pending = true, url = "https://x/setup#t")
        assertTrue(invite.startsWith("You're invited to notesd at notes.example.com."))
        assertTrue(invite.endsWith("\nhttps://x/setup#t"))
        assertTrue(
            shareText("h", pending = false, url = "u").startsWith("Here's a link to choose a new notesd password")
        )
    }
}
