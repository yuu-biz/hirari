package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.ScreenOrientation
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.core.model.VolumeKeys

/**
 * What the reader reads from the settings: the global settings of docs/spec.md section 4.10, with the changes
 * made from the command bar during this session. Settings that [GlobalSettings] does not hold yet keep their
 * defaults here.
 */
data class ReaderConfig(
    val layout: LayoutSettings = LayoutSettings(LayoutMode.Auto, ReadingDirection.RightToLeft, coverAlone = true),
    val scaleMode: ScaleMode = ScaleMode.FitScreen,
    val capAtOriginal: Boolean = false,
    val turnEffect: TurnEffect = TurnEffect.Cover,
    val turnDurationMs: Long = 150,
    val stopOrder: StopOrder = StopOrder.RowsFirst,
    val stopMaxStep: Float = 0.35f,
    val tapZones: TapZones = TapZones(),
    val tapAdvancesStops: Boolean = false,
    val swipeToTurn: Boolean = true,
    val volumeKeys: VolumeKeys = VolumeKeys.DownAdvances,
    val barHeightDp: Float = 60f,
    /** The gap between the pages of a pair, in screen pixels (docs/spec.md section 4.8, 見開き). */
    val gutterPx: Float = 2f,
) {
    val direction: ReadingDirection get() = layout.direction
    val mirrored: Boolean get() = tapZones.mirrored
    val keys: KeyBindings get() = KeyBindings(direction, mirrored, volumeKeys)

    companion object {
        fun of(settings: GlobalSettings): ReaderConfig = ReaderConfig(
            layout = LayoutSettings(settings.layoutMode, settings.readingDirection, settings.coverAlone),
            scaleMode = settings.scaleMode,
            turnEffect = settings.turnEffect,
            turnDurationMs = settings.turnDurationMs.toLong(),
            stopOrder = settings.stopOrder,
            stopMaxStep = settings.stopMaxStepPercent / 100f,
            tapZones = TapZones(settings.tapLayout, settings.tapBandPercent, settings.mirrorControls),
            swipeToTurn = settings.swipeToTurn,
            volumeKeys = settings.volumeKeys,
            barHeightDp = settings.barHeightDp.toFloat(),
        )
    }
}

/** The reader's drawing area in pixels, and the pixels per dp. */
data class Viewport(val width: Float, val height: Float, val density: Float) {
    val size: Size get() = Size(width, height)
    val orientation: ScreenOrientation get() = if (width > height) ScreenOrientation.Landscape else ScreenOrientation.Portrait
}
