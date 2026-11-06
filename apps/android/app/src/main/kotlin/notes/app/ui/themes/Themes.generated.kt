// Generated from shared/themes by shared/themes/generate.ts. Change the JSON there and run `mise run themes`.
// The format and what each value means: docs/contributing/themes.md.

package notes.app.ui.themes

import androidx.compose.ui.graphics.Color
import notes.app.R

/** A color in light and dark, each 0xAARRGGBB. */
data class ThemeColor(val light: Long, val dark: Long) {
    fun resolve(dark: Boolean): Color = Color(if (dark) this.dark else light)
}

/** One layer of a shadow, as CSS's box-shadow describes it. color is 0xAARRGGBB. */
data class ThemeShadowLayer(
    val x: Float,
    val y: Float,
    val blur: Float,
    val spread: Float,
    val color: Long,
    val inset: Boolean
)

/** A shadow's layers in each mode, the first on top; none is an empty list. */
data class ThemeShadow(val light: List<ThemeShadowLayer>, val dark: List<ThemeShadowLayer>)

/** A see-through panel's blur. Below Android 12 there's no blur: the panel is drawn opaque. */
data class ThemeBlur(val radius: Float, val saturation: Float)

sealed interface ThemeCurve {
    data class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : ThemeCurve

    /** Evenly spaced points of a curve: a spring. */
    data class Linear(val points: List<Float>) : ThemeCurve
}

sealed interface ThemeCorner {
    object Circle : ThemeCorner

    data class Radius(val radius: Float) : ThemeCorner
}

/** A ticked box: a dot inside an accent ring, or filled. */
enum class ThemeCheckMark { Dot, Fill }

/** In em. */
data class ThemeInsets(val vertical: Float, val horizontal: Float)

/** weights: one weight, or the range a variable font covers. */
data class ThemeFontFace(val resource: Int, val weights: ClosedFloatingPointRange<Float>, val italic: Boolean)

/** opticalSizing: has an opsz axis; set it to the text size, as browsers do by themselves. */
data class ThemeTypeface(val name: String, val faces: List<ThemeFontFace>, val opticalSizing: Boolean)

/**
 * The title face and how titles are set in it; quotes use it too, lighter. axes: variation axes
 * besides weight and optical size. scale multiplies title sizes, which were written for
 * Instrument Serif, a narrow face. tracking is added letter spacing, in em.
 */
data class ThemeTitleFont(
    val typeface: ThemeTypeface,
    val weight: Float,
    val italic: Boolean,
    val quoteWeight: Float,
    val quoteItalic: Boolean,
    val axes: Map<String, Float>,
    val scale: Float,
    val tracking: Float
)

/**
 * Palette roles, then the style's materials resolved on that palette. on: a selected row.
 * wash: the glow behind the window. tagInk: null for the text around a tag. selection: null for
 * the system's own.
 */
data class ThemeColors(
    val bg: ThemeColor,
    val paper: ThemeColor,
    val ink: ThemeColor,
    val ink2: ThemeColor,
    val ink3: ThemeColor,
    val scrim: ThemeColor,
    val surface: ThemeColor,
    val line: ThemeColor,
    val shade: ThemeColor,
    val blob1: ThemeColor,
    val accent: ThemeColor,
    val accentDeep: ThemeColor,
    val onAccent: ThemeColor,
    val tint: ThemeColor,
    val link: ThemeColor,
    val focus: ThemeColor,
    val solid: ThemeColor,
    val onSolid: ThemeColor,
    val danger: ThemeColor,
    val dangerBg: ThemeColor,
    val good: ThemeColor,
    val goodBg: ThemeColor,
    val errBg: ThemeColor,
    val errEdge: ThemeColor,
    val errInk: ThemeColor,
    val warnBg: ThemeColor,
    val warnEdge: ThemeColor,
    val warnInk: ThemeColor,
    val statusSaved: ThemeColor,
    val statusUnsaved: ThemeColor,
    val statusSaving: ThemeColor,
    val statusFailed: ThemeColor,
    val statusConflict: ThemeColor,
    val code: ThemeColor,
    val codeInk: ThemeColor,
    val codeDim: ThemeColor,
    val syntaxKeyword: ThemeColor,
    val syntaxString: ThemeColor,
    val syntaxNumber: ThemeColor,
    val syntaxTitle: ThemeColor,
    val syntaxMeta: ThemeColor,
    val syntaxDeletion: ThemeColor,
    val checkInk: ThemeColor,
    val sidebar: ThemeColor,
    val glassHi: ThemeColor,
    val edge: ThemeColor,
    val field: ThemeColor,
    val chip: ThemeColor,
    val hair: ThemeColor,
    val hairStrong: ThemeColor,
    val hover: ThemeColor,
    val on: ThemeColor,
    val wash: ThemeColor?,
    val tagBackground: ThemeColor?,
    val tagInk: ThemeColor?,
    val selection: ThemeColor?
)

