package notes.app

import android.app.Application

open class NotesApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = createGraph()
        startSync()
    }

    /** Tests drive sync themselves, against their own servers. */
    protected open fun startSync() {
        // Account first: the first sync tells this account's changes apart by its id.
        graph.watchAccount()
        graph.scheduler.start()
        graph.connectivity.start(this)
        graph.watchForeground()
        graph.agentLink.start()
        graph.watchKeyring()
        graph.watchSpaces()
    }

    /** Tests swap in a graph without the Android Keystore, which Robolectric lacks. */
    protected open fun createGraph() = AppGraph(this)
}
