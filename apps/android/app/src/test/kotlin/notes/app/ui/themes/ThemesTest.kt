package notes.app.ui.themes

import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import notes.app.ui.Tokens
import org.junit.Test
import org.junit.runner.RunWith

// Themes.generated.kt comes from shared/themes; shared/themes/test checks the definitions.
@RunWith(AndroidJUnit4::class)
class ThemesTest {
    @Test
    fun `every theme's fonts load`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (theme in Themes.all) {
            for (face in theme.title.typeface.faces + theme.body.faces) {
                val font = ResourcesCompat.getFont(context, face.resource)
                assertNotNull(font, "${theme.id}: ${face.resource}")
            }
        }
    }

    @Test
    fun `an id no theme has falls back to the default`() {
        assertEquals(Themes.fallback, Themes.theme("gone"))
        assertEquals(Themes.all.size, Themes.all.map { it.id }.toSet().size)
    }

    @Test
    fun `Amber is the palette the app draws today`() {
        val amber = Themes.amber.colors
        for ((dark, tokens) in listOf(false to Tokens.Light, true to Tokens.Dark)) {
            // Every role both have; the backdrop glow is kept at a different strength on purpose.
            val pairs = listOf(
                "bg" to (tokens.bg to amber.bg),
                "ink" to (tokens.ink to amber.ink),
                "ink2" to (tokens.ink2 to amber.ink2),
                "ink3" to (tokens.ink3 to amber.ink3),
                "accent" to (tokens.accent to amber.accent),
                "accentDeep" to (tokens.accentDeep to amber.accentDeep),
                "onAccent" to (tokens.onAccent to amber.onAccent),
                "tint" to (tokens.tint to amber.tint),
                "link" to (tokens.link to amber.link),
                "danger" to (tokens.danger to amber.danger),
                "solid" to (tokens.solid to amber.solid),
                "onSolid" to (tokens.onSolid to amber.onSolid),
                "hair" to (tokens.hair to amber.hair),
                "hairStrong" to (tokens.hairStrong to amber.hairStrong),
                "hover" to (tokens.hover to amber.hover),
                "on" to (tokens.on to amber.on),
                "glassHi" to (tokens.glassHi to amber.glassHi),
                "edge" to (tokens.edge to amber.edge),
                "field" to (tokens.field to amber.field),
                "chip" to (tokens.chip to amber.chip),
                "scrim" to (tokens.scrim to amber.scrim),
                "dangerBg" to (tokens.dangerBg to amber.dangerBg),
                "good" to (tokens.good to amber.good),
                "goodBg" to (tokens.goodBg to amber.goodBg),
                "errBg" to (tokens.errBg to amber.errBg),
                "errEdge" to (tokens.errEdge to amber.errEdge),
                "errInk" to (tokens.errInk to amber.errInk),
                "warnBg" to (tokens.warnBg to amber.warnBg),
                "warnEdge" to (tokens.warnEdge to amber.warnEdge),
                "warnInk" to (tokens.warnInk to amber.warnInk),
                "focus" to (tokens.focus to amber.focus)
            )
            for ((name, pair) in pairs) {
                val (current, generated) = pair.first to pair.second.resolve(dark)
                // Within one step of 255: 0.7 alpha is 178.5, which the hand-made value rounded down.
                val apart = listOf(
                    current.red - generated.red,
                    current.green - generated.green,
                    current.blue - generated.blue,
                    current.alpha - generated.alpha
                ).maxOf { abs(it) }
                assertTrue(apart <= 1.01f / 255, "$name, dark = $dark: $current vs $generated")
            }
        }
    }
}