data class ThemeShadows(
    val inset: ThemeShadow,
    val shadow: ThemeShadow,
    val shadowLg: ThemeShadow,
    val shadowPop: ThemeShadow,
    val shadowBar: ThemeShadow,
    val shadowToast: ThemeShadow,
    val shadowThumb: ThemeShadow,
    val focusRing: ThemeShadow
)

data class ThemeBlurs(val panel: ThemeBlur?, val pop: ThemeBlur?, val dialog: ThemeBlur?, val scrim: ThemeBlur?)

/** radiusScale multiplies every corner radius. */
data class ThemeShape(
    val radiusScale: Float,
    val checkCorner: ThemeCorner,
    val checkMark: ThemeCheckMark,
    val tagRadius: Float,
    val tagPadding: ThemeInsets,
    val doneStrikethrough: Boolean
)

/** spring: things that appear. */
data class ThemeMotion(val spring: ThemeCurve, val easeOut: ThemeCurve)

/** body: everything that isn't a title. */
data class NotesTheme(
    val id: String,
    val name: String,
    val mood: String,
    val colors: ThemeColors,
    val shadows: ThemeShadows,
    val blurs: ThemeBlurs,
    val shape: ThemeShape,
    val motion: ThemeMotion,
    val title: ThemeTitleFont,
    val body: ThemeTypeface
)

