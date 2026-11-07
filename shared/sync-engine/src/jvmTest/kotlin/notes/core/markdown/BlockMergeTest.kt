package notes.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import notes.core.Fixtures

class BlockMergeTest {
    @Test
    fun `merge fixtures`() = check(Fixtures.merge)

    @Test
    fun `generated merge corpus matches the web's answers`() = check(Fixtures.mergeGenerated)

    @Test
    fun `gives up on huge rewrites rather than stall`() {
        fun doc(prefix: String) = (0 until 2100).joinToString("\n\n") { "$prefix$it" }
        assertEquals(BodyMerge.Conflict, BlockMerge.merge(doc("a"), doc("b"), doc("c")))
    }

    private fun check(cases: List<Fixtures.MergeCase>) {
        val failures =
            cases.mapNotNull { c ->
                val want = expected(c)
                val got = BlockMerge.merge(c.base, c.mine, c.theirs)
                if (got == want) null else "${c.name}: expected $want got $got"
            }
        assertEquals(emptyList(), failures)
    }

    private fun expected(c: Fixtures.MergeCase): BodyMerge {
        val e = c.expect
        if (e is JsonPrimitive) {
            require(e.content == "conflict") { "${c.name}: unknown expectation $e" }
            return BodyMerge.Conflict
        }
        e as JsonObject
        e["clean"]?.let { return BodyMerge.Clean(it.jsonPrimitive.content) }
        val partial = e.getValue("partial").jsonObject
        return BodyMerge.Partial(
            partial.getValue("theirs").jsonPrimitive.content,
            partial.getValue("mine").jsonPrimitive.content
        )
    }
}
