package notes.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import notes.app.notes.NotesNavigation
import notes.app.ui.NotesTheme

class MainActivity : AppCompatActivity() {
    /** "New note" from the launcher shortcut, delivered once to whichever screen is showing. */
    private val newNote = Channel<Unit>(Channel.CONFLATED)

    /** "Email" chosen in a note's share sheet: the note's id. */
    private val emailNote = Channel<String>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation, theme change) must not open another note.
        if (savedInstanceState == null) handle(intent)
        val graph = (application as NotesApp).graph
        setContent {
            val space by graph.spaceTheme.collectAsStateWithLifecycle()
            NotesTheme(space = space) {
                NotesNavigation(graph, newNote.receiveAsFlow(), emailNote.receiveAsFlow())
            }
        }
    }

    /** Any touch or key: locked notes' idle timeout starts over. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        graph().locks.touch()
    }

    /** Off screen (not just rotating): locked notes lock, after the editor's ON_STOP save. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) graph().let { g -> g.scope.launch { g.locks.lock() } }
    }

    private fun graph() = (application as NotesApp).graph

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_NEW_NOTE -> newNote.trySend(Unit)
            ACTION_EMAIL_NOTE -> intent.getStringExtra(EXTRA_NOTE_ID)?.let { emailNote.trySend(it) }
        }
    }

    companion object {
        const val ACTION_NEW_NOTE = "notes.app.action.NEW_NOTE"
        const val ACTION_EMAIL_NOTE = "notes.app.action.EMAIL_NOTE"
        const val EXTRA_NOTE_ID = "notes.app.extra.NOTE_ID"
    }
}
