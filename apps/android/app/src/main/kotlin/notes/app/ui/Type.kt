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

// The web app's three faces (apps/web/src/styles/fonts.css), bundled: the app works offline.

private val weights = listOf(400, 500, 600, 700)

private fun variable(id: Int, weight: Int, style: FontStyle) =
    Font(id, FontWeight(weight), style, variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Instrument Sans: everything that isn't a title. */
val Sans = FontFamily(
    weights.flatMap {
        listOf(
            variable(R.font.instrument_sans, it, FontStyle.Normal),
            variable(R.font.instrument_sans_italic, it, FontStyle.Italic)
        )
    }
)

/** Instrument Serif: headings, titles, the brand word. One weight. */
val Serif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic)
)

/** JetBrains Mono: server addresses, tokens. */
val Mono = FontFamily(weights.take(3).map { variable(R.font.jetbrains_mono, it, FontStyle.Normal) })

/** Named styles, sized for phones (the web app's mobile sizes). */
object Type {
    val display = TextStyle(
        fontFamily = Serif,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.02).em,
        lineBreak = LineBreak.Heading
    )
    val heading = TextStyle(
        fontFamily = Serif,
        fontSize = 30.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.015).em,
        lineBreak = LineBreak.Heading
    )
    val title = TextStyle(fontFamily = Serif, fontSize = 22.sp, lineHeight = 26.sp, lineBreak = LineBreak.Heading)

    /**
     * The space switcher's name: sans, as the switcher names spaces. A thin serif looked lighter than the traits'
     * button beside it.
     */
    val space = TextStyle(
        fontFamily = Sans,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.015).em
    )
    val lede = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp, lineBreak = LineBreak.Paragraph)
    val body = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp, lineBreak = LineBreak.Paragraph)
    val rowTitle = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
    val rowMeta = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 19.sp)
    val label = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp)
    val button = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val kicker = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val mono = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp)

    /** The wordmark: lowercase and monospaced, like the daemon it's named after. */
    val brand = TextStyle(
        fontFamily = Mono,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.02).em
    )
}

/** Material's slots, for the few Material components kept (dialogs, menus, text fields). */
val NotesTypography = Typography().let { m ->
    Typography(
        displayLarge = m.displayLarge.copy(fontFamily = Serif),
        displayMedium = m.displayMedium.copy(fontFamily = Serif),
        displaySmall = m.displaySmall.copy(fontFamily = Serif),
        headlineLarge = m.headlineLarge.copy(fontFamily = Serif),
        headlineMedium = m.headlineMedium.copy(fontFamily = Serif),
        headlineSmall = Type.title.copy(fontSize = 26.sp, lineHeight = 30.sp),
        titleLarge = m.titleLarge.copy(fontFamily = Sans),
        titleMedium = m.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        titleSmall = m.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        bodyLarge = m.bodyLarge.copy(fontFamily = Sans),
        bodyMedium = m.bodyMedium.copy(fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = m.bodySmall.copy(fontFamily = Sans),
        labelLarge = m.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        labelMedium = m.labelMedium.copy(fontFamily = Sans),
        labelSmall = m.labelSmall.copy(fontFamily = Sans)
    )
}
