package notes.app.ui.themes

import androidx.compose.ui.graphics.Color
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
    fun `Amber draws what the app drew before themes`() {
        for ((dark, before) in listOf(false to amberLight, true to amberDark)) {
            val tokens = Tokens.of(Themes.amber, dark)
            val now = mapOf(
                "bg" to tokens.bg,
                "ink" to tokens.ink,
                "ink2" to tokens.ink2,
                "ink3" to tokens.ink3,
                "accent" to tokens.accent,
                "accentDeep" to tokens.accentDeep,
                "onAccent" to tokens.onAccent,
                "tint" to tokens.tint,
                "link" to tokens.link,
                "danger" to tokens.danger,
                "solid" to tokens.solid,
                "onSolid" to tokens.onSolid,
                "hair" to tokens.hair,
                "hairStrong" to tokens.hairStrong,
                "hover" to tokens.hover,
                "on" to tokens.on,
                "glassHi" to tokens.glassHi,
                "edge" to tokens.edge,
                "field" to tokens.field,
                "chip" to tokens.chip,
                "scrim" to tokens.scrim,
                "dangerBg" to tokens.dangerBg,
                "good" to tokens.good,
                "goodBg" to tokens.goodBg,
                "errBg" to tokens.errBg,
                "errEdge" to tokens.errEdge,
                "errInk" to tokens.errInk,
                "warnBg" to tokens.warnBg,
                "warnEdge" to tokens.warnEdge,
                "warnInk" to tokens.warnInk,
                "focus" to tokens.focus
            )
            for ((name, value) in before) {
                val current = Color(value)
                val generated = now.getValue(name)
                // Within one step of 255: 0.7 alpha is 178.5, which the hand-made value rounded down.
                val apart = listOf(
                    current.red - generated.red,
                    current.green - generated.green,
                    current.blue - generated.blue,
                    current.alpha - generated.alpha
                ).maxOf { abs(it) }
                assertTrue(apart <= 1.01f / 255, "$name, dark = $dark: $current vs $generated")
            }
            val glow = if (dark) 0xFF624821 else 0xFFF4D9A8
            assertEquals(Color(glow), tokens.blob1, "glow, dark = $dark")
            assertEquals(if (dark) 0.2f else 0.18f, tokens.blobOpacity, 0.002f, "glow strength, dark = $dark")
        }
    }

    // The hand-kept colours Android drew with before shared/themes, kept to hold Amber to them.
    private val amberLight = listOf<Pair<String, Long>>(
        "bg" to 0xFFF5F3F1,
        "ink" to 0xFF211F1C,
        "ink2" to 0xFF504C4A,
        "ink3" to 0xFF666260,
        "accent" to 0xFFEFB146,
        "accentDeep" to 0xFFDD9314,
        "onAccent" to 0xFF1F1915,
        "tint" to 0xFFBE7200,
        "link" to 0xFF874E00,
        "danger" to 0xFFAC312C,
        "solid" to 0xFF24211F,
        "onSolid" to 0xFFF9F8F6,
        "hair" to 0x172D2823,
        "hairStrong" to 0x292D2823,
        "hover" to 0x122D2823,
        "on" to 0x1C2D2823,
        "glassHi" to 0xE0FEFDFC,
        "edge" to 0xD9FFFFFF,
        "field" to 0xC7FFFFFF,
        "chip" to 0x9EFFFFFF,
        "scrim" to 0x6BECEAE6,
        "dangerBg" to 0x1AD24D45,
        "good" to 0xFF0E492A,
        "goodBg" to 0xBFC0EACD,
        "errBg" to 0xD9FFE8E3,
        "errEdge" to 0xFFFDCAC0,
        "errInk" to 0xFF782B21,
        "warnBg" to 0xE0FFEAD3,
        "warnEdge" to 0xFFF6CBA5,
        "warnInk" to 0xFF623512,
        "focus" to 0x8CDD9314
    )

    private val amberDark = listOf<Pair<String, Long>>(
        "bg" to 0xFF121416,
        "ink" to 0xFFE9EBEE,
        "ink2" to 0xFFB2B4B7,
        "ink3" to 0xFF939598,
        "accent" to 0xFFEFB146,
        "accentDeep" to 0xFFDD9314,
        "onAccent" to 0xFF1F1915,
        "tint" to 0xFFF0BA59,
        "link" to 0xFFEFBA64,
        "danger" to 0xFFFB8276,
        "solid" to 0xFFE6E8EA,
        "onSolid" to 0xFF141618,
        "hair" to 0x14FFFFFF,
        "hairStrong" to 0x29FFFFFF,
        "hover" to 0x12FFFFFF,
        "on" to 0x21FFFFFF,
        "glassHi" to 0xE02D2F31,
        "edge" to 0x14FFFFFF,
        "field" to 0x0FFFFFFF,
        "chip" to 0x12FFFFFF,
        "scrim" to 0x800B0D0E,
        "dangerBg" to 0x29D24D45,
        "good" to 0xFF9EE0B4,
        "goodBg" to 0x80245337,
        "errBg" to 0xB24D2620,
        "errEdge" to 0xFF723B32,
        "errInk" to 0xFFFDCAC0,
        "warnBg" to 0xBF482F1A,
        "warnEdge" to 0xFF724B2B,
        "warnInk" to 0xFFF8D8B4,
        "focus" to 0x8CDD9314
    )
}
