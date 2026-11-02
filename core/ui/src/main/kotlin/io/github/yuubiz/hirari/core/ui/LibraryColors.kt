package io.github.yuubiz.hirari.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The name colors of a file list by read state and kind (docs/spec.md section 4.3). */
@Immutable
data class LibraryColors(
    val stateUnread: Color,
    val stateReading: Color,
    val stateRead: Color,
    val stateFolder: Color,
    val stateImage: Color,
    val itemDetail: Color,
    val itemPressed: Color,
) {
    companion object {
        val Dark = LibraryColors(
            stateUnread = Color(0xFFFFFFFF),
            stateReading = Color(0xFF00FFFF),
            stateRead = Color(0xFF808080),
            stateFolder = Color(0xFF00FF00),
            stateImage = Color(0xFFFFFF00),
            itemDetail = Color(0xFF9F9F9F),
            itemPressed = Color(0xFF0040C0),
        )

        /** The same hues, darker, so the names read on a light background. */
        val Light = LibraryColors(
            stateUnread = Color(0xFF000000),
            stateReading = Color(0xFF007A7A),
            stateRead = Color(0xFF6E6E6E),
            stateFolder = Color(0xFF007A00),
            stateImage = Color(0xFF7A6A00),
            itemDetail = Color(0xFF5F5F5F),
            itemPressed = Color(0xFFB3C6F2),
        )
    }
}

val LocalLibraryColors = staticCompositionLocalOf { LibraryColors.Dark }
