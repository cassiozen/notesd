package notes.app.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import notes.app.R
import notes.app.ui.themes.NotesTheme as ThemeDefinition
import notes.app.ui.themes.ThemeTitleFont
import notes.app.ui.themes.ThemeTypeface

// The picked theme's title and body faces (shared/themes), and JetBrains Mono for code, all bundled.

private val weights = listOf(400, 500, 600, 700)

/**
 * A theme face as a Compose family: every weight a real instance of a variable font, with the
 * title's axes and, for a face with optical sizes, opsz set to the size the text is set at (as a
 * browser does by itself; out-of-range values clamp to the font's own).
 */
private fun family(
    face: ThemeTypeface,
    extra: List<Int> = emptyList(),
    axes: Map<String, Float> = emptyMap(),
    size: Float? = null
): FontFamily {
    val opsz = size?.takeIf { face.opticalSizing }
    val fonts = face.faces.flatMap { f ->
        val style = if (f.italic) FontStyle.Italic else FontStyle.Normal
        val variable = f.weights.start != f.weights.endInclusive
        val all = if (variable) {
            (weights + extra).distinct().filter {
                it.toFloat() in f.weights
            }
        } else {
            listOf(f.weights.start.toInt())
        }
        all.map { w ->
            val settings = buildList {
                if (variable) add(FontVariation.weight(w))
                axes.forEach { (axis, value) -> add(FontVariation.Setting(axis, value)) }
                opsz?.let { add(FontVariation.Setting("opsz", it)) }
            }
            Font(f.resource, FontWeight(w), style, variationSettings = FontVariation.Settings(*settings.toTypedArray()))
        }
    }
    return FontFamily(fonts)
}

/** JetBrains Mono: server addresses, tokens, code. The same in every theme. */
val Mono = FontFamily(weights.take(3).map { w -> monoFont(w) })

private fun monoFont(w: Int) = Font(
    R.font.jetbrains_mono,
    FontWeight(w),
    FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(w))
)

/** Instrument Serif, whatever the theme: the italic button's "I" is an icon, not text. */
val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic)
)

/** The picked theme's body face at a size: everything that isn't a title. */
val Sans: FontFamily get() = Type.bodyFamily(15f)

/** The picked theme's title face at a size: headings, titles, quotes. */
val Serif: FontFamily get() = Type.titleFamily(22f)

/** Named styles, sized for phones (the web app's mobile sizes), in the picked theme's faces. */
object Type {
    private class Styles(val theme: ThemeDefinition) {
        private val families = mutableMapOf<String, FontFamily>()
        val t: ThemeTitleFont = theme.title

        fun title(size: Float): FontFamily = families.getOrPut("title $size") {
            family(t.typeface, listOf(t.weight.toInt(), t.quoteWeight.toInt()), t.axes, size)
        }

        fun body(size: Float): FontFamily = families.getOrPut("body $size") { family(theme.body, size = size) }

        /** A title at a size the rules give for Instrument Serif: the theme's scale, weight, style and tracking on it. */
        fun titleAt(size: Float, lineHeight: Float = size * 1.15f, tracking: Float = 0f) = TextStyle(
            fontFamily = title(size * t.scale),
            fontSize = (size * t.scale).sp,
            lineHeight = (lineHeight * t.scale).sp,
            fontWeight = FontWeight(t.weight.toInt()),
            fontStyle = if (t.italic) FontStyle.Italic else FontStyle.Normal,
            letterSpacing = (tracking + t.tracking).em,
            lineBreak = LineBreak.Heading
        )

        fun bodyAt(
            size: Float,
            lineHeight: Float,
            weight: FontWeight? = null,
            lineBreak: LineBreak = LineBreak.Unspecified
        ) = TextStyle(
            fontFamily = body(size),
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            fontWeight = weight,
            lineBreak = lineBreak
        )

