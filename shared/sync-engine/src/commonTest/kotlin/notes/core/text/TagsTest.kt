package notes.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import notes.core.Fixtures

class TagsTest {
    @Test
    fun `tags match the shared fixtures`() {
        val failures =
            Fixtures.tags.mapNotNull { c ->
                val got = tags(c.title, c.markdown)
                val keysOk = got.all { it.key == it.name.lowercase() }
                if (got.map { it.name } == c.tags && keysOk) null else "${c.name}: expected ${c.tags} got $got"
            }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `tag keys normalise names as typed`() {
        assertEquals("home/repairs", tagKey("#Home/Repairs"))
        assertEquals("school/biology", tagKey("#school / biology#"))
        assertEquals("", tagKey("#"))
    }

    @Test
    fun `matches cover the hash marks in char offsets`() {
        val line = "🎉 #a and #b c# end"
        assertEquals(listOf("#a", "#b c#"), scanTags(line).map { line.substring(it.start, it.end) })
    }

    @Test
    fun `tag text is closed when it needs to be`() {
        assertEquals("#home/repairs", tagText("home/repairs"))
        assertEquals("#summer trip#", tagText("summer trip"))
        assertEquals("#2024#", tagText("2024"))
        assertEquals("#v1.2#", tagText("v1.2"))
        for (name in listOf("home/repairs", "summer trip", "2024", "v1.2")) {
            assertEquals(listOf(name), tags("", "${tagText(name)} after").map { it.name })
        }
    }
}
