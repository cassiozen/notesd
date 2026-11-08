package notes.app.account

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

    /** Already in memory: shown in the first frame, without a flash of the initial. */
    fun cached(userId: Long, version: String): ImageBitmap? = decoded[key(userId, version)]

    /** Null without a photo, or while one can't be downloaded. */
    suspend fun load(userId: Long, version: String): ImageBitmap? {
        if (userId == 0L || version.isEmpty()) return null
        cached(userId, version)?.let { return it }
        val file = File(dir, "${key(userId, version)}.jpg")
        val bytes = withContext(Dispatchers.IO) { file.takeIf { it.exists() }?.readBytes() }
            ?: (api()?.avatar(userId, version) as? ApiResult.Ok)?.value?.also { save(userId, version, it) }
            ?: return null
        return decode(userId, version, bytes)
    }

    /** A photo just set here, so it shows at once. */
    suspend fun put(userId: Long, version: String, bytes: ByteArray) {
        save(userId, version, bytes)
        decode(userId, version, bytes)
    }

    fun wipe() {
        decoded.clear()
        dir.deleteRecursively()
    }

    private suspend fun decode(userId: Long, version: String, bytes: ByteArray) = withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also {
            decoded[key(userId, version)] = it
        }
    }

    private suspend fun save(userId: Long, version: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith("$userId-") }?.forEach { it.delete() }
        val tmp = File(dir, "${key(userId, version)}.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(File(dir, "${key(userId, version)}.jpg"))
    }

    private fun key(userId: Long, version: String) = "$userId-$version"
}
