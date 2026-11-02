package notes.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import notes.app.account.Account
import notes.app.account.AccountStore
import notes.core.db.NoteStore
import notes.core.sync.SyncEngine
import notes.core.sync.SyncOutcome

/**
 * When to sync (§4.7). In the foreground: on start and return, 5 s after the
 * last local edit, when the network comes back, and right after signing in,
 * retrying failures with backoff (2 s doubling to 5 min, with jitter). In the
 * background: WorkManager, every 15 min and once after leaving with changes
 * unsent. Correctness never depends on any of these running.
 */
class SyncScheduler(
    private val context: Context,
    private val engine: SyncEngine,
    private val store: NoteStore,
    private val accounts: AccountStore,
    private val scope: CoroutineScope
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var foreground = false

    fun request() {
        requests.trySend(Unit)
    }

    fun start() {
        scope.launch {
            // Edits older versions dropped go back in line before the first cycle; that cycle does it if this fails.
            runCatching { engine.open() }
            loop()
        }
        scope.launch {
            store.localEdits.collectLatest {
                delay(AFTER_EDIT_MS)
                request()
            }
        }
        // App start with an account, and every sign-in.
        scope.launch {
            accounts.account.map { it is Account.SignedIn }.distinctUntilChanged().filter { it }.collect { request() }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    foreground = true
                    request()
                }

                override fun onStop(owner: LifecycleOwner) {
                    foreground = false
                    scope.launch { if (store.pendingNotes() > 0) enqueueOnce() }
                }
            }
        )
        context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = request()
            }
        )
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        )
    }

    private suspend fun loop() {
        var backoff = FIRST_RETRY_MS
        while (true) {
            requests.receive()
            while (true) {
                val outcome = engine.sync()
                // Only an updated app helps, and that's a new process: nothing more until then.
                if (outcome == SyncOutcome.UpgradeRequired) return
                if (outcome != SyncOutcome.Offline && outcome != SyncOutcome.Failed) {
                    backoff = FIRST_RETRY_MS
                    break
                }
                // In the background WorkManager retries; here, wait, unless something asks sooner.
                if (!foreground) break
                val wait = backoff + Random.nextLong(backoff / 2)
                backoff = minOf(backoff * 2, MAX_RETRY_MS)
                withTimeoutOrNull(wait) { requests.receive() }
            }
        }
    }

    private fun enqueueOnce() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            AFTER_EDIT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build()
        )
    }

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private companion object {
        const val PERIODIC = "sync"
        const val AFTER_EDIT = "sync-after-edit"
        const val AFTER_EDIT_MS = 5_000L
        const val FIRST_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 300_000L
    }
}
