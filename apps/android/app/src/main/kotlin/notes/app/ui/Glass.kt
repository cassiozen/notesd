package notes.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** The content glass surfaces on this screen blur; null where nothing scrolls under them. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * A translucent panel over the content behind it, like the web's backdrop-filter
 * surfaces. Real blur on Android 12+; earlier versions get the opaque glass colour.
 * A style without blur (Paper) gets its panel colour as it is: opaque, in that style.
 */
@Composable
fun Modifier.glass(hi: Boolean = true): Modifier {
    val t = LocalTokens.current
    val tint = if (hi) t.glassHi else t.glass
    val state = LocalHaze.current ?: return background(tint)
    val blur = t.theme.blurs.panel ?: return background(tint.compositeOverBg(t))
    return hazeBlur(
        HazeInput.Sources(state),
        HazeBlurStyle {
            // Glass's 18px on the web reads as 22dp here; other styles in proportion.
            blurRadius((blur.radius * 22f / 18f).dp)
            noiseFactor(0f)
            colorEffects(listOf(HazeColorEffect.tint(tint)))
            fallbackColorEffect(HazeColorEffect.tint(t.glassHi))
        }
    )
}

/** A one-pixel hairline along the bottom (or top) edge, the web's border-bottom: 1px solid var(--hair). */
fun Modifier.hairline(color: Color, top: Boolean = false): Modifier = drawBehind {
    val y = if (top) 0f else size.height - 1f
    drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(0f, y), size = size.copy(height = 1f))
}

private fun Modifier.background(color: Color): Modifier = drawBehind { drawRect(color) }

/** Glass is translucent; a sheet over a scrim, or a bar over text with nothing to blur it, needs it solid. */
fun Color.compositeOverBg(t: Tokens): Color {
    val a = alpha
    return Color(
        red = red * a + t.bg.red * (1 - a),
        green = green * a + t.bg.green * (1 - a),
        blue = blue * a + t.bg.blue * (1 - a)
    )
}

/**
 * A pill floating over the note (web: .dock). Solid glass colour: the keyboard
 * slot it sits in has no haze source to blur.
 */
@Composable
fun Modifier.floating(): Modifier {
    val t = LocalTokens.current
    // A capsule in every style, as the web's dock (999px, scaled, is still a capsule).
    val shape = RoundedCornerShape(28.dp)
    // Only where the style gives floating things a shadow (Lumen keeps one, Flush a faint one).
    val lift = if (t.theme.shadows.shadowPop.light.isEmpty()) 0.dp else 12.dp
    return shadow(lift, shape)
        .clip(shape)
        .background(t.glassHi.compositeOverBg(t))
        .border(1.dp, t.edge, shape)
}
