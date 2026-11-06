package notes.app.ui

import androidx.compose.ui.graphics.Color

/**
 * The web app's Amber theme (shared/themes), converted from oklch to sRGB. The oklch source is
 * kept beside each value so the two can be compared when the web palette changes. Translucent web
 * tokens (hair, hover, glass) are rgb() there already and are copied as is.
 * ui/themes/Themes.generated.kt has every theme; adopting it replaces this
 * (docs/contributing/themes.md).
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
    val dark: Boolean
) {
    // Password strength (apps/web/src/ui/password-meter.module.css), same in both themes.
    val meterWeak get() = Color(0xFFE57255) // oklch(0.68 0.15 35)
    val meterFair get() = Color(0xFFE4A339) // oklch(0.76 0.14 75)
    val meterStrong get() = Color(0xFF5DAD70) // oklch(0.68 0.12 150)

    companion object {
        val Light = Tokens(
            bg = Color(0xFFF5F3F1), // oklch(0.965 0.0035 80)
            ink = Color(0xFF211F1C), // oklch(0.24 0.006 60)
            ink2 = Color(0xFF504C4A), // oklch(0.42 0.006 60)
            ink3 = Color(0xFF666260), // oklch(0.5 0.006 60)
            accent = Color(0xFFEFB146), // oklch(0.8 0.14 78)
            accentDeep = Color(0xFFDD9314), // oklch(0.72 0.15 72)
            onAccent = Color(0xFF1F1915), // oklch(0.22 0.012 60)
            tint = Color(0xFFBE7200), // oklch(0.62 0.15 70)
            link = Color(0xFF874E00), // oklch(0.48 0.11 65)
            danger = Color(0xFFAC312C), // oklch(0.5 0.16 27)
            solid = Color(0xFF24211F), // oklch(0.25 0.006 60)
            onSolid = Color(0xFFF9F8F6), // oklch(0.98 0.0025 80)
            hair = Color(0x172D2823), // rgb(45 40 35 / 0.09)
            hairStrong = Color(0x292D2823), // rgb(45 40 35 / 0.16)
            hover = Color(0x122D2823), // rgb(45 40 35 / 0.07)
            on = Color(0x1C2D2823), // rgb(45 40 35 / 0.11)
            glass = Color(0xBDFEFDFC), // rgb(254 253 252 / 0.74)
            glassHi = Color(0xE0FEFDFC), // rgb(254 253 252 / 0.88)
            edge = Color(0xD9FFFFFF), // rgb(255 255 255 / 0.85)
            field = Color(0xC7FFFFFF), // rgb(255 255 255 / 0.78)
            chip = Color(0x9EFFFFFF), // rgb(255 255 255 / 0.62)
            scrim = Color(0x6BECEAE6), // rgb(236 234 230 / 0.42)
            dangerBg = Color(0x1AD24D45), // oklch(0.6 0.17 27 / 0.1)
            good = Color(0xFF0E492A), // oklch(0.36 0.08 155)
            goodBg = Color(0xBFC0EACD), // oklch(0.9 0.06 155 / 0.75)
            errBg = Color(0xD9FFE8E3), // oklch(0.95 0.03 30 / 0.85)
            errEdge = Color(0xFFFDCAC0), // oklch(0.88 0.06 30)
            errInk = Color(0xFF782B21), // oklch(0.4 0.11 30)
            warnBg = Color(0xE0FFEAD3), // oklch(0.95 0.04 65 / 0.88)
            warnEdge = Color(0xFFF6CBA5), // oklch(0.87 0.07 62)
            warnInk = Color(0xFF623512), // oklch(0.38 0.08 55)
            focus = Color(0x8CDD9314), // oklch(0.72 0.15 72 / 0.55)
            blob1 = Color(0xFFF4D9A8), // oklch(0.895 0.07 82)
            blobOpacity = 0.18f,
            dark = false
        )

        val Dark = Tokens(
            bg = Color(0xFF121416), // oklch(0.19 0.005 250)
            ink = Color(0xFFE9EBEE), // oklch(0.94 0.004 250)
            ink2 = Color(0xFFB2B4B7), // oklch(0.77 0.005 250)
            ink3 = Color(0xFF939598), // oklch(0.67 0.005 250)
            accent = Color(0xFFEFB146), // oklch(0.8 0.14 78)
            accentDeep = Color(0xFFDD9314), // oklch(0.72 0.15 72)
            onAccent = Color(0xFF1F1915), // oklch(0.22 0.012 60)
            tint = Color(0xFFF0BA59), // oklch(0.82 0.13 80)
            link = Color(0xFFEFBA64), // oklch(0.82 0.12 78)
            danger = Color(0xFFFB8276), // oklch(0.74 0.15 27)
            solid = Color(0xFFE6E8EA), // oklch(0.93 0.004 250)
            onSolid = Color(0xFF141618), // oklch(0.2 0.005 250)
            hair = Color(0x14FFFFFF), // rgb(255 255 255 / 0.08)
            hairStrong = Color(0x29FFFFFF), // rgb(255 255 255 / 0.16)
            hover = Color(0x12FFFFFF), // rgb(255 255 255 / 0.07)
            on = Color(0x21FFFFFF), // rgb(255 255 255 / 0.13)
            glass = Color(0xB827292B), // rgb(39 41 43 / 0.72)
            glassHi = Color(0xE02D2F31), // rgb(45 47 49 / 0.88)
            edge = Color(0x14FFFFFF), // rgb(255 255 255 / 0.08)
            field = Color(0x0FFFFFFF), // rgb(255 255 255 / 0.06)
            chip = Color(0x12FFFFFF), // rgb(255 255 255 / 0.07)
            scrim = Color(0x800B0D0E), // rgb(11 13 14 / 0.5)
            dangerBg = Color(0x29D24D45), // oklch(0.6 0.17 27 / 0.16)
            good = Color(0xFF9EE0B4), // oklch(0.85 0.09 155)
            goodBg = Color(0x80245337), // oklch(0.4 0.07 155 / 0.5)
            errBg = Color(0xB24D2620), // oklch(0.32 0.06 30 / 0.7)
            errEdge = Color(0xFF723B32), // oklch(0.42 0.08 30)
            errInk = Color(0xFFFDCAC0), // oklch(0.88 0.06 30)
            warnBg = Color(0xBF482F1A), // oklch(0.33 0.05 60 / 0.75)
            warnEdge = Color(0xFF724B2B), // oklch(0.45 0.07 60)
            warnInk = Color(0xFFF8D8B4), // oklch(0.9 0.06 70)
            focus = Color(0x8CDD9314), // oklch(0.72 0.15 72 / 0.55)
            blob1 = Color(0xFF624821), // oklch(0.42 0.065 75)
            blobOpacity = 0.2f,
            dark = true
        )
    }
}
