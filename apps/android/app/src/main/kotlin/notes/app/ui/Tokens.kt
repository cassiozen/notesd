package notes.app.ui

import androidx.compose.ui.graphics.Color

/**
 * The web app's design tokens (apps/web/src/styles/tokens.css and
 * space-themes.css), converted from oklch to sRGB. The colours a space's theme
 * sets come from [SpaceThemes]; the ones every theme shares are here, the oklch
 * source beside each so the two can be compared when the web palette changes.
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
    /** Behind #tags. */
    val tag: Color,
    val dark: Boolean
) {
    // Password strength (apps/web/src/ui/password-meter.module.css), same in both themes.
    val meterWeak get() = Color(0xFFE57255) // oklch(0.68 0.15 35)
    val meterFair get() = Color(0xFFE4A339) // oklch(0.76 0.14 75)
    val meterStrong get() = Color(0xFF5DAD70) // oklch(0.68 0.12 150)

    companion object {
        /** A space theme's tokens (its key from [SpaceThemes.key]) in light or dark. */
        fun forSpace(key: String, dark: Boolean): Tokens = of(SpaceThemes.colors(key, dark), dark)

        /** The app icon's theme. */
        val Light = forSpace("", dark = false)
        val Dark = forSpace("", dark = true)

        fun of(c: SpaceColors, dark: Boolean) = Tokens(
            bg = c.bg,
            ink = c.ink,
            ink2 = c.ink2,
            ink3 = c.ink3,
            accent = c.accent,
            accentDeep = c.accentDeep,
            onAccent = c.onAccent,
            tint = c.tint,
            link = c.link,
            solid = c.solid,
            onSolid = c.onSolid,
            hair = c.hair,
            hairStrong = c.hairStrong,
            hover = c.hover,
            on = c.on,
            glass = c.glass,
            glassHi = c.glassHi,
            scrim = c.scrim,
            focus = c.focus,
            blob1 = c.blob1,
            tag = c.tag,
            dark = dark,
            // The same in every theme; sources dark, then light.
            danger = if (dark) Color(0xFFFB8276) else Color(0xFFAC312C), // oklch(0.74 0.15 27), oklch(0.5 0.16 27)
            edge = if (dark) Color(0x14FFFFFF) else Color(0xD9FFFFFF), // rgb(255 255 255 / 0.08), / 0.85
            field = if (dark) Color(0x0FFFFFFF) else Color(0xC7FFFFFF), // rgb(255 255 255 / 0.06), / 0.78
            chip = if (dark) Color(0x12FFFFFF) else Color(0x9EFFFFFF), // rgb(255 255 255 / 0.07), / 0.62
            // oklch(0.6 0.17 27 / 0.16), / 0.1
            dangerBg = if (dark) Color(0x29D24D45) else Color(0x1AD24D45),
            good = if (dark) Color(0xFF9EE0B4) else Color(0xFF0E492A), // oklch(0.85 0.09 155), oklch(0.36 0.08 155)
            // oklch(0.4 0.07 155 / 0.5), oklch(0.9 0.06 155 / 0.75)
            goodBg = if (dark) Color(0x80245337) else Color(0xBFC0EACD),
            // oklch(0.32 0.06 30 / 0.7), oklch(0.95 0.03 30 / 0.85)
            errBg = if (dark) Color(0xB24D2620) else Color(0xD9FFE8E3),
            errEdge = if (dark) Color(0xFF723B32) else Color(0xFFFDCAC0), // oklch(0.42 0.08 30), oklch(0.88 0.06 30)
            errInk = if (dark) Color(0xFFFDCAC0) else Color(0xFF782B21), // oklch(0.88 0.06 30), oklch(0.4 0.11 30)
            // oklch(0.33 0.05 60 / 0.75), oklch(0.95 0.04 65 / 0.88)
            warnBg = if (dark) Color(0xBF482F1A) else Color(0xE0FFEAD3),
            warnEdge = if (dark) Color(0xFF724B2B) else Color(0xFFF6CBA5), // oklch(0.45 0.07 60), oklch(0.87 0.07 62)
            warnInk = if (dark) Color(0xFFF8D8B4) else Color(0xFF623512), // oklch(0.9 0.06 70), oklch(0.38 0.08 55)
            blobOpacity = if (dark) 0.2f else 0.18f
        )
    }
}
