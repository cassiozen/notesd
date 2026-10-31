package notes.core.sync

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A real notesd (built by the buildNotesd Gradle task) on a free
 * port with an empty data dir, its owner account already created. Restarts
 * keep the port, so clients keep their URL.
 */
class TestServer(private val resendUrl: String? = null) : AutoCloseable {
    val dataDir: File = Files.createTempDirectory("notesd").toFile()
    private var process: Process? = null
    private var listen = "127.0.0.1:0"
    lateinit var url: HttpUrl
        private set

    init {
        start()
        runBlocking {
            check(ApiClient(url).setup(USERNAME, PASSWORD) is ApiResult.Ok) { "owner setup failed" }
        }
    }

    private fun start() {
        val bin = System.getProperty("notes.serverBin") ?: error("notes.serverBin not set: run through Gradle")
        val args = listOf(bin, "--data-dir", dataDir.path, "--listen", listen) +
            (resendUrl?.let { listOf("--resend-url", it) } ?: emptyList())
        val p = ProcessBuilder(args)
            .redirectErrorStream(true)
            // An exported NOTESD_OWNER_* would create the owner at startup, before init can.
            .apply { environment().keys.removeAll { it.startsWith("NOTESD_OWNER_") } }
            .start()
        process = p
        val reader = p.inputStream.bufferedReader()
        val log = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: error("notesd exited:\n$log")
            log.appendLine(line)
            val addr = Regex("listen=(\\S+)").find(line)?.groupValues?.get(1) ?: continue
            listen = addr
            url = "http://$addr/".toHttpUrl()
            break
        }
        // Keep draining the log so the server never blocks on a full pipe.
        Thread { reader.useLines { lines -> lines.forEach { } } }.apply { isDaemon = true }.start()
    }

    fun stop() {
        process?.let {
            it.destroy()
            it.waitFor(10, TimeUnit.SECONDS)
        }
        process = null
    }

    fun restart() {
        stop()
        start()
    }

    /** A copy of the data dir, for restoring later as if from a backup. Stop the server first. */
    fun snapshot(): File {
        val copy = Files.createTempDirectory("notes-backup").toFile()
        dataDir.copyRecursively(copy, overwrite = true)
        return copy
    }

    fun restore(backup: File) {
        dataDir.deleteRecursively()
        backup.copyRecursively(dataDir, overwrite = true)
    }

    override fun close() {
        stop()
        dataDir.deleteRecursively()
    }

    companion object {
        const val USERNAME = "owner"
        const val PASSWORD = "correct horse"
    }
}
