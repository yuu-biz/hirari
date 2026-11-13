package io.github.yuubiz.hirari.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The icons of the reader bars and panels (docs/spec.md section 4.8), drawn on a 24 grid with 2-unit round strokes. */
object BarIcons {
    val Close by icon(line("M6,6L18,18M18,6L6,18"))
    val Menu by icon(dot(12f, 5f), dot(12f, 12f), dot(12f, 19f))
    val More by icon(dot(5f, 12f), dot(12f, 12f), dot(19f, 12f))
    val View by icon(line("M4,7H20M4,12H20M4,17H20"), dot(9f, 7f, 2.6f), dot(15f, 12f, 2.6f), dot(7f, 17f, 2.6f))
    val ShiftPairing by icon(line("M3,3H11V14H3ZM13,3H21V14H13Z"), line("M5,19H18M15,16L18,19L15,22"))
    val Zoom by icon(line("M4.5,10.5A6,6 0 1,0 16.5,10.5A6,6 0 1,0 4.5,10.5ZM15,15L20.5,20.5M8,10.5H13M10.5,8V13"))
    val Trim by icon(line("M7,3V17H21M3,7H17V21"))
    val Layout by icon(line("M3,5H21V19H3ZM12,5V19"))
    val Direction by icon(line("M12,3V21M9.5,12H3M5.5,9.5L3,12L5.5,14.5M14.5,12H21M18.5,9.5L21,12L18.5,14.5"))
    val Rotate by icon(line("M20,12A8,8 0 1,1 17.66,6.34M20,3.5V7.5H16"))
    val Orientation by icon(line("M4,3H12V18H4ZM12,14H21V21H8V18"))
    val AddBookmark by icon(line("M6,3H18V21L12,16.5L6,21ZM12,7V13M9,10H15"))
    val Bookmarks by icon(line("M3,3H11V17L7,14L3,17ZM15,6H21M15,11H21M15,16H21"))
    val AutoPlay by icon(line("M8,5L19,12L8,19Z"))
    val ReverseOrder by icon(line("M7,4V20M4,7L7,4L10,7M17,20V4M14,17L17,20L20,17"))
    val ImageAdjust by icon(line("M3,12A9,9 0 1,0 21,12A9,9 0 1,0 3,12Z"), fill("M12,3A9,9 0 0,1 12,21Z"))
    val MirrorControls by icon(line("M4,8H19M16,5L19,8L16,11M20,16H5M8,13L5,16L8,19"))
    val StopOrder by icon(line("M5,6H19L5,18H19"), dot(5f, 6f), dot(19f, 18f))
    val Gutter by icon(line("M3,5H10V19H3ZM14,5H21V19H14Z"), fill("M11,5H13V19H11Z"))
    val EditItems by icon(line("M4,20H8L19,9L15,5L4,16ZM13,7L17,11"))
    val Breath by icon(line("M3,9H13A3,3 0 1,0 10,6M3,15H16A3,3 0 1,1 13,18"))
    val SetCover by icon(line("M5,3H19V21H5ZM5,16L9,12L12,15L14,13L19,18"), dot(14.5f, 8f, 1.6f))
    val Share by icon(line("M15.5,5A2.5,2.5 0 1,0 20.5,5A2.5,2.5 0 1,0 15.5,5ZM3.5,12A2.5,2.5 0 1,0 8.5,12A2.5,2.5 0 1,0 3.5,12ZM15.5,19A2.5,2.5 0 1,0 20.5,19A2.5,2.5 0 1,0 15.5,19ZM8.2,10.8L15.8,6.2M8.2,13.2L15.8,17.8"))
    val Settings by icon(line("M10.21,4.82L10.61,2.10L13.39,2.10L13.79,4.82L15.81,5.66L18.02,4.01L19.99,5.98L18.34,8.19L19.18,10.21L21.90,10.61L21.90,13.39L19.18,13.79L18.34,15.81L19.99,18.02L18.02,19.99L15.81,18.34L13.79,19.18L13.39,21.90L10.61,21.90L10.21,19.18L8.19,18.34L5.98,19.99L4.01,18.02L5.66,15.81L4.82,13.79L2.10,13.39L2.10,10.61L4.82,10.21L5.66,8.19L4.01,5.98L5.98,4.01L8.19,5.66ZM9,12A3,3 0 1,0 15,12A3,3 0 1,0 9,12Z"))
    val Contents by icon(line("M9,6H20M9,12H20M9,18H20"), dot(5f, 6f), dot(5f, 12f), dot(5f, 18f))
    val Search by icon(line("M4,10.5A6.5,6.5 0 1,0 17,10.5A6.5,6.5 0 1,0 4,10.5ZM15.5,15.5L20.5,20.5"))
    val TextSize by icon(line("M2.5,19L7.5,5L12.5,19M4.5,14H10.5M14,19L17.5,10L21,19M15.2,16H19.8"))
}

private class Part(val path: String, val filled: Boolean)

private fun line(path: String) = Part(path, filled = false)

private fun fill(path: String) = Part(path, filled = true)

private fun dot(x: Float, y: Float, r: Float = 1.8f) =
    fill("M${x - r},${y}A$r,$r 0 1,0 ${x + r},${y}A$r,$r 0 1,0 ${x - r},${y}Z")

private fun icon(vararg parts: Part) = lazy {
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        for (part in parts) {
            val nodes = addPathNodes(part.path)
            if (part.filled) {
                addPath(nodes, fill = SolidColor(Color.Black))
            } else {
                addPath(
                    nodes, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
    }.build()
}
