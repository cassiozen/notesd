package notes.core.images

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.ImageFile
import notes.core.api.LOCKED_IMAGE
import notes.core.api.PendingImages
import notes.core.api.SyncApi
import notes.core.crypto.LockedException
import notes.core.crypto.LockedNotes
import notes.core.db.Database

/** An image added on this device that the server doesn't have yet. */
data class PendingImage(val id: String, val type: String)

/**
 * This device's images (architecture §7.6). One added here is written to
 * [pendingDir] with a `pending_images` row and stays there until sync
 * uploads it; then it moves to [cacheDir], where images downloaded on first
 * view live too. The system may clear the cache: a cached image is fetched
 * again, a pending one is never lost. Locked notes' images stay encrypted on
 * disk; they are decrypted in memory to be shown.
 */
class ImageStore(
    private val db: Database,
    private val pendingDir: File,
    private val cacheDir: File,
    private val api: () -> ApiClient?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : PendingImages {
    /** Stores a new image's bytes (already prepared, and encrypted for a locked note); returns its id. */
    suspend fun add(bytes: ByteArray, type: String): String {
        val id = ImageRef.sha256(bytes)
        withContext(io) { write(File(pendingDir, fileName(id, type)), bytes) }
        // The file is durable before the row says it exists.
        db.write { sql ->
            sql.exec(
                "INSERT OR IGNORE INTO pending_images (id, content_type, created_at) VALUES (?, ?, ?)",
                id,
                type,
                clock()
            )
        }
        return id
    }

    /** An image from this device, or from the server the first time it's needed; null if neither has it. */
    suspend fun load(id: String): ImageFile? {
        local(id)?.let { return it }
        val client = api() ?: return null
        val result = client.getImage(id)
        if (result !is ApiResult.Ok) return null
        val file = result.value
        // The id is the hash: anything else isn't this image.
        if (ImageRef.sha256(file.bytes) != id || TYPES.none { it.second == file.type }) return null
        withContext(io) { write(File(cacheDir, fileName(id, file.type)), file.bytes) }
        return file
    }

    /** Whether the image is on this device, so it can be shown (or re-encrypted) offline. */
    suspend fun has(id: String): Boolean = local(id) != null

    private suspend fun local(id: String): ImageFile? = withContext(io) {
        for (dir in listOf(pendingDir, cacheDir)) {
            for ((ext, type) in TYPES) {
                val f = File(dir, "$id.$ext")
                if (f.isFile) return@withContext ImageFile(type, f.readBytes())
            }
        }
        null
    }

    /**
     * The note body [markdown] with each of its images stored again encrypted
     * ([locked]) or plain, as a lock change needs (§10.1): new images are
     * added as pending, and the references rewritten. Null, changing nothing,
     * when an image is neither on this device nor downloadable (offline), or
     * won't open with [key]: a locked note must not point at plain images.
     */
    suspend fun reseal(markdown: String, locked: Boolean, key: ByteArray): String? {
        val moved = move(ImageRef.ids(markdown).toSet(), locked, key) ?: return null
        if (moved.isEmpty()) return markdown
        return ImageRef.REFERENCE.replace(markdown) { m ->
            val next = moved[m.groupValues[1]] ?: return@replace m.value
            m.value.replace(m.groupValues[1], next)
        }
    }

    /**
     * [reseal] for images by id: each one not already stored the way [locked]
     * asks is added again, encrypted or decrypted. Returns old id → new id
     * for those, or null (nothing added) if any can't be had or opened.
     */
    suspend fun move(ids: Set<String>, locked: Boolean, key: ByteArray): Map<String, String>? {
        val next = mutableListOf<Triple<String, ByteArray, String>>()
        for (id in ids) {
            val file = load(id) ?: return null
            if ((file.type == LOCKED_IMAGE) == locked) continue
            next += if (locked) {
                Triple(id, LockedNotes.encryptImage(key, file.bytes), LOCKED_IMAGE)
            } else {
                val plain = try {
                    LockedNotes.decryptImage(key, file.bytes)
                } catch (_: LockedException) {
                    return null
                }
                Triple(id, plain, sniff(plain) ?: return null)
            }
        }
        // Only once every image is in hand: a refusal leaves nothing half-done.
        return next.associate { (id, bytes, type) -> id to add(bytes, type) }
    }

    suspend fun pending(): List<PendingImage> = db.read { sql ->
        sql.query("SELECT id, content_type FROM pending_images ORDER BY created_at, id") {
            PendingImage(it.getText(0), it.getText(1))
        }
    }

    /**
     * Uploads every pending image, oldest first. Called by sync before any
     * note operation goes out, so no device sees a reference before its bytes
     * exist. Stops at the first failure that a retry might fix.
     */
    override suspend fun upload(client: SyncApi): ApiResult<Unit> {
        for (image in pending()) {
            val file = File(pendingDir, fileName(image.id, image.type))
            val bytes = withContext(io) { if (file.isFile) file.readBytes() else null }
            if (bytes == null) {
                forget(image.id)
                continue
            }
            when (val r = client.putImage(image.id, bytes, image.type)) {
                is ApiResult.Ok -> uploaded(image)

                // Refused for good (bytes not matching the id, too large, not an
                // image): retrying won't change that, and holding it back would
                // stop every note from syncing. Its notes show it as missing.
                is ApiResult.Rejected if r.status in PendingImages.REFUSED -> uploaded(image)

                else -> return r
            }
        }
        return ApiResult.Ok(Unit)
    }

    private suspend fun uploaded(image: PendingImage) {
        withContext(io) {
            val from = File(pendingDir, fileName(image.id, image.type))
            val to = File(cacheDir, fileName(image.id, image.type))
            cacheDir.mkdirs()
            if (!from.renameTo(to)) {
                from.copyTo(to, overwrite = true)
                from.delete()
            }
        }
        forget(image.id)
    }

    private suspend fun forget(id: String) = db.write { sql -> sql.exec("DELETE FROM pending_images WHERE id = ?", id) }

    /** Removes every image from this device (signing out). */
    suspend fun wipe() = withContext(io) {
        pendingDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    private fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!tmp.renameTo(target)) error("Couldn't store image ${target.name}")
    }

    companion object {
        /** The image type of decrypted bytes, from their magic numbers. */
        fun sniff(b: ByteArray): String? {
            fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xff else -1
            fun ascii(from: Int, to: Int) = if (b.size >= to) String(b, from, to - from, Charsets.ISO_8859_1) else ""
            return when {
                at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> "image/jpeg"
                at(0) == 0x89 && ascii(1, 4) == "PNG" -> "image/png"
                ascii(0, 4) == "GIF8" -> "image/gif"
                ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP" -> "image/webp"
                else -> null
            }
        }

        /** File extension by type: the type is kept in the name, so a cached file needs no row. */
        private val TYPES = listOf(
            "jpg" to "image/jpeg",
            "png" to "image/png",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "locked" to LOCKED_IMAGE
        )

        private fun fileName(id: String, type: String): String {
            val ext = TYPES.firstOrNull { it.second == type }?.first ?: error("Not an image type: $type")
            return "$id.$ext"
        }
    }
}
