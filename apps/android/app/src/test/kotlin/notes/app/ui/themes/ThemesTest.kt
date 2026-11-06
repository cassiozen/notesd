package notes.app.ui.themes

import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
            assertEquals(tokens.bg, amber.bg.resolve(dark))
            assertEquals(tokens.ink, amber.ink.resolve(dark))
            assertEquals(tokens.ink2, amber.ink2.resolve(dark))
            assertEquals(tokens.accent, amber.accent.resolve(dark))
            assertEquals(tokens.link, amber.link.resolve(dark))
            assertEquals(tokens.hair, amber.hair.resolve(dark))
            assertEquals(tokens.glassHi, amber.glassHi.resolve(dark))
        }
    }
}
