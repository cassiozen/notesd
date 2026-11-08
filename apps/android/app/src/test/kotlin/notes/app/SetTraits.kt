package notes.app

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import notes.core.api.Member
import notes.core.api.SharedSpace
import notes.core.api.SpaceTrait

/** Gives a space [traits] as a sync would leave them: only a list download writes them, so tests go to the table. */
suspend fun AppGraph.setTraits(id: String, vararg traits: SpaceTrait) {
    val json = Json.encodeToString(ListSerializer(SpaceTrait.serializer()), traits.toList())
    database.write { it.exec("UPDATE spaces SET traits = ? WHERE id = ?", json, id) }
}

/** Shares [id] with [shared]'s people, as a list download would leave it. */
suspend fun AppGraph.setShared(id: String, shared: SharedSpace) {
    val json = Json.encodeToString(SharedSpace.serializer(), shared)
    database.write { it.exec("UPDATE spaces SET shared = ? WHERE id = ?", json, id) }
}

/** An invitation waiting for this account, as a sync would leave it. */
suspend fun AppGraph.addInvite(id: String, spaceId: String, spaceName: String, spaceIcon: String, from: Member) {
    database.write {
        it.exec(
            """
            INSERT INTO invites (id, space_id, space_name, space_icon, from_id, from_username, created_at)
            VALUES (?, ?, ?, ?, ?, ?, 1)
            """,
            id,
            spaceId,
            spaceName,
            spaceIcon,
            from.id,
            from.username
        )
    }
}

/** This phone last showed [id] at [at]: what others changed since is news (§7.9). */
suspend fun AppGraph.lastShown(id: String, at: Long) {
    database.write { it.exec("UPDATE spaces SET visited_at = ? WHERE id = ?", at, id) }
}
