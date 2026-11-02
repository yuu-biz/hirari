package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.Size

/** Positions closer than this, in pixels, count as the same. */
private const val SAME_PX = 0.5f

/**
 * A view laid out on screen at a zoom [factor] over its scale mode. [pages] are the page rectangles in screen
 * pixels, in reading order, with the view's top-left corner at the origin. A position is the screen's top-left
 * corner in the same space, as stop points are.
 */
data class ViewGeometry(
    val view: PageView,
    val pages: List<Rect>,
    val size: Size,
    val modeScale: Scale,
    val fit: Float,
    val factor: Float,
    val viewport: Size,
    val capAtOriginal: Boolean,
) {
    val overflows: Boolean get() = size.width > viewport.width + SAME_PX || size.height > viewport.height + SAME_PX

    val limits: ZoomLimits get() = ZoomLimits(modeScale, fit, capAtOriginal)

    /** [position] moved into the range where the screen stays inside the view, or centered on an axis that fits. */
    fun clamp(position: Point): Point = Point(axis(position.x, size.width, viewport.width), axis(position.y, size.height, viewport.height))

    /** How far the view reaches past the left and right edges of the screen at [position], in pixels. */
    fun hidden(position: Point): Pair<Float, Float> = Pair(position.x, size.width - viewport.width - position.x)

    fun stops(config: ReaderConfig): StopPath = StopPath.of(pages, viewport, config.stopMaxStep, config.stopOrder, config.direction)

    private fun axis(at: Float, content: Float, screen: Float): Float =
        if (content <= screen) (content - screen) / 2f else at.coerceIn(0f, content - screen)

    companion object {
        /** `null` until the shape of every page of [view] is known. */
        fun of(
            view: PageView,
            shapes: List<PageShape>,
            config: ReaderConfig,
            viewport: Viewport,
            factor: Float,
        ): ViewGeometry? {
            if (view.pages.any { shapes[it] !is PageShape.Known }) return null
            val shape = { page: Int -> shapes[page] as PageShape.Known }
            val screen = viewport.size
            val bare = viewContent(view, shape, config.direction)
            val bareScale = modeScale(config.scaleMode, bare.size, screen, config.capAtOriginal)
            val content = if (view is PageView.Two) viewContent(view, shape, config.direction, config.gutterPx / bareScale.x) else bare
            val mode = modeScale(config.scaleMode, content.size, screen, config.capAtOriginal)
            val fit = modeScale(ScaleMode.FitScreen, content.size, screen, capAtOriginal = false).smaller
            val scale = mode * factor
            return ViewGeometry(
                view = view,
                pages = content.pagesAt(scale),
                size = Size(content.size.width * scale.x, content.size.height * scale.y),
                modeScale = mode,
                fit = fit,
                factor = factor,
                viewport = screen,
                capAtOriginal = config.capAtOriginal,
            )
        }
    }
}
