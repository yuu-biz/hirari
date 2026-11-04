package io.github.yuubiz.hirari.core.ui

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
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
 * How far in from the edges of the screen a reader lies (docs/spec.md section 4.8, 画面の領域): not at all while the
 * system bars are hidden, and inside the shown bars and the display cutout otherwise.
 */
@Composable
fun readerInsets(hideSystemBars: Boolean): WindowInsets =
    if (hideSystemBars) WindowInsets(0) else WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * Where system gestures start in a reader laid out by [readerInsets], in dp (docs/spec.md section 4.8, 画面の領域). From
 * Android 10 the window reports them; before, only the bars come out, from the top and bottom edges.
 */
@Composable
fun systemGestureEdges(hideSystemBars: Boolean): SystemGestureEdges {
    val density = LocalDensity.current
    fun dp(px: Int) = px / density.density
    val screen = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        SystemGestureEdges.Fixed
    } else {
        val mandatory = WindowInsets.mandatorySystemGestures
        val gestures = WindowInsets.systemGestures
        SystemGestureEdges.of(
            mandatoryTop = dp(mandatory.getTop(density)),
            mandatoryBottom = dp(mandatory.getBottom(density)),
            left = dp(gestures.getLeft(density, LayoutDirection.Ltr)),
            right = dp(gestures.getRight(density, LayoutDirection.Ltr)),
        )
    }
    if (hideSystemBars) return screen
    val bars = readerInsets(hideSystemBars = false)
    return screen.insideShownBars(
        bottom = dp(bars.getBottom(density)),
        left = dp(bars.getLeft(density, LayoutDirection.Ltr)),
        right = dp(bars.getRight(density, LayoutDirection.Ltr)),
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
