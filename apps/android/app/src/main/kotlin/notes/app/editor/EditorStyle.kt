package notes.app.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import notes.app.R
import notes.app.ui.Tokens

/**
 * Sizes, faces and colours the editor's spans draw with, resolved once per
 * view. Typography follows the web app's phone sizes (editor.module.css):
 * serif title and H1/H2, a sans H3, italic serif quotes.
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

    /** Behind #tags (web: .prose [data-tag]). */
    val tagBg = tokens.accent.copy(alpha = 0.3f).toArgb()

    /**
     * Behind find matches: neutral, so they don't read as tags; the current
     * one solid accent with [onAccent] text.
     */
    val findBg = hairStrong
    val findCurrentBg = accent

    // Under half a space: two tags in a row keep a gap between their pills.
    val tagPadX = dp(2f)
    val tagRadius = dp(6f)

    /** Width one list level adds; the marker is drawn inside it. */
    val itemIndent = dp(28f)

    /** Width one quote level adds; the bar sits at its start. */
    val quoteIndent = dp(18f)
    val quoteBar = dp(2f)
    val boxSize = dp(18f)

    // Relative to the body size (17sp, set on the view) so system font scaling applies.
    val titleScale = 34f / 17f
    val headingScale = floatArrayOf(30f / 17f, 24f / 17f, 1f)
    val quoteScale = 20f / 17f
    val rawScale = 0.82f

    // A code fence is dark in both themes, as on the web (--code, --code-ink, --code-dim).
    val code = if (tokens == Tokens.Dark) 0xFF0B0C0E.toInt() else 0xFF24211F.toInt()
    val codeInk = 0xFFD8D4CD.toInt()
    val codeDim = 0xFF8F8C85.toInt()
    val codeKeyword = 0xFFC3A5F9.toInt()
    val codeString = 0xFF8BD28D.toInt()
    val codeNumber = 0xFFFBA962.toInt()
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
    val serifItalic: Typeface = Typeface.create(serif, Typeface.ITALIC)

    companion object {
        fun from(context: Context): EditorStyle {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            fun font(id: Int, fallback: Typeface) = ResourcesCompat.getFont(context, id) ?: fallback
            return EditorStyle(
                density = context.resources.displayMetrics.density,
                tokens = if (night) Tokens.Dark else Tokens.Light,
                typeface = font(R.font.notes_sans, Typeface.DEFAULT),
                serif = font(R.font.notes_serif, Typeface.SERIF),
                monospace = font(R.font.jetbrains_mono, Typeface.MONOSPACE),
                // As setTextSize(COMPLEX_UNIT_SP, 17f) does, nonlinear font scaling included.
                bodySize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f, context.resources.displayMetrics)
            )
        }
    }
}
