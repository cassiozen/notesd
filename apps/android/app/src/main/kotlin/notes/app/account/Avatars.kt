package notes.app.account

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import notes.core.api.ApiClient
import notes.core.api.ApiResult

/**
 * Profile photos (architecture §10), in files so they show offline: one per account, named for its version, so a
 * new photo replaces the old and a stale one is never shown.
 */
class Avatars(private val dir: File, private val api: () -> ApiClient?) {
    /** By account: its latest version only, and as many as fit in [MEMORY_BYTES]. */
    private val decoded = object : LruCache<Long, Pair<String, ImageBitmap>>(MEMORY_BYTES) {
        override fun sizeOf(key: Long, value: Pair<String, ImageBitmap>) = value.second.asAndroidBitmap().byteCount
    }

    /** Disk and memory change under it, so a slow download can't land after a newer photo or a wipe. */
    private val lock = Mutex()

    // Moved on by wipe, for everyone, and by put and forget, for one account: what a download in flight
    // finds changed when it lands is no longer wanted.
    private var generation = 0L
    private val changed = HashMap<Long, Long>()

    /** Already in memory: shown in the first frame, without a flash of the initial. */
    fun cached(userId: Long, version: String): ImageBitmap? =
        decoded.get(userId)?.takeIf { it.first == version }?.second

    /** Null without a photo, or while one can't be downloaded. */
    suspend fun load(userId: Long, version: String): ImageBitmap? {
        if (userId == 0L || version.isEmpty()) return null
        cached(userId, version)?.let { return it }
        val asOf = lock.withLock { stamp(userId) }
        val file = File(dir, "${key(userId, version)}.jpg")
        withContext(Dispatchers.IO) { runCatching { file.takeIf { it.exists() }?.readBytes() }.getOrNull() }
            ?.let { bytes -> decode(bytes)?.let { return keep(asOf, userId, version, it, null) } }
        val bytes = (api()?.avatar(userId, version) as? ApiResult.Ok)?.value ?: return null
        // Not a picture (a captive portal's page, say): not kept, so it's asked for again next time.
        val image = decode(bytes) ?: return null
        return keep(asOf, userId, version, image, bytes)
    }

    /** A photo just set here, so it shows at once. */
    suspend fun put(userId: Long, version: String, bytes: ByteArray) {
        val image = decode(bytes) ?: return
        val asOf = lock.withLock {
            changed[userId] = (changed[userId] ?: 0) + 1
            stamp(userId)
        }
        keep(asOf, userId, version, image, bytes)
    }

    /** [userId]'s photo was removed. */
    suspend fun forget(userId: Long) = lock.withLock {
        changed[userId] = (changed[userId] ?: 0) + 1
        decoded.remove(userId)
        withContext(Dispatchers.IO) { deleteFiles(userId) }
    }

    suspend fun wipe() = lock.withLock {
        generation++
        decoded.evictAll()
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
    }

    private fun stamp(userId: Long) = generation to (changed[userId] ?: 0)

    /** [image], and [bytes] on disk if given, unless something newer for [userId] came since [asOf]. */
    private suspend fun keep(
        asOf: Pair<Long, Long>,
        userId: Long,
        version: String,
        image: ImageBitmap,
        bytes: ByteArray?
    ): ImageBitmap = lock.withLock {
        if (stamp(userId) != asOf) return@withLock image
        decoded.put(userId, version to image)
        if (bytes != null) withContext(Dispatchers.IO) { save(userId, version, bytes) }
        image
    }

    private suspend fun decode(bytes: ByteArray) = withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    /** Best effort: a full disk only means downloading it again. */
    private fun save(userId: Long, version: String, bytes: ByteArray) {
        try {
            dir.mkdirs()
            deleteFiles(userId)
            val tmp = File(dir, "${key(userId, version)}.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(File(dir, "${key(userId, version)}.jpg"))
        } catch (_: IOException) {
        }
    }

    private fun deleteFiles(userId: Long) {
        dir.listFiles { f -> f.name.startsWith("$userId-") }?.forEach { it.delete() }
    }

    private fun key(userId: Long, version: String) = "$userId-$version"

    private companion object {
        const val MEMORY_BYTES = 16 shl 20
    }
}