object Themes {
    val amber = NotesTheme(
        id = "amber",
        name = "Amber",
        mood = "Afternoon light on paper",
        colors = ThemeColors(
            bg = ThemeColor(0xFFF5F3F1, 0xFF121416),
            paper = ThemeColor(0xFFFCFBFA, 0xFF1A1C1E),
            ink = ThemeColor(0xFF211F1C, 0xFFE9EBEE),
            ink2 = ThemeColor(0xFF504C4A, 0xFFB2B4B7),
            ink3 = ThemeColor(0xFF666260, 0xFF939598),
            scrim = ThemeColor(0x6BECEAE6, 0x800B0D0E),
            surface = ThemeColor(0xFFFEFDFC, 0xFF2D2F31),
            line = ThemeColor(0xFF2D2823, 0xFFFFFFFF),
            shade = ThemeColor(0xFF302821, 0xFF000000),
            blob1 = ThemeColor(0xFFF4D9A8, 0xFF624821),
            accent = ThemeColor(0xFFEFB146, 0xFFEFB146),
            accentDeep = ThemeColor(0xFFDD9314, 0xFFDD9314),
            onAccent = ThemeColor(0xFF1F1915, 0xFF1F1915),
            tint = ThemeColor(0xFFBE7200, 0xFFF0BA59),
            link = ThemeColor(0xFF874E00, 0xFFEFBA64),
            focus = ThemeColor(0x8CDD9314, 0x8CDD9314),
            solid = ThemeColor(0xFF24211F, 0xFFE6E8EA),
            onSolid = ThemeColor(0xFFF9F8F6, 0xFF141618),
            danger = ThemeColor(0xFFAC312C, 0xFFFB8276),
            dangerBg = ThemeColor(0x1AD24D45, 0x29D24D45),
            good = ThemeColor(0xFF0E492A, 0xFF9EE0B4),
            goodBg = ThemeColor(0xBFC0EACD, 0x80245337),
            errBg = ThemeColor(0xD9FFE8E3, 0xB34D2620),
            errEdge = ThemeColor(0xFFFDCAC0, 0xFF723B32),
            errInk = ThemeColor(0xFF782B21, 0xFFFDCAC0),
            warnBg = ThemeColor(0xE0FFEAD3, 0xBF482F1A),
            warnEdge = ThemeColor(0xFFF6CBA5, 0xFF724B2B),
            warnInk = ThemeColor(0xFF623512, 0xFFF8D8B4),
            statusSaved = ThemeColor(0xFF56AE6C, 0xFF56AE6C),
            statusUnsaved = ThemeColor(0xFF96918C, 0xFF96918C),
            statusSaving = ThemeColor(0xFFE8A127, 0xFFE8A127),
            statusFailed = ThemeColor(0xFFE24942, 0xFFE24942),
            statusConflict = ThemeColor(0xFFE67339, 0xFFE67339),
            code = ThemeColor(0xED24211F, 0xD9060709),
            codeInk = ThemeColor(0xFFD8D4CD, 0xFFD8D4CD),
            codeDim = ThemeColor(0xFF8F8C85, 0xFF8F8C85),
            syntaxKeyword = ThemeColor(0xFFC3A5F9, 0xFFC3A5F9),
            syntaxString = ThemeColor(0xFF8BD28D, 0xFF8BD28D),
            syntaxNumber = ThemeColor(0xFFFBA962, 0xFFFBA962),
            syntaxTitle = ThemeColor(0xFF75CAF2, 0xFF75CAF2),
            syntaxMeta = ThemeColor(0xFFF8A4A3, 0xFFF8A4A3),
            syntaxDeletion = ThemeColor(0xFFF47C70, 0xFFF47C70),
            checkInk = ThemeColor(0xFF26201C, 0xFF26201C),
            sidebar = ThemeColor(0xFFF5F3F1, 0xFF121416),
            glassHi = ThemeColor(0xE0FEFDFC, 0xE02D2F31),
            edge = ThemeColor(0xD9FFFFFF, 0x14FFFFFF),
            field = ThemeColor(0xC7FFFFFF, 0x0FFFFFFF),
            chip = ThemeColor(0x9EFFFFFF, 0x12FFFFFF),
            hair = ThemeColor(0x172D2823, 0x14FFFFFF),
            hairStrong = ThemeColor(0x292D2823, 0x29FFFFFF),
            hover = ThemeColor(0x122D2823, 0x12FFFFFF),
            on = ThemeColor(0x1C2D2823, 0x21FFFFFF),
            wash = ThemeColor(0x2EF4D9A8, 0x33624821),
            tagBackground = ThemeColor(0x4DEFB146, 0x4DEFB146),
            tagInk = null,
            selection = null
        ),
        shadows = ThemeShadows(
            inset = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 1f, 0f, 0f, 0xE6FFFFFF, inset = true)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 1f, 0f, 0f, 0x0FFFFFFF, inset = true)
                )
            ),
            shadow = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 12f, 32f, -18f, 0x38302821, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 14f, 34f, -18f, 0xA6000000, inset = false)
                )
            ),
            shadowLg = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 40f, 90f, -34f, 0x59302821, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 40f, 90f, -30f, 0xBF000000, inset = false)
                )
            ),
            shadowPop = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 24f, 50f, -16f, 0x59000000, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 24f, 50f, -16f, 0x59000000, inset = false)
                )
            ),
            shadowBar = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 24f, 50f, -16f, 0x4D000000, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 24f, 50f, -16f, 0x4D000000, inset = false)
                )
            ),
            shadowToast = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 16f, 40f, -12f, 0x66000000, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 16f, 40f, -12f, 0x66000000, inset = false)
                )
            ),
            shadowThumb = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 1f, 3f, 0f, 0x1F000000, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 1f, 3f, 0f, 0x1F000000, inset = false)
                )
            ),
            focusRing = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 3f, 0x8CDD9314, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 3f, 0x8CDD9314, inset = false)
                )
            )
        ),
        blurs = ThemeBlurs(
            panel = ThemeBlur(18f, 1.3f),
            pop = ThemeBlur(24f, 1.4f),
            dialog = ThemeBlur(24f, 1.3f),
            scrim = ThemeBlur(10f, 1f)
        ),
        shape = ThemeShape(
            radiusScale = 1f,
            checkCorner = ThemeCorner.Circle,
            checkMark = ThemeCheckMark.Dot,
            tagRadius = 6f,
            tagPadding = ThemeInsets(0.05f, 0.3f),
            doneStrikethrough = false
        ),
        motion = ThemeMotion(
            spring = ThemeCurve.Linear(
                listOf(
                    0f, 0.084f, 0.266f, 0.471f, 0.657f, 0.805f, 0.911f, 0.979f, 1.017f,
                    1.035f, 1.038f, 1.034f, 1.027f, 1.019f, 1.012f, 1.007f, 1.003f, 1f
                )
            ),
            easeOut = ThemeCurve.CubicBezier(0.2f, 0.8f, 0.2f, 1f)
        ),
        title = ThemeTitleFont(
            typeface = ThemeTypeface(
                name = "Instrument Serif",
                faces = listOf(
                    ThemeFontFace(R.font.instrument_serif, 400f..400f, italic = false),
                    ThemeFontFace(R.font.instrument_serif_italic, 400f..400f, italic = true)
                ),
                opticalSizing = false
            ),
            weight = 400f,
            italic = false,
            quoteWeight = 400f,
            quoteItalic = true,
            axes = emptyMap(),
            scale = 1f,
            tracking = 0f
        ),
        body = ThemeTypeface(
            name = "Instrument Sans",
            faces = listOf(
                ThemeFontFace(R.font.instrument_sans, 400f..700f, italic = false),
                ThemeFontFace(R.font.instrument_sans_italic, 400f..700f, italic = true)
            ),
            opticalSizing = false
        )
    )

    val nocturne = NotesTheme(
        id = "nocturne",
        name = "Nocturne",
        mood = "The city, long after midnight",
        colors = ThemeColors(
            bg = ThemeColor(0xFFE8E9EF, 0xFF13141B),
            paper = ThemeColor(0xFFF6F6F9, 0xFF191A24),
            ink = ThemeColor(0xFF262A45, 0xFFD0D5EA),
            ink2 = ThemeColor(0xFF4A4F70, 0xFFA3ABCB),
            ink3 = ThemeColor(0xFF5D6286, 0xFF7C84AB),
            scrim = ThemeColor(0x80E1E3EC, 0x8C08090E),
            surface = ThemeColor(0xFFFBFBFD, 0xFF1F2130),
            line = ThemeColor(0xFF262A45, 0xFFA9B1D6),
            shade = ThemeColor(0xFF1E2450, 0xFF000004),
            blob1 = ThemeColor(0xFFC9D1EF, 0xFF283163),
            accent = ThemeColor(0xFFB8336F, 0xFFF0A3C6),
            accentDeep = ThemeColor(0xFF962A5B, 0xFFDF88B1),
            onAccent = ThemeColor(0xFFFFFFFF, 0xFF12131B),
            tint = ThemeColor(0xFFB8336F, 0xFFF2ADCC),
            link = ThemeColor(0xFFA02C61, 0xFFF5B3D0),
            focus = ThemeColor(0x4DB8336F, 0x66F0A3C6),
            solid = ThemeColor(0xFF262A45, 0xFFD0D5EA),
            onSolid = ThemeColor(0xFFF6F6F9, 0xFF13141B),
            danger = ThemeColor(0xFFB8324F, 0xFFEF8399),
            dangerBg = ThemeColor(0x17B8324F, 0x21EF8399),
            good = ThemeColor(0xFF3F6A1E, 0xFFA9D27C),
            goodBg = ThemeColor(0x3878AA50, 0x249ECE6A),
            errBg = ThemeColor(0xE6FAE4EA, 0xB3461E2D),
            errEdge = ThemeColor(0xFFEAB8C4, 0xFF5E3346),
            errInk = ThemeColor(0xFF8A2139, 0xFFFFC9D3),
            warnBg = ThemeColor(0xE6F8ECD6, 0xB346371E),
            warnEdge = ThemeColor(0xFFE6C99A, 0xFF5E4A2A),
            warnInk = ThemeColor(0xFF6A4710, 0xFFF2D6A6),
            statusSaved = ThemeColor(0xFF5F9A35, 0xFF9ECE6A),
            statusUnsaved = ThemeColor(0xFF9A9FBA, 0xFF565F89),
            statusSaving = ThemeColor(0xFFB8336F, 0xFFF0A3C6),
            statusFailed = ThemeColor(0xFFC4304F, 0xFFF7768E),
            statusConflict = ThemeColor(0xFFC07A20, 0xFFE0AF68),
            code = ThemeColor(0xFF1C1D29, 0xFF101118),
            codeInk = ThemeColor(0xFFC0CAF5, 0xFFC0CAF5),
            codeDim = ThemeColor(0xFF6A72A0, 0xFF6A72A0),
            syntaxKeyword = ThemeColor(0xFFBB9AF7, 0xFFBB9AF7),
            syntaxString = ThemeColor(0xFF9ECE6A, 0xFF9ECE6A),
            syntaxNumber = ThemeColor(0xFFFF9E64, 0xFFFF9E64),
            syntaxTitle = ThemeColor(0xFF7AA2F7, 0xFF7AA2F7),
            syntaxMeta = ThemeColor(0xFF7DCFFF, 0xFF7DCFFF),
            syntaxDeletion = ThemeColor(0xFFF7768E, 0xFFF7768E),
            checkInk = ThemeColor(0xFFFFFFFF, 0xFF12131B),
            sidebar = ThemeColor(0xFFE8E9EF, 0xFF13141B),
            glassHi = ThemeColor(0xD6FBFBFD, 0xD61F2130),
            edge = ThemeColor(0x12262A45, 0x0FA9B1D6),
            field = ThemeColor(0xB3FFFFFF, 0x0DA9B1D6),
            chip = ThemeColor(0x8CFFFFFF, 0x0FA9B1D6),
            hair = ThemeColor(0x14262A45, 0x14A9B1D6),
            hairStrong = ThemeColor(0x26262A45, 0x26A9B1D6),
            hover = ThemeColor(0x0F262A45, 0x0FA9B1D6),
            on = ThemeColor(0x1A262A45, 0x1AA9B1D6),
            wash = null,
            tagBackground = ThemeColor(0x29B8336F, 0x29F0A3C6),
            tagInk = null,
            selection = ThemeColor(0x4DB8336F, 0x4DF0A3C6)
        ),
        shadows = ThemeShadows(
            inset = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 1f, 0f, 0f, 0xE6FFFFFF, inset = true)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 1f, 0f, 0f, 0x0EFFFFFF, inset = true)
                )
            ),
            shadow = ThemeShadow(
                light = emptyList(),
                dark = emptyList()
            ),
            shadowLg = ThemeShadow(
                light = emptyList(),
                dark = emptyList()
            ),
            shadowPop = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 28f, 60f, -20f, 0x4D1E2450, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 28f, 60f, -20f, 0xBF000004, inset = false)
                )
            ),
            shadowBar = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 28f, 60f, -20f, 0x4D1E2450, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 28f, 60f, -20f, 0xBF000004, inset = false)
                )
            ),
            shadowToast = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 28f, 60f, -20f, 0x4D1E2450, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 28f, 60f, -20f, 0xBF000004, inset = false)
                )
            ),
            shadowThumb = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 1f, 0f, 0f, 0xE6FFFFFF, inset = true),
                    ThemeShadowLayer(0f, 1f, 3f, 0f, 0x291E2450, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 1f, 0f, 0f, 0x12FFFFFF, inset = true),
                    ThemeShadowLayer(0f, 1f, 3f, 0f, 0xB3000004, inset = false)
                )
            ),
            focusRing = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 3f, 0x4DB8336F, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 3f, 0x66F0A3C6, inset = false)
                )
            )
        ),
        blurs = ThemeBlurs(
            panel = ThemeBlur(28f, 1.15f),
            pop = ThemeBlur(32f, 1.2f),
            dialog = ThemeBlur(32f, 1.2f),
            scrim = ThemeBlur(14f, 1f)
        ),
        shape = ThemeShape(
            radiusScale = 0.86f,
            checkCorner = ThemeCorner.Circle,
            checkMark = ThemeCheckMark.Dot,
            tagRadius = 6f,
            tagPadding = ThemeInsets(0.05f, 0.3f),
            doneStrikethrough = false
        ),
        motion = ThemeMotion(
            spring = ThemeCurve.CubicBezier(0.22f, 1f, 0.36f, 1f),
            easeOut = ThemeCurve.CubicBezier(0.22f, 1f, 0.36f, 1f)
        ),
        title = ThemeTitleFont(
            typeface = ThemeTypeface(
                name = "Shippori Mincho",
                faces = listOf(
                    ThemeFontFace(R.font.shippori_mincho_regular, 400f..400f, italic = false),
                    ThemeFontFace(R.font.shippori_mincho_medium, 500f..500f, italic = false)
                ),
                opticalSizing = false
            ),
            weight = 500f,
            italic = false,
            quoteWeight = 400f,
            quoteItalic = false,
            axes = emptyMap(),
            scale = 0.9f,
            tracking = 0f
        ),
        body = ThemeTypeface(
            name = "Zen Kaku Gothic New",
            faces = listOf(
                ThemeFontFace(R.font.zen_kaku_gothic_new_regular, 400f..400f, italic = false),
                ThemeFontFace(R.font.zen_kaku_gothic_new_medium, 500f..500f, italic = false),
                ThemeFontFace(R.font.zen_kaku_gothic_new_bold, 700f..700f, italic = false)
            ),
            opticalSizing = false
        )
    )

    val carta = NotesTheme(
        id = "carta",
        name = "Carta",
        mood = "Black ink on good paper",
        colors = ThemeColors(
            bg = ThemeColor(0xFFECEBE7, 0xFF0A0A0A),
            paper = ThemeColor(0xFFFBFAF7, 0xFF131313),
            ink = ThemeColor(0xFF121212, 0xFFECEBE6),
            ink2 = ThemeColor(0xFF3D3D3B, 0xFFB4B3AE),
            ink3 = ThemeColor(0xFF5C5C59, 0xFF8F8E8A),
            scrim = ThemeColor(0x99ECEBE7, 0x99000000),
            surface = ThemeColor(0xFFFDFCFA, 0xFF1A1A1A),
            line = ThemeColor(0xFF121212, 0xFFECEBE6),
            shade = ThemeColor(0xFF14120F, 0xFF000000),
            blob1 = ThemeColor(0xFFE2E1DC, 0xFF1C1C1C),
            accent = ThemeColor(0xFF121212, 0xFFECEBE6),
            accentDeep = ThemeColor(0xFF000000, 0xFFFFFFFF),
            onAccent = ThemeColor(0xFFFBFAF7, 0xFF0A0A0A),
            tint = ThemeColor(0xFF121212, 0xFFECEBE6),
            link = ThemeColor(0xFF5C5C59, 0xFFA8A7A2),
            focus = ThemeColor(0x40121212, 0x4DECEBE6),
            solid = ThemeColor(0xFF121212, 0xFFECEBE6),
            onSolid = ThemeColor(0xFFFBFAF7, 0xFF0A0A0A),
            danger = ThemeColor(0xFFB3261E, 0xFFF2847A),
            dangerBg = ThemeColor(0x12B3261E, 0x1FF2847A),
            good = ThemeColor(0xFF2F4A36, 0xFFB9CBBD),
            goodBg = ThemeColor(0x1A2F4A36, 0x1FB9CBBD),
            errBg = ThemeColor(0xE6F6E6E3, 0xB3461C19),
            errEdge = ThemeColor(0xFFE3BDB7, 0xFF5E302B),
            errInk = ThemeColor(0xFF7D1D17, 0xFFFFCFC9),
            warnBg = ThemeColor(0xE6F0ECE2, 0xCC322E24),
            warnEdge = ThemeColor(0xFFD8D0BC, 0xFF55503F),
            warnInk = ThemeColor(0xFF4A4430, 0xFFECE4CC),
            statusSaved = ThemeColor(0xFF9A9A96, 0xFF6E6D6A),
            statusUnsaved = ThemeColor(0xFFCFCFCB, 0xFF3A3A38),
            statusSaving = ThemeColor(0xFF121212, 0xFFECEBE6),
            statusFailed = ThemeColor(0xFFC62D22, 0xFFC62D22),
            statusConflict = ThemeColor(0xFF7A6A3A, 0xFFC9B47A),
            code = ThemeColor(0xFF151515, 0xFF050505),
            codeInk = ThemeColor(0xFFE4E3DE, 0xFFE4E3DE),
            codeDim = ThemeColor(0xFF858480, 0xFF858480),
            syntaxKeyword = ThemeColor(0xFFFFFFFF, 0xFFFFFFFF),
            syntaxString = ThemeColor(0xFFBDBCB7, 0xFFBDBCB7),
            syntaxNumber = ThemeColor(0xFFFFFFFF, 0xFFFFFFFF),
            syntaxTitle = ThemeColor(0xFFF4F3EE, 0xFFF4F3EE),
            syntaxMeta = ThemeColor(0xFFA6A5A0, 0xFFA6A5A0),
            syntaxDeletion = ThemeColor(0xFFF2847A, 0xFFF2847A),
            checkInk = ThemeColor(0xFFFBFAF7, 0xFF0A0A0A),
            sidebar = ThemeColor(0xFFECEBE7, 0xFF0A0A0A),
            glassHi = ThemeColor(0xFFFDFCFA, 0xFF1A1A1A),
            edge = ThemeColor(0x21121212, 0x21ECEBE6),
            field = ThemeColor(0xFFFBFAF7, 0xFF131313),
            chip = ThemeColor(0x0A121212, 0x0AECEBE6),
            hair = ThemeColor(0x1A121212, 0x1AECEBE6),
            hairStrong = ThemeColor(0x2E121212, 0x2EECEBE6),
            hover = ThemeColor(0x0D121212, 0x0DECEBE6),
            on = ThemeColor(0x14121212, 0x14ECEBE6),
            wash = null,
            tagBackground = null,
            tagInk = ThemeColor(0xFF5C5C59, 0xFFA8A7A2),
            selection = ThemeColor(0x29121212, 0x29ECEBE6)
        ),
        shadows = ThemeShadows(
            inset = ThemeShadow(
                light = emptyList(),
                dark = emptyList()
            ),
            shadow = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 1f, 1f, 0f, 0x0F14120F, inset = false),
                    ThemeShadowLayer(0f, 8f, 20f, -14f, 0x4714120F, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 1f, 1f, 0f, 0x0F000000, inset = false),
                    ThemeShadowLayer(0f, 8f, 20f, -14f, 0x99000000, inset = false)
                )
            ),
            shadowLg = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 1f, 1f, 0f, 0x1214120F, inset = false),
                    ThemeShadowLayer(0f, 14f, 32f, -22f, 0x5214120F, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 1f, 1f, 0f, 0x12000000, inset = false),
                    ThemeShadowLayer(0f, 14f, 32f, -22f, 0xB3000000, inset = false)
                )
            ),
            shadowPop = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 1f, 2f, 0f, 0x1414120F, inset = false),
                    ThemeShadowLayer(0f, 14f, 28f, -16f, 0x5214120F, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 1f, 2f, 0f, 0x14000000, inset = false),
                    ThemeShadowLayer(0f, 14f, 28f, -16f, 0xB3000000, inset = false)
                )
            ),
            shadowBar = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 1f, 2f, 0f, 0x1414120F, inset = false),
                    ThemeShadowLayer(0f, 14f, 28f, -16f, 0x5214120F, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 1f, 2f, 0f, 0x14000000, inset = false),
                    ThemeShadowLayer(0f, 14f, 28f, -16f, 0xB3000000, inset = false)
                )
            ),
            shadowToast = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 1f, 2f, 0f, 0x1414120F, inset = false),
                    ThemeShadowLayer(0f, 14f, 28f, -16f, 0x5214120F, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 1f, 2f, 0f, 0x14000000, inset = false),
                    ThemeShadowLayer(0f, 14f, 28f, -16f, 0xB3000000, inset = false)
                )
            ),
            shadowThumb = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 1f, 0x1A121212, inset = false),
                    ThemeShadowLayer(0f, 1f, 1f, 0f, 0x1A14120F, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 1f, 0x1AECEBE6, inset = false),
                    ThemeShadowLayer(0f, 1f, 1f, 0f, 0x1A000000, inset = false)
                )
            ),
            focusRing = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 2f, 0x40121212, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 2f, 0x4DECEBE6, inset = false)
                )
            )
        ),
        blurs = ThemeBlurs(
            panel = null,
            pop = null,
            dialog = null,
            scrim = ThemeBlur(8f, 1f)
        ),
        shape = ThemeShape(
            radiusScale = 0.45f,
            checkCorner = ThemeCorner.Radius(4f),
            checkMark = ThemeCheckMark.Fill,
            tagRadius = 0f,
            tagPadding = ThemeInsets(0f, 0f),
            doneStrikethrough = false
        ),
        motion = ThemeMotion(
            spring = ThemeCurve.CubicBezier(0.25f, 0.8f, 0.3f, 1f),
            easeOut = ThemeCurve.CubicBezier(0.25f, 0.8f, 0.3f, 1f)
        ),
        title = ThemeTitleFont(
            typeface = ThemeTypeface(
                name = "Playfair",
                faces = listOf(
                    ThemeFontFace(R.font.playfair, 400f..500f, italic = false),
                    ThemeFontFace(R.font.playfair_italic, 400f..500f, italic = true)
                ),
                opticalSizing = true
            ),
            weight = 400f,
            italic = false,
            quoteWeight = 400f,
            quoteItalic = true,
            axes = mapOf("wdth" to 88f),
            scale = 1f,
            tracking = 0f
        ),
        body = ThemeTypeface(
            name = "Hanken Grotesk",
            faces = listOf(
                ThemeFontFace(R.font.hanken_grotesk, 100f..900f, italic = false),
                ThemeFontFace(R.font.hanken_grotesk_italic, 100f..900f, italic = true)
            ),
            opticalSizing = false
        )
    )

    val den = NotesTheme(
        id = "den",
        name = "Den",
        mood = "Warm graphite, one honey mark",
        colors = ThemeColors(
            bg = ThemeColor(0xFFF6F6F5, 0xFF222427),
            paper = ThemeColor(0xFFFFFFFF, 0xFF2A2C30),
            ink = ThemeColor(0xFF2B2D30, 0xFFDEDEDE),
            ink2 = ThemeColor(0xFF53565B, 0xFFB0B2B5),
            ink3 = ThemeColor(0xFF66696E, 0xFF919397),
            scrim = ThemeColor(0xD1F6F6F5, 0xBF121315),
            surface = ThemeColor(0xFFFFFFFF, 0xFF303236),
            line = ThemeColor(0xFF2B2D30, 0xFFDEDEDE),
            shade = ThemeColor(0xFF1E1E20, 0xFF000000),
            blob1 = ThemeColor(0xFFECECEB, 0xFF2A2C30),
            accent = ThemeColor(0xFFC8962F, 0xFFD9A94F),
            accentDeep = ThemeColor(0xFFA87A1F, 0xFFC2913A),
            onAccent = ThemeColor(0xFF211C12, 0xFF1A1B1D),
            tint = ThemeColor(0xFFB2842A, 0xFFDCAE58),
            link = ThemeColor(0xFF8A6414, 0xFFE2B766),
            focus = ThemeColor(0x59C8962F, 0x66D9A94F),
            solid = ThemeColor(0xFF2B2D30, 0xFFDEDEDE),
            onSolid = ThemeColor(0xFFFFFFFF, 0xFF1C1D20),
            danger = ThemeColor(0xFFB42318, 0xFFF2847A),
            dangerBg = ThemeColor(0x12B42318, 0x1FF2847A),
            good = ThemeColor(0xFF2F6B3A, 0xFF9FD8A8),
            goodBg = ThemeColor(0x1A2F6B3A, 0x1F9FD8A8),
            errBg = ThemeColor(0xF2FCE8E6, 0xCC501E1C),
            errEdge = ThemeColor(0xFFF0C0BB, 0xFF6E3530),
            errInk = ThemeColor(0xFF86201A, 0xFFFFD0C8),
            warnBg = ThemeColor(0xF2FCF2DC, 0xCC4B3C19),
            warnEdge = ThemeColor(0xFFEAD39C, 0xFF6E5A28),
            warnInk = ThemeColor(0xFF5E4500, 0xFFF2DF9C),
            statusSaved = ThemeColor(0xFF4F9A5C, 0xFF7FCF8C),
            statusUnsaved = ThemeColor(0xFFB5B7BA, 0xFF5A5C60),
            statusSaving = ThemeColor(0xFFC8962F, 0xFFD9A94F),
            statusFailed = ThemeColor(0xFFD1403D, 0xFFD1403D),
            statusConflict = ThemeColor(0xFFD98A1C, 0xFFD98A1C),
            code = ThemeColor(0xFF2F3237, 0xFF1C1D20),
            codeInk = ThemeColor(0xFFE3E3E3, 0xFFE3E3E3),
            codeDim = ThemeColor(0xFF8E9196, 0xFF8E9196),
            syntaxKeyword = ThemeColor(0xFFFF8A85, 0xFFFF8A85),
            syntaxString = ThemeColor(0xFFA8D68F, 0xFFA8D68F),
            syntaxNumber = ThemeColor(0xFFF4B860, 0xFFF4B860),
            syntaxTitle = ThemeColor(0xFF8FBCF0, 0xFF8FBCF0),
            syntaxMeta = ThemeColor(0xFFC7A3F0, 0xFFC7A3F0),
            syntaxDeletion = ThemeColor(0xFFFF8A85, 0xFFFF8A85),
            checkInk = ThemeColor(0xFF211C12, 0xFF1A1B1D),
            sidebar = ThemeColor(0xFFECECEB, 0xFF25272A),
            glassHi = ThemeColor(0xD1FFFFFF, 0xD1303236),
            edge = ThemeColor(0x1F2B2D30, 0x1FDEDEDE),
            field = ThemeColor(0x0F2B2D30, 0x0FDEDEDE),
            chip = ThemeColor(0x0F2B2D30, 0x0FDEDEDE),
            hair = ThemeColor(0x1A2B2D30, 0x1ADEDEDE),
            hairStrong = ThemeColor(0x2E2B2D30, 0x2EDEDEDE),
            hover = ThemeColor(0x0D2B2D30, 0x0DDEDEDE),
            on = ThemeColor(0x172B2D30, 0x17DEDEDE),
            wash = null,
            tagBackground = ThemeColor(0x142B2D30, 0x14DEDEDE),
            tagInk = null,
            selection = ThemeColor(0x40C8962F, 0x40D9A94F)
        ),
        shadows = ThemeShadows(
            inset = ThemeShadow(
                light = emptyList(),
                dark = emptyList()
            ),
            shadow = ThemeShadow(
                light = emptyList(),
                dark = emptyList()
            ),
            shadowLg = ThemeShadow(
                light = emptyList(),
                dark = emptyList()
            ),
            shadowPop = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 0.5f, 0x242B2D30, inset = false),
                    ThemeShadowLayer(0f, 10f, 30f, -8f, 0x401E1E20, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 0.5f, 0x24DEDEDE, inset = false),
                    ThemeShadowLayer(0f, 10f, 30f, -8f, 0xA6000000, inset = false)
                )
            ),
            shadowBar = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 0.5f, 0x242B2D30, inset = false),
                    ThemeShadowLayer(0f, 10f, 30f, -8f, 0x401E1E20, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 0.5f, 0x24DEDEDE, inset = false),
                    ThemeShadowLayer(0f, 10f, 30f, -8f, 0xA6000000, inset = false)
                )
            ),
            shadowToast = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 0.5f, 0x242B2D30, inset = false),
                    ThemeShadowLayer(0f, 10f, 30f, -8f, 0x401E1E20, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 0.5f, 0x24DEDEDE, inset = false),
                    ThemeShadowLayer(0f, 10f, 30f, -8f, 0xA6000000, inset = false)
                )
            ),
            shadowThumb = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 0.5f, 0x1F2B2D30, inset = false),
                    ThemeShadowLayer(0f, 1f, 2f, 0f, 0x261E1E20, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 0.5f, 0x1FDEDEDE, inset = false),
                    ThemeShadowLayer(0f, 1f, 2f, 0f, 0x80000000, inset = false)
                )
            ),
            focusRing = ThemeShadow(
                light = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 3f, 0x59C8962F, inset = false)
                ),
                dark = listOf(
                    ThemeShadowLayer(0f, 0f, 0f, 3f, 0x66D9A94F, inset = false)
                )
            )
        ),
        blurs = ThemeBlurs(
            panel = ThemeBlur(30f, 1.8f),
            pop = ThemeBlur(30f, 1.8f),
            dialog = ThemeBlur(30f, 1.8f),
            scrim = ThemeBlur(12f, 1f)
        ),
        shape = ThemeShape(
            radiusScale = 0.5f,
            checkCorner = ThemeCorner.Circle,
            checkMark = ThemeCheckMark.Fill,
            tagRadius = 4f,
            tagPadding = ThemeInsets(0.05f, 0.35f),
            doneStrikethrough = true
        ),
        motion = ThemeMotion(
            spring = ThemeCurve.CubicBezier(0.25f, 0.1f, 0.25f, 1f),
            easeOut = ThemeCurve.CubicBezier(0.25f, 0.1f, 0.25f, 1f)
        ),
        title = ThemeTitleFont(
            typeface = ThemeTypeface(
                name = "Nunito Sans",
                faces = listOf(
                    ThemeFontFace(R.font.nunito_sans, 400f..800f, italic = false),
                    ThemeFontFace(R.font.nunito_sans_italic, 400f..800f, italic = true)
                ),
                opticalSizing = true
            ),
            weight = 750f,
            italic = false,
            quoteWeight = 400f,
            quoteItalic = true,
            axes = emptyMap(),
            scale = 0.84f,
            tracking = -0.012f
        ),
        body = ThemeTypeface(
            name = "Nunito Sans",
            faces = listOf(
                ThemeFontFace(R.font.nunito_sans, 400f..800f, italic = false),
                ThemeFontFace(R.font.nunito_sans_italic, 400f..800f, italic = true)
            ),
            opticalSizing = true
        )
    )

    val all = listOf(amber, nocturne, carta, den)
    val fallback = amber

    /** fallback for an id no theme has (a stored one from a build that had it). */
    fun theme(id: String): NotesTheme = all.firstOrNull { it.id == id } ?: fallback
}
