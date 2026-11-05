package notes.app

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/**
 * Ends the graph after its activity has closed: its coroutines cancelled, then the database closed
 * once the statement under way finishes. Closed under one (a save started as the screen closed), the
 * write fails with "connection is closed". Off Main, like [runOffMain], with the main looper kept
 * going: the write's lock is released back on Main.
 */
fun AppGraph.shutDown() {
    scope.cancel()
    val closed = CoroutineScope(Dispatchers.IO).async { database.closeWhenIdle() }
    val looper = shadowOf(Looper.getMainLooper())
    val deadline = System.currentTimeMillis() + 5_000
    while (!closed.isCompleted) {
        check(System.currentTimeMillis() < deadline) { "the database stayed busy" }
        looper.idle()
        Thread.sleep(1)
    }
    runBlocking { closed.await() }
}
