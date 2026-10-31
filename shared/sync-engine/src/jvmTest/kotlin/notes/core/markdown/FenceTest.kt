package notes.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import notes.core.Fixtures
import notes.core.markdown.Fence.Token

class FenceTest {
    @Test
    fun `fences split as in the shared fixtures`() {
        val failures = Fixtures.fences.mapNotNull { c ->
            val got = Fence.parse(c.source)?.let { Fixtures.FenceCase.Shown(it.lang, it.code) }
            if (got == c.fence) null else "${c.name}: expected ${c.fence} got $got"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `highlight finds keywords, strings, numbers and comments`() {
        val code = "func main() { // hi\n\tx := \"a\\\"b\" + 42\n}"
        val got = Fence.highlight(code, "Go").map { code.substring(it.start, it.end) to it.token }
        assertEquals(
            listOf("func" to Token.Keyword, "// hi" to Token.Comment, "\"a\\\"b\"" to Token.Str, "42" to Token.Number),
            got
        )
        assertEquals(emptyList(), Fence.highlight(code, "brainfuck"))
        assertEquals(emptyList(), Fence.highlight(code, ""))
    }
}
