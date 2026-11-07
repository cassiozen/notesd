package notes.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons in the web app's style (apps/web/src/ui/icons.tsx): a 16-unit
 * grid, round caps, drawn in the current colour. The first six are the web's
 * paths; the rest are drawn to match.
 */
object Icons {
    val Plus = icon("M8 3v10M3 8h10", stroke = 1.6f)
    val Search = icon("M12 7a5 5 0 1 1-10 0a5 5 0 1 1 10 0", "M11 11l3.5 3.5", stroke = 1.6f)
    val Trash = icon("M2.5 4h11M6 4V2.5h4V4M4 4l.7 9.5h6.6L12 4")
    val Checklist = icon(
        "M5.9 4a2.4 2.4 0 1 1-4.8 0a2.4 2.4 0 1 1 4.8 0",
        "M5.9 12a2.4 2.4 0 1 1-4.8 0a2.4 2.4 0 1 1 4.8 0",
        "M8.5 4h8M8.5 12h8",
        width = 18f
    )
    val Link = icon(
        "M6.8 9.2a3 3 0 004.2 0l2.2-2.2a3 3 0 00-4.2-4.2l-1 1",
        "M9.2 6.8a3 3 0 00-4.2 0L2.8 9a3 3 0 004.2 4.2l1-1"
    )
    val Compose = icon("M13 9v4.5H2.5V3H7", "M11.5 1.8l2.7 2.7L8.5 10.2 5.6 10.5l.3-2.9z")

    val Back = icon("M10 2.5L4.5 8l5.5 5.5", stroke = 1.6f)
    val Close = icon("M4.5 4.5l7 7M11.5 4.5l-7 7", stroke = 1.6f)
    val Check = icon("M3.5 8.5l3 3 6-7", stroke = 1.6f)
    val More = dots()
    val Undo = icon("M5.5 3.5L2.5 6.5l3 3", "M2.5 6.5h7a3.5 3.5 0 0 1 0 7H8")
    val Redo = icon("M10.5 3.5l3 3-3 3", "M13.5 6.5h-7a3.5 3.5 0 0 0 0 7H8")
    val Share = icon("M8 1.8v8.2M5 4.8l3-3 3 3", "M4.5 7.5H3.5v6h9v-6h-1")
    val OpenExternal = icon("M9.5 2.5h4v4M13.5 2.5L8 8", "M12 9.5v4H2.5V4h4")
    val Edit = icon("M11.5 1.8l2.7 2.7-8.6 8.6-3.3.6.6-3.3z")
    val Chevron = icon("M6 3.5L10.5 8 6 12.5", stroke = 1.8f)
    val ChevronUp = icon("M3.5 10L8 5.5l4.5 4.5", stroke = 1.6f)
    val ChevronDown = icon("M3.5 6L8 10.5 12.5 6", stroke = 1.6f)

    /** Find and replace: two arrows turning one into the other. */
    val Replace = icon(
        "M2.5 7a5 5 0 0 1 9.2-2.7",
        "M12 1.8v2.7H9.3",
        "M13.5 9a5 5 0 0 1-9.2 2.7",
        "M4 14.2v-2.7h2.7"
    )
    val Hash = icon("M6.5 2.5L5 13.5M11 2.5l-1.5 11M3 6h10.5M2.5 10H13", stroke = 1.5f)
    val Settings = icon(
        "M2.5 5h5.5M12 5h1.5M2.5 11h1.5M8 11h5.5",
        "M11.5 5a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0",
        "M7.5 11a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0"
    )
    val Bullets = icon(
        "M6.5 4h7M6.5 8h7M6.5 12h7",
        dotsAt = listOf(3f to 4f, 3f to 8f, 3f to 12f)
    )
    val Numbers = icon(
        "M6.5 4.5h7M6.5 11.5h7",
        "M2.4 3.1l1.1-.7v4.1",
        "M2.2 9.6a1.1 1.1 0 0 1 2.2 0c0 1-2.2 1.7-2.2 3h2.3",
        stroke = 1.3f
    )
    val Image = icon(
        "M4 3h8a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M7.1 6.5a1.1 1.1 0 1 1-2.2 0a1.1 1.1 0 1 1 2.2 0",
        "M2.5 11.5l3.5-3 2.5 2 2-1.5 3 2.5"
    )
    val Quote = icon("M5.5 4.5h8M5.5 8h8M5.5 11.5h5", "M2.2 3v10", stroke = 1.5f)
    val Indent = icon("M2.5 3h11M7.5 6.5h6M7.5 9.5h6M2.5 13h11", "M2.5 6l2.2 2-2.2 2")
    val Outdent = icon("M2.5 3h11M7.5 6.5h6M7.5 9.5h6M2.5 13h11", "M4.7 6L2.5 8l2.2 2")
    val CloudOff = icon("M4.5 12.5a3 3 0 0 1-.4-6A4 4 0 0 1 11.8 5.6a3.4 3.4 0 0 1 .2 6.9z", "M2.5 2l11 12")
    val Cloud = icon("M4.5 12.5a3 3 0 0 1-.4-6A4 4 0 0 1 11.8 5.6a3.4 3.4 0 0 1 .2 6.9z")
    val Spaces = icon(square(2.5f, 2.5f), square(9f, 2.5f), square(2.5f, 9f), square(9f, 9f))
    val Mail = icon(
        "M4 3.5h8a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z",
        "M2.5 4.5L8 8.8l5.5-4.3"
    )
    val Appearance = icon(
        "M8 2.5a5.5 5.5 0 1 1 0 11a5.5 5.5 0 1 1 0-11z",
        fills = listOf("M8 2.5a5.5 5.5 0 0 1 0 11z")
    )
    val Send = icon("M14 2L7 9", "M14 2l-4.5 12L7 9 2 6.5z")
    val Info = icon("M8 2.5a5.5 5.5 0 1 1 0 11a5.5 5.5 0 1 1 0-11z", "M8 7.3v3.7", dotsAt = listOf(8f to 5f))
    val Shield = icon("M8 1.8l5 2v4c0 3-2.2 5.2-5 6.4C5.2 13 3 10.8 3 7.8v-4z")
    val Lock =
        icon("M5 7h6a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M5.5 7V5a2.5 2.5 0 0 1 5 0v2")

    private fun icon(
        vararg paths: String,
        stroke: Float = 1.4f,
        width: Float = 16f,
        dotsAt: List<Pair<Float, Float>> = emptyList(),
        fills: List<String> = emptyList()
    ): ImageVector = ImageVector.Builder(
        defaultWidth = (width * 1.5f).dp,
        defaultHeight = 24.dp,
        viewportWidth = width,
        viewportHeight = 16f
    ).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        for ((x, y) in dotsAt) addPath(addPathNodes(circle(x, y, 1.15f)), fill = SolidColor(Color.Black))
        for (d in fills) addPath(addPathNodes(d), fill = SolidColor(Color.Black))
    }.build()

    private fun dots(): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 16f,
        viewportHeight = 16f
    ).apply {
        for (x in listOf(3.2f, 8f, 12.8f)) addPath(addPathNodes(circle(x, 8f, 1.3f)), fill = SolidColor(Color.Black))
    }.build()

    /** A 4.5-unit square with rounded corners, from its top left corner. */
    private fun square(x: Float, y: Float) =
        "M${x + 1.2f} ${y}h2.1a1.2 1.2 0 0 1 1.2 1.2v2.1a1.2 1.2 0 0 1-1.2 1.2h-2.1a1.2 1.2 0 0 1-1.2-1.2v-2.1" +
            "a1.2 1.2 0 0 1 1.2-1.2z"

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
}
