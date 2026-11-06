package notes.app.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import notes.app.R
import notes.app.ui.CurrentTheme
import notes.app.ui.Tokens
import notes.app.ui.themes.ThemeCheckMark
import notes.app.ui.themes.ThemeColor
import notes.app.ui.themes.ThemeCorner

/**
 * Sizes, faces and colours the editor's spans draw with, resolved once per
 * view from the picked theme (shared/themes). Typography follows the web app's phone sizes
 * (editor.module.css): title and H1/H2 in the theme's title face, a body-face H3, quotes in the
 * title face as the theme sets them.
 */
class EditorStyle(
    val density: Float,
    val tokens: Tokens,
    val typeface: Typeface,
    val serif: Typeface = Typeface.SERIF,
    val monospace: Typeface = Typeface.MONOSPACE,
    /** The view's body size in px: 17sp, scaled as the view scales it. */
    val bodySize: Float = 17f * density
) {
    fun dp(v: Float) = v * density

    private val theme = tokens.theme
    private fun ThemeColor.argb() = resolve(tokens.dark).toArgb()

    val ink = tokens.ink.toArgb()
    val ink2 = tokens.ink2.toArgb()
    val ink3 = tokens.ink3.toArgb()
    val accent = tokens.accent.toArgb()
    val accentDeep = tokens.accentDeep.toArgb()
    val onAccent = tokens.onAccent.toArgb()
    val tint = tokens.tint.toArgb()
    val link = tokens.link.toArgb()
    val hover = tokens.hover.toArgb()
    val hairStrong = tokens.hairStrong.toArgb()

    /** Behind #tags (web: .prose [data-tag]); none where the style sets tags as plain coloured text. */
    val tagBg: Int? = theme.colors.tagBackground?.argb()

    /** #tags' text; null keeps the text's own colour. */
    val tagInk: Int? = theme.colors.tagInk?.argb()

    /**
     * Behind find matches: neutral, so they don't read as tags; the current
     * one solid accent with [onAccent] text.
     */
    val findBg = hairStrong
    val findCurrentBg = accent

    // Under half a space: two tags in a row keep a gap between their pills.
    val tagPadX = dp(2f)
    val tagRadius = dp(theme.shape.tagRadius)

    /** Width one list level adds; the marker is drawn inside it. */
    val itemIndent = dp(28f)

    /** Width one quote level adds; the bar sits at its start. */
    val quoteIndent = dp(18f)
    val quoteBar = dp(2f)
    val boxSize = dp(18f)

    /** A ticked box: an accent ring with a dot in it, or filled; round, or with these corners. */
    val checkFill = theme.shape.checkMark == ThemeCheckMark.Fill
    val checkRadius: Float? = (theme.shape.checkCorner as? ThemeCorner.Radius)?.let { dp(it.radius) }

    /** A ticked task's text is struck through in styles that do it (Flush), not just dimmed. */
    val doneStrike = theme.shape.doneStrikethrough

    // Relative to the body size (17sp, set on the view) so system font scaling applies; the title
    // sizes as the theme's title face sets them (the rules were written for Instrument Serif).
    private val title = theme.title
    val titleScale = 34f / 17f * title.scale
    val headingScale = floatArrayOf(30f / 17f * title.scale, 24f / 17f * title.scale, 1f)
    val quoteScale = 20f / 17f * title.scale
    val rawScale = 0.82f
    val titleTracking = -0.015f + title.tracking
    val headingTracking = title.tracking

    /**
     * The title face's variation settings for text at [sizePx]: weight, the theme's axes, and opsz
     * at the size (in points, as a browser sets it) for a face with optical sizes.
     */
    fun titleAxes(sizePx: Float, weight: Float = title.weight): String = buildList {
        add("'wght' $weight")
        title.axes.forEach { (axis, value) -> add("'$axis' $value") }
        if (title.typeface.opticalSizing) add("'opsz' ${sizePx / density}")
    }.joinToString()

    val quoteWeight = title.quoteWeight

    // A code fence is dark in every palette, as on the web (--code, --code-ink, --code-dim).
    // Opaque: the web's see-through block lets its backdrop glow show; on a phone's page it only washes it out.
    val code = theme.colors.code.argb() or 0xFF000000.toInt()
    val codeInk = theme.colors.codeInk.argb()
    val codeDim = theme.colors.codeDim.argb()
    val codeKeyword = theme.colors.syntaxKeyword.argb()
    val codeString = theme.colors.syntaxString.argb()
    val codeNumber = theme.colors.syntaxNumber.argb()
    val codeLabelScale = 11f / 17f

    /** Unpainted space above a raw block, so two in a row stay apart, and its text's inset. */
    val rawGap = dp(8f)
    val rawPad = dp(10f)

    /** Four monospace spaces, as tab-size: 4 on the web. */
    val tabWidth = Paint().apply {
        typeface = monospace
        textSize = bodySize * rawScale
    }.measureText("    ").toInt()

    /** The view's line spacing multiplier; spans that size lines account for it. */
    val lineSpacing = 1.5f

    val semibold: Typeface = Typeface.create(typeface, 600, false)

    /** The title face as titles are set: its weight, upright or italic. */
    val titleFace: Typeface = Typeface.create(serif, title.weight.toInt(), title.italic)
    val serifItalic: Typeface = Typeface.create(serif, title.quoteWeight.toInt(), title.quoteItalic)

    companion object {
        fun from(context: Context): EditorStyle {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val theme = CurrentTheme.theme
            fun font(id: Int, fallback: Typeface) = ResourcesCompat.getFont(context, id) ?: fallback
            return EditorStyle(
                density = context.resources.displayMetrics.density,
                tokens = Tokens.of(theme, night),
                typeface = font(theme.body.family, Typeface.DEFAULT),
                serif = font(theme.title.typeface.family, Typeface.SERIF),
                monospace = font(R.font.jetbrains_mono, Typeface.MONOSPACE),
                // As setTextSize(COMPLEX_UNIT_SP, 17f) does, nonlinear font scaling included.
                bodySize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f, context.resources.displayMetrics)
            )
        }
    }
}
