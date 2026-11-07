package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.OverlayTextSize
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ScreenSpot
import io.github.yuubiz.hirari.core.ui.rememberStatusText
import io.github.yuubiz.hirari.core.ui.sp

/** 重ねて表示する情報 that the settings place (docs/spec.md sections 4.8 and 4.10): ページ番号 and 時刻と電池. */
data class Overlays(
    val pageNumber: ScreenSpot = ScreenSpot.Off,
    val status: ScreenSpot = ScreenSpot.Off,
    val textSize: OverlayTextSize = OverlayTextSize.Medium,
)

/** `12 / 200`, or `12-13 / 200` for a view of two pages. */
fun pageNumberText(view: PageView, pageCount: Int): String {
    val numbers = view.pages.map { it + 1 }.sorted().distinct().joinToString("-")
    return "$numbers / $pageCount"
}

private val ScreenSpot.alignment: Alignment?
    get() = when (this) {
        ScreenSpot.Off -> null
        ScreenSpot.TopLeft -> Alignment.TopStart
        ScreenSpot.TopCenter -> Alignment.TopCenter
        ScreenSpot.TopRight -> Alignment.TopEnd
        ScreenSpot.BottomLeft -> Alignment.BottomStart
        ScreenSpot.BottomCenter -> Alignment.BottomCenter
        ScreenSpot.BottomRight -> Alignment.BottomEnd
    }

/**
 * ページ番号 and 時刻と電池 at the spots [overlays] gives, inside the system bars and the display cutout. Two in one spot
 * stack, the page number first.
 */
@Composable
internal fun ReaderOverlays(overlays: Overlays, pageNumber: String) {
    if (overlays.pageNumber == ScreenSpot.Off && overlays.status == ScreenSpot.Off) return
    val status = if (overlays.status == ScreenSpot.Off) null else rememberStatusText()
    val texts = listOfNotNull(overlays.pageNumber.alignment?.let { it to pageNumber }, status?.let { text -> overlays.status.alignment?.let { it to text } })
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
        for ((alignment, shown) in texts.groupBy({ it.first }, { it.second })) {
            Column(Modifier.align(alignment), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (text in shown) OutlinedText(text, Modifier, overlays.textSize.sp)
            }
        }
    }
}
