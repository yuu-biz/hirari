package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.ScreenOrientation
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.core.model.VolumeKeys

/**
 * What the reader reads from the settings: the global settings of docs/spec.md section 4.10 under the book's
 * overrides of section 2.9, with the changes made from the command bar during this session.
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
    val gutter: Gutter = Gutter.Gap,
    val commandBarItems: List<ReaderCommand> = ReaderCommand.Defaults,
) {
    /** The gap between the pages of a pair, in screen pixels (docs/spec.md section 4.8, 見開き). */
    val gutterPx: Float get() = if (gutter == Gutter.Gap) 2f else 0f
    val direction: ReadingDirection get() = layout.direction
    val mirrored: Boolean get() = tapZones.mirrored
    val keys: KeyBindings get() = KeyBindings(direction, mirrored, volumeKeys)

    companion object {
        fun of(settings: GlobalSettings, overrides: BookOverrides = BookOverrides()): ReaderConfig = ReaderConfig(
            layout = LayoutSettings(
                overrides.layoutMode ?: settings.layoutMode,
                overrides.direction ?: settings.readingDirection,
                overrides.coverAlone ?: settings.coverAlone,
            ),
            scaleMode = overrides.scaleMode ?: settings.scaleMode,
            turnEffect = settings.turnEffect,
            turnDurationMs = settings.turnDurationMs.toLong(),
            stopOrder = settings.stopOrder,
            stopMaxStep = settings.stopMaxStepPercent / 100f,
            tapZones = TapZones(settings.tapLayout, settings.tapBandPercent, settings.mirrorControls),
            swipeToTurn = settings.swipeToTurn,
            volumeKeys = settings.volumeKeys,
            barHeightDp = settings.barHeightDp.toFloat(),
            commandBarItems = settings.commandBarItems,
        )
    }
}

/** 綴じ目の表示 (docs/spec.md section 4.8, 見開き). [Shadow] darkens the bound edge of each page of a pair. */
enum class Gutter { None, Gap, Shadow }

/** The reader's drawing area in pixels, and the pixels per dp. */
data class Viewport(val width: Float, val height: Float, val density: Float) {
    val size: Size get() = Size(width, height)
    val orientation: ScreenOrientation get() = if (width > height) ScreenOrientation.Landscape else ScreenOrientation.Portrait
}