        val display = titleAt(34f, 38f, -0.02f)
        val heading = titleAt(30f, 33f, -0.015f)
        val title = titleAt(22f, 26f)
        val lede = bodyAt(16f, 24f, lineBreak = LineBreak.Paragraph)
        val body = bodyAt(15f, 22f, lineBreak = LineBreak.Paragraph)
        val rowTitle = bodyAt(16f, 21f, FontWeight.Medium)
        val rowMeta = bodyAt(14f, 19f)
        val label = bodyAt(13f, 17f, FontWeight.Medium)
        val small = bodyAt(13f, 18f)
        val button = bodyAt(15f, 20f, FontWeight.SemiBold)
        val kicker = bodyAt(13f, 16f, FontWeight.SemiBold)
        val mono = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp)
        val brand = TextStyle(
            fontFamily = Mono,
            fontSize = 20.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.02).em
        )

        /** Material's slots, for the few Material components kept (dialogs, menus, text fields). */
        val material = Typography().let { m ->
            val titleFace = title(22f)
            val bodyFace = body(15f)
            Typography(
                displayLarge = m.displayLarge.copy(fontFamily = titleFace),
                displayMedium = m.displayMedium.copy(fontFamily = titleFace),
                displaySmall = m.displaySmall.copy(fontFamily = titleFace),
                headlineLarge = m.headlineLarge.copy(fontFamily = titleFace),
                headlineMedium = m.headlineMedium.copy(fontFamily = titleFace),
                headlineSmall = titleAt(26f, 30f),
                titleLarge = m.titleLarge.copy(fontFamily = bodyFace),
                titleMedium = m.titleMedium.copy(fontFamily = bodyFace, fontWeight = FontWeight.SemiBold),
                titleSmall = m.titleSmall.copy(fontFamily = bodyFace, fontWeight = FontWeight.SemiBold),
                bodyLarge = m.bodyLarge.copy(fontFamily = bodyFace),
                bodyMedium = m.bodyMedium.copy(fontFamily = bodyFace, fontSize = 15.sp, lineHeight = 22.sp),
                bodySmall = m.bodySmall.copy(fontFamily = bodyFace),
                labelLarge = m.labelLarge.copy(fontFamily = bodyFace, fontWeight = FontWeight.SemiBold),
                labelMedium = m.labelMedium.copy(fontFamily = bodyFace),
                labelSmall = m.labelSmall.copy(fontFamily = bodyFace)
            )
        }
    }

    // Built once per theme: a new pick recreates the activity, and everything reads these again.
    @Volatile
    private var cached: Styles? = null

    private fun styles(): Styles {
        val theme = CurrentTheme.theme
        return cached?.takeIf { it.theme === theme } ?: Styles(theme).also { cached = it }
    }

    val display get() = styles().display
    val heading get() = styles().heading
    val title get() = styles().title
    val lede get() = styles().lede
    val body get() = styles().body
    val rowTitle get() = styles().rowTitle
    val rowMeta get() = styles().rowMeta
    val label get() = styles().label
    val small get() = styles().small
    val button get() = styles().button
    val kicker get() = styles().kicker
    val mono get() = styles().mono

    /** The wordmark: lowercase and monospaced, like the daemon it's named after. */
    val brand get() = styles().brand
    val material get() = styles().material

    /** A title set at a size the rules give for Instrument Serif, as the theme sets titles. */
    fun titleAt(size: Float, lineHeight: Float = size * 1.15f) = styles().titleAt(size, lineHeight)

    fun titleFamily(size: Float) = styles().title(size * styles().t.scale)

    fun bodyFamily(size: Float) = styles().body(size)

    // Settings' theme gallery sets each theme in its own fonts, whichever is picked.
    private val previews = mutableMapOf<String, Styles>()

    private fun of(theme: ThemeDefinition) = synchronized(previews) { previews.getOrPut(theme.id) { Styles(theme) } }

    /** [theme]'s title at a size the rules give for Instrument Serif. */
    fun titleAt(theme: ThemeDefinition, size: Float) = of(theme).titleAt(size)

    /** [theme]'s body face at a size. */
    fun bodyAt(theme: ThemeDefinition, size: Float, lineHeight: Float) = of(theme).bodyAt(size, lineHeight)
}
