package notes.core.db

import notes.core.api.ApiResult
import notes.core.api.PendingImages
import notes.core.api.SyncApi

/** Unexpected answers a queued change gets, one per cycle, before it's set aside. */
internal const val PARK_AFTER = 3

/** The tables whose rows are queued changes, each with attempts and parked. */
private val QUEUES = listOf("sync_outbox", "spaces", "pending_images")

/** Parked changes back in line, one failure from parking again. */
internal suspend fun Sql.unparkAll() {
    for (t in QUEUES) exec("UPDATE $t SET parked = 0, attempts = ? WHERE parked = 1", PARK_AFTER - 1)
}

/** One more Unexpected answer for the row [where] picks in [table]; parked at [PARK_AFTER]. */
internal suspend fun Sql.attempted(table: String, where: String, vararg args: Any?) {
    require(table in QUEUES)
    exec("UPDATE $table SET attempts = attempts + 1, parked = (attempts + 1 >= ?) WHERE $where", PARK_AFTER, *args)
}

/**
 * Uploads the waiting images, oldest first, for [PendingImages] on either platform: [read] has the
 * bytes (null: gone, forgotten), [uploaded] is called when the server has them or refused them for good.
 */
internal suspend fun uploadPendingImages(
    db: Database,
    client: SyncApi,
    read: suspend (id: String, type: String) -> ByteArray?,
    uploaded: suspend (id: String, type: String) -> Unit
): ApiResult<List<String>> {
    val waiting = db.read { sql ->
        sql.query("SELECT id, content_type FROM pending_images WHERE parked = 0 ORDER BY created_at, id") {
            it.getText(0) to it.getText(1)
        }
    }
    val refused = ArrayList<String>()
    for ((id, type) in waiting) {
        val bytes = read(id, type)
        if (bytes == null) {
            db.write { sql -> sql.exec("DELETE FROM pending_images WHERE id = ?", id) }
            continue
        }
        when (val r = putToEachFile(db, client, id, bytes, type)) {
            is ApiResult.Ok -> uploaded(id, type)

            // Holding it back would stop every note from syncing; its notes show it as missing.
            is ApiResult.Rejected if r.error in PendingImages.REFUSED -> {
                uploaded(id, type)
                refused += "A picture wasn’t uploaded: ${r.message.ifEmpty { r.error }}."
            }

            is ApiResult.Unexpected -> {
                db.write { sql -> sql.attempted("pending_images", "id = ?", id) }
                return ApiResult.Unexpected(r.status)
            }

            // An error code notesd doesn't send for an upload is as unexpected as no code at all.
            is ApiResult.Rejected if r.status < 500 -> {
                db.write { sql -> sql.attempted("pending_images", "id = ?", id) }
                return ApiResult.Unexpected(r.status)
            }

            else -> return r.failure()
        }
    }
    return ApiResult.Ok(refused)
}

/**
 * Uploads an image to the file of each note here that shows it (§7.9): the account's, and each shared space's.
 * One no note shows yet goes to the account's file.
 */
private suspend fun putToEachFile(
    db: Database,
    client: SyncApi,
    id: String,
    bytes: ByteArray,
    type: String
): ApiResult<Unit> {
    val feeds = db.read { sql ->
        val spaces = sql.query(
            "SELECT DISTINCT space_id FROM notes WHERE deleted_at IS NULL AND instr(content, ?) > 0",
            "notesd:image/$id"
        ) { it.getText(0) }
        spaces.map { sql.feedOf(it) }.toSet().ifEmpty { setOf("") }
    }
    for (feed in feeds.sorted()) {
        val r = (if (feed.isEmpty()) client else client.inSpace(feed)).putImage(id, bytes, type)
        if (r !is ApiResult.Ok) return r
    }
    return ApiResult.Ok(Unit)
}

/** A failure retyped: none carry a value. */
private fun <T> ApiResult<*>.failure(): ApiResult<T> = when (this) {
    is ApiResult.Ok -> error("not a failure")
    is ApiResult.Conflict -> this
    ApiResult.Unauthenticated -> ApiResult.Unauthenticated
    is ApiResult.RateLimited -> this
    is ApiResult.Rejected -> this
    is ApiResult.Unreachable -> this
    is ApiResult.UpgradeRequired -> this
    is ApiResult.Unexpected -> this
}
