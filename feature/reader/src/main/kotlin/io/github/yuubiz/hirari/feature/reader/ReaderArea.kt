package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BarLayout
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.model.Size

/** Screen and page coordinates meet only here. Regions use screen dp; page events and drawing use page pixels. */
data class ReaderArea(val screen: Viewport, val config: ReaderConfig) {
    val bars: BarLayout = if (config.barDisplay.shown(screen.size, config.barHeightDp * screen.density)) BarLayout.Shown else BarLayout.Hidden
    val regions = ReaderRegions(Size(screen.width / screen.density, screen.height / screen.density), config.barHeightDp, bars = bars)
    val page = regions.pageArea
    val viewport = Viewport(page.width * screen.density, (page.height * screen.density).coerceAtLeast(1f), screen.density)

    fun toPage(screenPoint: Point): Point = Point(screenPoint.x - page.left * screen.density, screenPoint.y - page.top * screen.density)
}
