package notes.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import notes.core.Fixtures

class PlainTextTest {
    @Test
    fun `plain text matches the shared fixtures`() {
        val failures =
            Fixtures.plaintext.mapNotNull { c ->
                val out = plainText(c.markdown)
                if (out == c.text) null else "${c.name}: expected ${c.text} got $out"
            }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `preview is one line per block and cut at 160 code points`() {
        assertEquals("Title\nBody text", preview("# Title\n\nBody   text\n"))
        val long = "🎉".repeat(200)
        val cut = preview(long)
        assertEquals(161, cut.codePointLength())
        assertTrue(cut.endsWith("…"))
    }

    @Test
    fun `uuid7 is well formed and sorts by time`() {
        val a = Uuid7.generate(1_700_000_000_000)
        val b = Uuid7.generate(1_700_000_000_001)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(a))
        assertTrue(a < b)
    }
}
