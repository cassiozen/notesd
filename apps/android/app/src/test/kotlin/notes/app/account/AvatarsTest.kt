package notes.app.account

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import notes.core.api.ApiClient
import notes.core.api.Credentials
import notes.core.api.Tokens
import okio.Buffer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarsTest {
    private val server = MockWebServer()
    private val dir: File = Files.createTempDirectory("avatars").toFile()
    private val credentials = object : Credentials {
        override suspend fun tokens() = Tokens("a", Long.MAX_VALUE, "r")
        override suspend fun refreshed(tokens: Tokens) = Unit
        override suspend fun revoked() = Unit
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private val jpeg: ByteArray = ByteArrayOutputStream().also {
        createBitmap(8, 8).compress(Bitmap.CompressFormat.JPEG, 90, it)
    }.toByteArray()

    private fun serve(body: ByteArray, gate: CountDownLatch? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                gate?.await(5, TimeUnit.SECONDS)
                return MockResponse.Builder().body(Buffer().write(body)).build()
            }
        }
        server.start()
    }

    private fun avatars() = Avatars(dir) { ApiClient(server.url("/"), credentials) }

    private fun files() = dir.list()?.sorted().orEmpty()

    @Test
    fun `a downloaded photo is kept, and anything else isn't`() = runBlocking {
        serve(jpeg)
        val avatars = avatars()
        assertNotNull(avatars.load(1, "v1"))
        assertNotNull(avatars.cached(1, "v1"))
        assertEquals(listOf("1-v1.jpg"), files())
        assertNull(avatars.cached(1, "v2"))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().body("<html>Wi-Fi</html>").build()
        }
        assertNull(avatars.load(2, "v1"))
        assertEquals(listOf("1-v1.jpg"), files())
    }

    @Test
    fun `a download that lands after the photo was removed, or everything wiped, isn't kept`() = runBlocking {
        val gate = CountDownLatch(1)
        serve(jpeg, gate)
        val avatars = avatars()
        val late = async(Dispatchers.IO) { avatars.load(1, "v1") }
        while (server.requestCount == 0) yield()
        avatars.forget(1)
        gate.countDown()
        late.await()
        assertNull(avatars.cached(1, "v1"))
        assertEquals(emptyList(), files())

        val again = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                again.await(5, TimeUnit.SECONDS)
                return MockResponse.Builder().body(Buffer().write(jpeg)).build()
            }
        }
        val wiped = async(Dispatchers.IO) { avatars.load(2, "v1") }
        while (server.requestCount < 2) yield()
        avatars.wipe()
        again.countDown()
        wiped.await()
        assertNull(avatars.cached(2, "v1"))
        assertEquals(false, dir.exists())
    }
}
