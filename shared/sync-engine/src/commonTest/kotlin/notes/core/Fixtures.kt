package notes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The cross-platform fixtures in shared/markdown-fixtures, compiled in by generateFixtures. */
object Fixtures {
    @Serializable
    data class Case(
        val name: String,
        val markdown: String,
        val text: String? = null,
        val title: String = "",
        val tags: List<String>? = null
    )

    @Serializable
    private data class Suite(val cases: List<Case>)

    @Serializable
    data class FenceCase(val name: String, val source: String, val fence: Shown?) {
        @Serializable
        data class Shown(val lang: String, val code: String)
    }

    @Serializable
    private data class FenceSuite(val cases: List<FenceCase>)

    /** [expect]: `{"clean": md}`, `{"partial": {"theirs", "mine"}}` or `"conflict"`. */
    @Serializable
    data class MergeCase(
        val name: String,
        val base: String,
        val mine: String,
        val theirs: String,
        val expect: JsonElement
    )

    @Serializable
    private data class MergeSuite(val cases: List<MergeCase>)

    private val json = Json { ignoreUnknownKeys = true }

    val roundtrip: List<Case> by lazy { load("roundtrip.json") }
    val plaintext: List<Case> by lazy { load("plaintext.json") }
    val tags: List<Case> by lazy { load("tags.json") }
    val fences: List<FenceCase> by lazy { json.decodeFromString<FenceSuite>(read("fences.json")).cases }
    val merge: List<MergeCase> by lazy { json.decodeFromString<MergeSuite>(read("merge.json")).cases }
    val mergeGenerated: List<MergeCase> by lazy {
        json.decodeFromString<MergeSuite>(read("merge-generated.json")).cases
    }

    private fun load(name: String): List<Case> = json.decodeFromString<Suite>(read(name)).cases

    private fun read(name: String): String =
        MARKDOWN_FIXTURES.firstOrNull { it.first == name }?.second ?: error("No fixture $name")
}
