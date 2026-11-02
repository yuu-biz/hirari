package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.TapLayout
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.core.model.VolumeKeys
import kotlinx.serialization.Serializable

/**
 * The global settings of docs/spec.md section 4.10 that M1 uses. Property names are the internal names
 * of the spec and the keys of the stored JSON, so renaming one loses what users chose.
 */
@Serializable
data class GlobalSettings(
    val readingDirection: ReadingDirection = ReadingDirection.RightToLeft,
    val layoutMode: LayoutMode = LayoutMode.Auto,
    val coverAlone: Boolean = true,
    val scaleMode: ScaleMode = ScaleMode.FitScreen,
    val turnEffect: TurnEffect = TurnEffect.Cover,
    val turnDurationMs: Int = 150,
    val stopOrder: StopOrder = StopOrder.RowsFirst,
    val stopMaxStepPercent: Int = 35,
    val tapLayout: TapLayout = TapLayout.OneSideBand,
    val tapBandPercent: Int = 10,
    val mirrorControls: Boolean = false,
    val swipeToTurn: Boolean = true,
    val volumeKeys: VolumeKeys = VolumeKeys.DownAdvances,
    val barHeightDp: Int = 60,
) {
    /** Moves each number to the nearest allowed value. */
    fun normalized(): GlobalSettings = copy(
        turnDurationMs = turnDurationMs.snapTo(TURN_DURATION_MS),
        stopMaxStepPercent = stopMaxStepPercent.snapTo(STOP_MAX_STEP_PERCENT),
        tapBandPercent = tapBandPercent.snapTo(
            if (tapLayout == TapLayout.BothSideBands || tapLayout == TapLayout.TopAndBottomBands) TAP_TWO_BAND_PERCENT else TAP_BAND_PERCENT,
        ),
        barHeightDp = barHeightDp.snapTo(BAR_HEIGHT_DP),
    )

    companion object {
        val TURN_DURATION_MS = 0..1000 step 50
        val STOP_MAX_STEP_PERCENT = 5..100 step 5
        val TAP_BAND_PERCENT = 10..90 step 10

        /** Each of two bands stays at or under 45% (docs/spec.md section 4.10). */
        val TAP_TWO_BAND_PERCENT = 10..40 step 10
        val BAR_HEIGHT_DP = 40..120 step 4
    }
}

private fun Int.snapTo(allowed: IntProgression): Int {
    val offset = coerceIn(allowed.first, allowed.last) - allowed.first
    return allowed.first + (offset + allowed.step / 2) / allowed.step * allowed.step
}
