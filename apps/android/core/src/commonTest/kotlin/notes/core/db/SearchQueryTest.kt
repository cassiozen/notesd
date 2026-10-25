package notes.core.db

import kotlin.test.Test
import kotlin.test.assertEquals

// Same cases as the server's TestBuildQuery (internal/search/search_test.go).
class SearchQueryTest {
    @Test
    fun `builds quoted prefix terms`() {
        val cases = mapOf(
            "" to "",
            "   " to "",
            "milk" to "\"milk\"*",
            "Oat milk" to "\"Oat\"* \"milk\"*",
            "\"unbalanced" to "\"unbalanced\"*",
            "a OR b NEAR(c)" to "\"a\"* \"OR\"* \"b\"* \"NEAR\"* \"c\"*",
            "title:secret -x ^y *" to "\"title\"* \"secret\"* \"x\"* \"y\"*",
            "café naïve" to "\"café\"* \"naïve\"*",
            "日本語" to "\"日本語\"*",
            "--- ***" to ""
        )
        for ((input, want) in cases) assertEquals(want, Search.buildQuery(input), input)
    }

    @Test
    fun `caps the number of terms`() {
        assertEquals(Search.MAX_TERMS, Search.buildQuery("w ".repeat(100)).split(" ").size)
    }
}
