package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.GutterStyle
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.KeyBindings
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.LoupeFrame
import io.github.yuubiz.hirari.core.model.LoupeZoom
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.ScreenOrientation
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.TapZones
import io.github.yuubiz.hirari.core.model.TrimStrength
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.core.model.VolumeKeys
import kotlin.math.roundToLong

/**
 * What the reader reads from the settings: the global settings of docs/spec.md section 4.10 under the book's
 * overrides of section 2.9, with the changes made from the command bar during this session.
 */
data class ReaderConfig(
    val layout: LayoutSettings = LayoutSettings(LayoutMode.Spread, ReadingDirection.RightToLeft, coverAlone = false),
    val scaleMode: ScaleMode = ScaleMode.FitPage,
    val capAtOriginal: Boolean = false,
    val turnEffect: TurnEffect = TurnEffect.Cover,
    val turnDurationMs: Long = 150,
    val stopOrder: StopOrder = StopOrder.RowsFirst,
    val stopMaxStep: Float = 0.35f,
    val tapZones: TapZones = TapZones(),
    val tapAdvancesStops: Boolean = true,
    val swipeToTurn: Boolean = true,
    val volumeKeys: VolumeKeys = VolumeKeys.DownAdvances,
    val barHeightDp: Float = 60f,
    val gutter: GutterStyle = GutterStyle.Gap,
    val commandBarItems: List<ReaderCommand> = ReaderCommand.Defaults,
    /** The book's rotation (docs/spec.md section 2.9), which has no global setting. */
    val rotation: Rotation = Rotation.None,
    val orientation: ReaderOrientation = ReaderOrientation.System,
    val adjust: ImageAdjust = ImageAdjust(),
    /** `autoPlaySeconds`: how long 自動再生 rests between steps. */
    val autoPlayMs: Long = 1500,
    val loupeZoom: LoupeZoom = LoupeZoom.View200,
    val loupeFrame: LoupeFrame = LoupeFrame.Small,
    val loupeHoldMs: Long = 800,
    val atBookEnd: BookEndAction = BookEndAction.Ask,
    /** 余白トリミング. */
    val trim: TrimStrength = TrimStrength.Off,
    /** `turnHaptics`: a short tick when a turn changes the view. */
    val turnHaptics: Boolean = false,
    /** 画面の明るさ in percent, or `null` to follow the device. */
    val brightness: Int? = null,
    /**
     * What the command bar switched in this session, which a change of the global settings leaves as it is.
     * [SessionSwitch.MirrorControls] is in it while 操作の反転 is flipped from the global setting.
     */
    val switched: Set<SessionSwitch> = emptySet(),
) {
    /** The gap between the pages of a pair, in screen pixels (docs/spec.md section 4.8, 見開き). */
    val gutterPx: Float get() = if (gutter == GutterStyle.Gap) 2f else 0f
    val direction: ReadingDirection get() = layout.direction
    val mirrored: Boolean get() = tapZones.mirrored
    val keys: KeyBindings get() = KeyBindings(direction, mirrored, volumeKeys, vertical)

    /** 縦に連続, which scrolls the pages as one strip (docs/spec.md section 2.5). */
    val vertical: Boolean get() = layout.mode == LayoutMode.Vertical

    companion object {
        fun of(settings: GlobalSettings, overrides: BookOverrides = BookOverrides()): ReaderConfig = ReaderConfig(
            layout = LayoutSettings(
                overrides.layoutMode ?: settings.layoutMode,
                overrides.direction ?: settings.readingDirection,
                overrides.coverAlone ?: settings.coverAlone,
            ),
            scaleMode = overrides.scaleMode ?: settings.scaleMode,
            capAtOriginal = settings.capAtOriginal,
            turnEffect = settings.turnEffect,
            turnDurationMs = settings.turnDurationMs.toLong(),
            stopOrder = settings.stopOrder,
            stopMaxStep = settings.stopMaxStepPercent / 100f,
            tapZones = TapZones(settings.tapLayout, settings.tapBandPercent, settings.mirrorControls),
            tapAdvancesStops = settings.tapAdvancesStops,
            swipeToTurn = settings.swipeToTurn,
            volumeKeys = settings.volumeKeys,
            barHeightDp = settings.barHeightDp.toFloat(),
            gutter = settings.gutterStyle,
            commandBarItems = settings.commandBarItems,
            rotation = overrides.rotation ?: Rotation.None,
            orientation = settings.readerOrientation,
            adjust = overrides.adjust ?: settings.adjust,
            autoPlayMs = (settings.autoPlaySeconds * 1000).roundToLong(),
            loupeZoom = settings.loupeZoom,
            loupeFrame = settings.loupeFrame,
            loupeHoldMs = settings.loupeHoldMs.toLong(),
            atBookEnd = settings.atBookEnd,
            trim = overrides.trim ?: settings.trimStrength,
            turnHaptics = settings.turnHaptics,
            brightness = settings.readerBrightness,
        )
    }
}

