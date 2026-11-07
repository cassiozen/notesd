package notes.app.ui

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import notes.app.notes.SPACE_ICONS
import org.junit.Test

class SpaceThemesTest {
    @Test
    fun `every icon brings a theme of its own, in both modes`() {
        for (icon in SPACE_ICONS) {
            assertEquals(icon.key, SpaceThemes.key(icon.key))
            assertNotEquals(Tokens.Light, Tokens.forSpace(icon.key, dark = false), icon.key)
            assertNotEquals(Tokens.Dark, Tokens.forSpace(icon.key, dark = true), icon.key)
        }
    }

    @Test
    fun `an icon this app doesn't have brings the app icon's`() {
        assertEquals("", SpaceThemes.key("notepad"))
        assertEquals(Tokens.Light, Tokens.forSpace("notepad", dark = false))
    }
}
