package notes.core.web

import kotlin.js.Promise
import kotlinx.coroutines.await
import notes.core.api.ApiResult
import notes.core.api.PendingImages
import notes.core.api.SyncApi
import notes.core.db.Database
import notes.core.db.uploadPendingImages
import notes.core.text.nowMillis
import org.khronos.webgl.Int8Array

/** Where the web app keeps image files, by id (the SHA-256 of the bytes). */
external interface ImageFiles {
    fun read(id: String): Promise<Int8Array?>

    fun write(id: String, bytes: Int8Array, type: String): Promise<Unit>
}

/**
 * Images added on this device (architecture §7.6): the file in [files], a
 * `pending_images` row until sync uploads it. Uploaded, the file stays where
 * it is, as the copy the device shows.
 */
internal class WebImages(private val db: Database, private val files: ImageFiles) : PendingImages {
    suspend fun add(id: String, bytes: Int8Array, type: String) {
        // The file is stored before the row says it exists.
        files.write(id, bytes, type).await()
        db.write { sql ->
            sql.exec(
                "INSERT OR IGNORE INTO pending_images (id, content_type, created_at) VALUES (?, ?, ?)",
                id,
                type,
                nowMillis()
            )
        }
    }

    override suspend fun upload(client: SyncApi): ApiResult<List<String>> = uploadPendingImages(
        db,
        client,
        read = { id, _ -> files.read(id).await()?.unsafeCast<ByteArray>() },
        uploaded = { id, _ -> forget(id) }
    )

    private suspend fun forget(id: String) = db.write { sql -> sql.exec("DELETE FROM pending_images WHERE id = ?", id) }
}
