package notes.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Not static: a theme change eases in over many frames, and only what reads the tokens should redraw.
val LocalTokens = compositionLocalOf { Tokens.Light }

/** The key of the space theme [LocalTokens] are in. */
val LocalSpaceTheme = staticCompositionLocalOf { "" }

/**
 * Material 3 driven by the web app's tokens, in the theme of the space shown
 * ([space], a key from [SpaceThemes.key]): its neutrals throughout, its accent
 * only on the caret, checked tasks, the main button and links. No dynamic colour.
 */
@Composable
fun NotesTheme(dark: Boolean = isSystemInDarkTheme(), space: String = "", content: @Composable () -> Unit) {
    val reduceMotion = systemReducesMotion()
    val t = animated(remember(space, dark) { Tokens.forSpace(space, dark) }, reduceMotion)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        // Material's own controls stay neutral, as the web's do: the accent is kept for a few places.
        primary = t.solid,
        onPrimary = t.onSolid,
        primaryContainer = t.on,
        onPrimaryContainer = t.ink,
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
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp)
    )
    CompositionLocalProvider(
        LocalTokens provides t,
        LocalSpaceTheme provides space,
        LocalReduceMotion provides reduceMotion,
        // With the caret: the handles and selection are where text is being edited.
        LocalTextSelectionColors provides TextSelectionColors(t.accentDeep, t.accent.copy(alpha = 0.4f))
    ) {
        MaterialTheme(colorScheme = colors, typography = NotesTypography, shapes = shapes, content = content)
    }
}

/** [content] in another space's theme, in the mode around it: a preview of the theme an icon brings. */
@Composable
fun SpaceTheme(space: String, content: @Composable () -> Unit) {
    NotesTheme(dark = LocalTokens.current.dark, space = space, content = content)
}

/** [target] with the colours a theme sets easing in from the last theme's, as on the web. */
@Composable
private fun animated(target: Tokens, reduceMotion: Boolean): Tokens {
    val spec: AnimationSpec<Color> = if (reduceMotion) snap() else tween(300)

    @Composable
    fun ease(c: Color): Color {
        val value by animateColorAsState(c, spec, label = "theme")
        return value
    }
    return target.copy(
        bg = ease(target.bg),
        ink = ease(target.ink),
        ink2 = ease(target.ink2),
        ink3 = ease(target.ink3),
        accent = ease(target.accent),
        accentDeep = ease(target.accentDeep),
        onAccent = ease(target.onAccent),
        tint = ease(target.tint),
        link = ease(target.link),
        solid = ease(target.solid),
        onSolid = ease(target.onSolid),
        hair = ease(target.hair),
        hairStrong = ease(target.hairStrong),
        hover = ease(target.hover),
        on = ease(target.on),
        glass = ease(target.glass),
        glassHi = ease(target.glassHi),
        scrim = ease(target.scrim),
        focus = ease(target.focus),
        blob1 = ease(target.blob1),
        tag = ease(target.tag)
    )
}
