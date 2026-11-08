package notes.app

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import notes.core.api.SpaceTrait

/** Gives a space [traits] as a sync would leave them: only a list download writes them, so tests go to the table. */
suspend fun AppGraph.setTraits(id: String, vararg traits: SpaceTrait) {
    val json = Json.encodeToString(ListSerializer(SpaceTrait.serializer()), traits.toList())
    database.write { it.exec("UPDATE spaces SET traits = ? WHERE id = ?", json, id) }
}
