package notes.app.ui

import androidx.compose.ui.graphics.Color
import notes.app.ui.themes.NotesTheme as ThemeDefinition
import notes.app.ui.themes.ThemeColor

/**
 * The colours the UI draws with: the picked theme (shared/themes, generated into
 * ui/themes/Themes.generated.kt) in one mode. [theme] reaches the rest of it: shapes, fonts and
 * the editor's details.
 */
data class Tokens(
    val bg: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val accentDeep: Color,
    val onAccent: Color,
    val tint: Color,
    val link: Color,
    val danger: Color,
    val solid: Color,
    val onSolid: Color,
    val hair: Color,
    val hairStrong: Color,
    val hover: Color,
    val on: Color,
    val glass: Color,
    val glassHi: Color,
    val edge: Color,
    val field: Color,
    val chip: Color,
    val scrim: Color,
    val dangerBg: Color,
    val good: Color,
    val goodBg: Color,
    val errBg: Color,
    val errEdge: Color,
    val errInk: Color,
    val warnBg: Color,
    val warnEdge: Color,
    val warnInk: Color,
    val focus: Color,
    val blob1: Color,
    val blobOpacity: Float,
    val dark: Boolean,
    val theme: ThemeDefinition
) {
    // Password strength (apps/web/src/ui/password-meter.module.css), the same in every theme.
    val meterWeak get() = Color(0xFFE57255) // oklch(0.68 0.15 35)
    val meterFair get() = Color(0xFFE4A339) // oklch(0.76 0.14 75)
    val meterStrong get() = Color(0xFF5DAD70) // oklch(0.68 0.12 150)

    companion object {
        fun of(theme: ThemeDefinition, dark: Boolean): Tokens {
            val c = theme.colors
            fun ThemeColor.v() = resolve(dark)
            val glassHi = c.glassHi.v()
            // The glow behind the window: its colour, and how strongly it shows (none for a style without one).
            val wash = c.wash?.v()
            return Tokens(
                bg = c.bg.v(),
                ink = c.ink.v(),
                ink2 = c.ink2.v(),
                ink3 = c.ink3.v(),
                accent = c.accent.v(),
                accentDeep = c.accentDeep.v(),
                onAccent = c.onAccent.v(),
                tint = c.tint.v(),
                link = c.link.v(),
                danger = c.danger.v(),
                solid = c.solid.v(),
                onSolid = c.onSolid.v(),
                hair = c.hair.v(),
                hairStrong = c.hairStrong.v(),
                hover = c.hover.v(),
                on = c.on.v(),
                // The quieter panel (onboarding's card): the floating material, a little more see-through.
                glass = glassHi.copy(alpha = glassHi.alpha * 0.84f),
                glassHi = glassHi,
                edge = c.edge.v(),
                field = c.field.v(),
                chip = c.chip.v(),
                scrim = c.scrim.v(),
                dangerBg = c.dangerBg.v(),
                good = c.good.v(),
                goodBg = c.goodBg.v(),
                errBg = c.errBg.v(),
                errEdge = c.errEdge.v(),
                errInk = c.errInk.v(),
                warnBg = c.warnBg.v(),
                warnEdge = c.warnEdge.v(),
                warnInk = c.warnInk.v(),
                focus = c.focus.v(),
                blob1 = wash?.copy(alpha = 1f) ?: Color.Transparent,
                blobOpacity = wash?.alpha ?: 0f,
                dark = dark,
                theme = theme
            )
        }
    }
}
