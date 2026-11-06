package notes.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import notes.app.ui.themes.NotesTheme as ThemeDefinition
import notes.app.ui.themes.Themes

val LocalTokens = staticCompositionLocalOf { Tokens.of(Themes.fallback, dark = false) }

/**
 * The theme picked in Settings (settings/Appearance), read as the UI is built: picking another
 * recreates the activity, as changing the mode does, so nothing has to follow it live.
 */
object CurrentTheme {
    @Volatile
    var theme: ThemeDefinition = Themes.fallback
}

/** A corner radius as the theme's style sets corners: every radius scaled by the same amount. */
fun corners(radius: Dp): RoundedCornerShape = RoundedCornerShape(radius * CurrentTheme.theme.shape.radiusScale)

fun corners(topStart: Dp = 0.dp, topEnd: Dp = 0.dp, bottomEnd: Dp = 0.dp, bottomStart: Dp = 0.dp): RoundedCornerShape {
    val s = CurrentTheme.theme.shape.radiusScale
    return RoundedCornerShape(topStart * s, topEnd * s, bottomEnd * s, bottomStart * s)
}

/** Material 3 driven by the picked theme's tokens: its accent, paper and ink, no dynamic colour. */
@Composable
fun NotesTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val t = Tokens.of(CurrentTheme.theme, dark)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = t.accentDeep,
        onPrimary = t.onAccent,
        primaryContainer = t.accent,
        onPrimaryContainer = t.onAccent,
        secondary = t.ink2,
        onSecondary = t.bg,
        background = t.bg,
        onBackground = t.ink,
        surface = t.bg,
        onSurface = t.ink,
        surfaceVariant = t.hover,
        onSurfaceVariant = t.ink2,
        surfaceContainer = t.bg,
        surfaceContainerHigh = t.bg,
        surfaceContainerHighest = t.bg,
        surfaceContainerLow = t.bg,
        inverseSurface = t.solid,
        inverseOnSurface = t.onSolid,
        outline = t.hairStrong,
        outlineVariant = t.hair,
        error = t.danger,
        scrim = t.scrim
    )
    val shapes = Shapes(
        extraSmall = corners(10.dp),
        small = corners(12.dp),
        medium = corners(16.dp),
        large = corners(24.dp),
        extraLarge = corners(28.dp)
    )
    CompositionLocalProvider(
        LocalTokens provides t,
        LocalReduceMotion provides systemReducesMotion(),
        LocalTextSelectionColors provides TextSelectionColors(t.accentDeep, t.accent.copy(alpha = 0.4f))
    ) {
        MaterialTheme(colorScheme = colors, typography = Type.material, shapes = shapes, content = content)
    }
}
