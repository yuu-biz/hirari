package io.github.yuubiz.hirari.core.ui

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.SystemGestureEdges

/**
 * Where system gestures start on this screen, in dp (docs/spec.md section 4.8, 画面の領域). From Android 10 the window
 * reports them; before, only the bars come out, from the top and bottom edges.
 */
@Composable
fun systemGestureEdges(): SystemGestureEdges {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return SystemGestureEdges.Fixed
    val density = LocalDensity.current
    val mandatory = WindowInsets.mandatorySystemGestures
    val gestures = WindowInsets.systemGestures
    fun dp(px: Int) = px / density.density
    return SystemGestureEdges.of(
        mandatoryTop = dp(mandatory.getTop(density)),
        mandatoryBottom = dp(mandatory.getBottom(density)),
        left = dp(gestures.getLeft(density, LayoutDirection.Ltr)),
        right = dp(gestures.getRight(density, LayoutDirection.Ltr)),
    )
}

/**
 * Keeps the page bar, [barHeightDp] tall at the bottom of the screen, out of the system's back gesture, so a slide to
 * the ends of the screen stays the reader's (docs/spec.md section 4.8, 画面の領域). It draws nothing and takes no touch.
 */
@Composable
fun PageBarGestureExclusion(barHeightDp: Float) {
    Spacer(
        Modifier
            .fillMaxSize()
            .wrapContentHeight(Alignment.Bottom)
            .height(barHeightDp.dp)
            .fillMaxWidth()
            .systemGestureExclusion(),
    )
}
