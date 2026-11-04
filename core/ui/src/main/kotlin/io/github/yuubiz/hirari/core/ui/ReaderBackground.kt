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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
 * The surface of a reader, filling the screen: [background] fills it around the pages and between them, also under
 * [insets], and [content] sits on it inside [insets]. [modifier] applies inside [insets].
 */
@Composable
fun ReaderBackdrop(background: ReaderBackground, modifier: Modifier = Modifier, insets: WindowInsets = WindowInsets(0), content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(readerBackgroundColor(background)).windowInsetsPadding(insets).then(modifier), content = content)
}
