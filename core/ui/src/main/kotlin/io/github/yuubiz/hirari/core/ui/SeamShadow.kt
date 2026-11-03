package io.github.yuubiz.hirari.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs

/** The share of a page's width that the shadow of 綴じ目の表示 covers (docs/spec.md section 4.8, 見開き). */
const val SEAM_SHADOW_SHARE = 0.05f
private val SeamShadow = Color(0x59000000)

/** The 影 of 綴じ目の表示: a shadow darkest at [seamX] that fades out toward [fadeX]. */
fun DrawScope.drawSeamShadow(seamX: Float, fadeX: Float, top: Float, height: Float, alpha: Float = 1f) {
    val brush = Brush.horizontalGradient(listOf(SeamShadow, Color.Transparent), startX = seamX, endX = fadeX)
    drawRect(brush, Offset(minOf(seamX, fadeX), top), Size(abs(fadeX - seamX), height), alpha)
}
