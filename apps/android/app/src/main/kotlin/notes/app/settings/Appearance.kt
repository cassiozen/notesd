package notes.app.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import notes.app.ui.CurrentTheme
import notes.app.ui.themes.Themes

enum class Mode(val label: String, val nightMode: Int) {
    System("System", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    Light("Light", AppCompatDelegate.MODE_NIGHT_NO),
    Dark("Dark", AppCompatDelegate.MODE_NIGHT_YES)
}

/**
 * Appearance, kept on this device: the mode, applied as AppCompat's night mode, so the
 * configuration itself changes and Compose, resources and the editor's View-drawn spans all follow
 * it; and the theme (shared/themes), which [CurrentTheme] holds for the UI to read as it's built.
 * Picking another theme recreates the activity (MainActivity), as a mode change does.
 */
class Appearance(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    // "theme" held the mode before themes; the key stays, so a picked mode survives the update.
    private val _mode = MutableStateFlow(
        prefs.getString(MODE_KEY, null)?.let { saved -> Mode.entries.firstOrNull { it.name == saved } } ?: Mode.System
    )
    val mode: StateFlow<Mode> = _mode

    private val _theme = MutableStateFlow(Themes.theme(prefs.getString(THEME_KEY, null) ?: Themes.fallback.id))
    val theme: StateFlow<notes.app.ui.themes.NotesTheme> = _theme

    init {
        CurrentTheme.theme = _theme.value
        AppCompatDelegate.setDefaultNightMode(_mode.value.nightMode)
    }

    fun set(mode: Mode) {
        prefs.edit { putString(MODE_KEY, mode.name) }
        _mode.value = mode
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    fun set(theme: notes.app.ui.themes.NotesTheme) {
        if (theme.id == _theme.value.id) return
        prefs.edit { putString(THEME_KEY, theme.id) }
        CurrentTheme.theme = theme
        _theme.value = theme
    }

    private companion object {
        const val MODE_KEY = "theme"
        const val THEME_KEY = "theme_id"
    }
}
