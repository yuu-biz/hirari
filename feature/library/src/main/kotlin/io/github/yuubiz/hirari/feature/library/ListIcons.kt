package io.github.yuubiz.hirari.feature.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/** The icons of the file list, drawn on a 24 by 24 grid and tinted by where they are shown. */
internal object ListIcons {
    val Folder: ImageVector = icon("folder") {
        moveTo(2f, 5f)
        horizontalLineTo(9f)
        lineTo(11f, 7f)
        horizontalLineTo(22f)
        verticalLineTo(19f)
        horizontalLineTo(2f)
        close()
    }

    /** To the parent folder: an arrow up whose stem bends to the right at its foot. */
    val Up: ImageVector = lines("up") {
        moveTo(4f, 9f)
        lineTo(9f, 4f)
        lineTo(14f, 9f)
        moveTo(9f, 4f)
        verticalLineTo(14f)
        quadTo(9f, 19f, 14f, 19f)
        horizontalLineTo(20f)
    }

    val Filter: ImageVector = lines("filter") {
        moveTo(16f, 10f)
        arcTo(6f, 6f, 0f, true, true, 4f, 10f)
        arcTo(6f, 6f, 0f, true, true, 16f, 10f)
        moveTo(14.5f, 14.5f)
        lineTo(20f, 20f)
    }

    val Close: ImageVector = lines("close") {
        moveTo(6f, 6f)
        lineTo(18f, 18f)
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }
}

private fun icon(name: String, path: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(PathBuilder().apply(path).nodes, fill = SolidColor(Color.Black))
        .build()

private fun lines(name: String, path: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            PathBuilder().apply(path).nodes,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
