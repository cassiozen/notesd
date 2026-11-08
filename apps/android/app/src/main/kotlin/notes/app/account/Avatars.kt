package notes.app.account

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import notes.core.api.ApiClient
import notes.core.api.ApiResult

/**
 * Profile photos (architecture §10), in files so they show offline: one per account, named for its version, so a
 * new photo replaces the old and a stale one is never shown.
 */
class Avatars(private val dir: File, private val api: () -> ApiClient?) {
    private val decoded = ConcurrentHashMap<String, ImageBitmap>()

    // Bumped by wipe: a download that finishes afterwards mustn't bring a deleted photo back.
    private val generation = AtomicInteger()

    /** Already in memory: shown in the first frame, without a flash of the initial. */
    fun cached(userId: Long, version: String): ImageBitmap? = decoded[key(userId, version)]

    /** Null without a photo, or while one can't be downloaded. */
    suspend fun load(userId: Long, version: String): ImageBitmap? {
        if (userId == 0L || version.isEmpty()) return null
        cached(userId, version)?.let { return it }
        val gen = generation.get()
        val file = File(dir, "${key(userId, version)}.jpg")
        withContext(Dispatchers.IO) { runCatching { file.takeIf { it.exists() }?.readBytes() }.getOrNull() }
            ?.let { bytes -> decode(bytes)?.let { return keep(gen, userId, version, it) } }
        val bytes = (api()?.avatar(userId, version) as? ApiResult.Ok)?.value ?: return null
        // Not a picture (a captive portal's page, say): not kept, so it's asked for again next time.
        val image = decode(bytes) ?: return null
        if (generation.get() == gen) save(userId, version, bytes)
        return keep(gen, userId, version, image)
    }

    /** A photo just set here, so it shows at once. */
    suspend fun put(userId: Long, version: String, bytes: ByteArray) {
        val gen = generation.get()
        val image = decode(bytes) ?: return
        save(userId, version, bytes)
        keep(gen, userId, version, image)
    }

    /** [userId]'s photo was removed. */
    suspend fun forget(userId: Long) {
        decoded.keys.removeIf { it.startsWith("$userId-") }
        withContext(Dispatchers.IO) { dir.listFiles { f -> f.name.startsWith("$userId-") }?.forEach { it.delete() } }
    }

    suspend fun wipe() {
        generation.incrementAndGet()
        decoded.clear()
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
    }

    private fun keep(gen: Int, userId: Long, version: String, image: ImageBitmap): ImageBitmap {
        if (generation.get() == gen) {
            decoded.keys.removeIf { it.startsWith("$userId-") }
            decoded[key(userId, version)] = image
        }
        return image
    }

    private suspend fun decode(bytes: ByteArray) = withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    /** Best effort: a full disk only means downloading it again. */
    private suspend fun save(userId: Long, version: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            dir.listFiles { f -> f.name.startsWith("$userId-") }?.forEach { it.delete() }
            val tmp = File(dir, "${key(userId, version)}.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(File(dir, "${key(userId, version)}.jpg"))
        } catch (_: IOException) {
        }
    }

    private fun key(userId: Long, version: String) = "$userId-$version"
}
