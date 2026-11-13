package io.github.yuubiz.hirari.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import io.github.yuubiz.hirari.core.model.ReaderBackground

/** The default `readerBackground`, 濃い灰色 (docs/spec.md section 4.10). */
val DefaultReaderBackground = Color(0xFF616161)

/** The color of `readerBackground` (docs/spec.md section 4.10), which 「テーマに従う」 takes from the theme in force. */
@Composable
fun readerBackgroundColor(background: ReaderBackground): Color = when (background) {
    ReaderBackground.Black -> Color.Black
    ReaderBackground.DarkGray -> DefaultReaderBackground
    ReaderBackground.White -> Color.White
    ReaderBackground.Theme -> MaterialTheme.colorScheme.background
    is ReaderBackground.Custom -> Color(0xFF000000.toInt() or background.color.value)
}

/** Whether [background] is light, so black reads on it and white does not. */
fun isLight(background: Color): Boolean = background.luminance() > 0.5f

/** Black or white, whichever reads on [background]: for the text a reader sets straight on its background. */
fun readableOn(background: Color): Color = if (isLight(background)) Color.Black else Color.White

/**
 * The surface of the shown bars over [background] (docs/spec.md section 4.8, バーを出す表示): a little darker, or a
 * little lighter on a near-black background. Text on it keeps the color [readableOn] gives for [background].
 */
fun barSurface(background: Color): Color = when {
    isLight(background) -> lerp(background, Color.Black, 0.07f)
    background.luminance() >= 0.03f -> lerp(background, Color.Black, 0.5f)
    else -> lerp(background, Color.White, 0.1f)
}

/**
 * The surface of a reader, filling the screen: [background] fills it around the pages and between them, also under
 * [insets], and [content] sits on it inside [insets]. [modifier] applies inside [insets]. With [edges], the areas
 * under the top and bottom insets take that color instead, so shown bars run on under the system bars.
 */
@Composable
fun ReaderBackdrop(
    background: ReaderBackground,
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets(0),
    edges: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(readerBackgroundColor(background)).insetEdges(insets, edges).windowInsetsPadding(insets).then(modifier),
        content = content,
    )
}

/** Paints the areas under the top and bottom of [insets] with [color], when there is one. */
@Composable
fun Modifier.insetEdges(insets: WindowInsets, color: Color?): Modifier {
    if (color == null) return this
    val density = LocalDensity.current
    val top = insets.getTop(density).toFloat()
    val bottom = insets.getBottom(density).toFloat()
    return drawBehind {
        if (top > 0f) drawRect(color, size = Size(size.width, top))
        if (bottom > 0f) drawRect(color, Offset(0f, size.height - bottom), Size(size.width, bottom))
    }
}
