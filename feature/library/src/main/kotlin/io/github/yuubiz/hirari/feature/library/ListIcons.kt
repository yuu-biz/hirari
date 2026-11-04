package io.github.yuubiz.hirari.feature.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
}

private fun icon(name: String, path: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(PathBuilder().apply(path).nodes, fill = SolidColor(Color.Black))
        .build()