/**
 * This reader's setup once the global settings became [settings] while it is open, for a book with [overrides]
 * (docs/spec.md section 4.8, メニュー). What the session switched for itself, 画面の向き, 操作の反転, 綴じ目の表示, and
 * 停止点の順序 ([ReaderConfig.switched]), stays switched, even when the global setting now holds the same value. 操作の
 * 反転 stays flipped from the global setting, whatever that becomes. Every other value is what [settings] and
 * [overrides] give.
 */
fun ReaderConfig.following(settings: GlobalSettings, overrides: BookOverrides): ReaderConfig {
    val next = ReaderConfig.of(settings, overrides)
    fun <T> kept(switch: SessionSwitch, session: T, global: T): T = if (switch in switched) session else global
    return next.copy(
        orientation = kept(SessionSwitch.Orientation, orientation, next.orientation),
        tapZones = next.tapZones.copy(mirrored = next.mirrored != (SessionSwitch.MirrorControls in switched)),
        gutter = kept(SessionSwitch.Gutter, gutter, next.gutter),
        stopOrder = kept(SessionSwitch.StopOrder, stopOrder, next.stopOrder),
        switched = switched,
    )
}

/**
 * These overrides with what the reader changed from [before] to [after], which 2.9 of docs/spec.md keeps for the
 * book: 綴じ方向, 組み方, 表紙の単独表示, 拡大モード, 余白トリミング, the rotation, and 画像補正. A value changed back
 * to what [global] has is stored as no override, so the book follows the global setting again. A value the change
 * left alone keeps its override.
 */
fun BookOverrides.changedBy(before: ReaderConfig, after: ReaderConfig, global: ReaderConfig): BookOverrides = copy(
    direction = direction.changed(before.direction, after.direction, global.direction),
    layoutMode = layoutMode.changed(before.layout.mode, after.layout.mode, global.layout.mode),
    coverAlone = coverAlone.changed(before.layout.coverAlone, after.layout.coverAlone, global.layout.coverAlone),
    scaleMode = scaleMode.changed(before.scaleMode, after.scaleMode, global.scaleMode),
    rotation = rotation.changed(before.rotation, after.rotation, global.rotation),
    adjust = adjust.changed(before.adjust, after.adjust, global.adjust),
    trim = trim.changed(before.trim, after.trim, global.trim),
)

private fun <T : Any> T?.changed(before: T, after: T, global: T): T? = if (after == before) this else after.takeUnless { it == global }

/** The global `adjust*` settings as one value. */
val GlobalSettings.adjust: ImageAdjust
    get() = ImageAdjust(adjustLevel.coerceIn(GlobalSettings.ADJUST_LEVEL), adjustSharpen, adjustMono, adjustNegative)

/** A value the command bar switches for this book's reading session only (docs/spec.md section 2.9), and never stores. */
enum class SessionSwitch { Orientation, MirrorControls, Gutter, StopOrder }

/** The reader's drawing area in pixels, and the pixels per dp. */
data class Viewport(val width: Float, val height: Float, val density: Float) {
    val size: Size get() = Size(width, height)
    val orientation: ScreenOrientation get() = if (width > height) ScreenOrientation.Landscape else ScreenOrientation.Portrait
}
