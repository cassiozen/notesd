package notes.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf

/** A page's sections with changes not yet saved, each by what it names them ("the agent"). */
class UnsavedSections {
    internal val sections = SnapshotStateMap<Any, String>()

    /** What's unsaved, in the words each section gave. */
    val unsaved: List<String> get() = sections.values.toList()
}

val LocalUnsaved = staticCompositionLocalOf<UnsavedSections?> { null }

/** Tells the page [what] has changes not yet saved, while [unsaved]: going back asks first. */
@Composable
fun Unsaved(what: String, unsaved: Boolean) {
    val page = LocalUnsaved.current ?: return
    val key = remember { Any() }
    DisposableEffect(page, what, unsaved) {
        if (unsaved) page.sections[key] = what
        onDispose { page.sections.remove(key) }
    }
}
