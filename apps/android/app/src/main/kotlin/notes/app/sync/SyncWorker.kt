package notes.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import notes.app.NotesApp
import notes.core.sync.SyncOutcome

/**
 * Background sync: the same engine (and lock) the foreground uses. WorkManager owns the retry backoff here;
 * UpgradeRequired isn't retried (the engine answers it without asking the server until the app restarts).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as NotesApp).graph
        return when (graph.sync.sync()) {
            SyncOutcome.Offline, SyncOutcome.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}
