package io.github.yuubiz.hirari.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list

val TALL = PageShape.Known(70, 100)
val WIDE = PageShape.Known(140, 100)

/** `T` is a tall page, `W` a wide page and `?` a page whose shape is not loaded yet. */
fun shapesOf(pattern: String): List<PageShape> = pattern.map {
    when (it) {
        'T' -> TALL
        'W' -> WIDE
        '?' -> PageShape.Unknown
        else -> error("unknown shape $it")
    }
}

fun spread(coverAlone: Boolean = false, direction: ReadingDirection = ReadingDirection.RightToLeft) =
    LayoutSettings(LayoutMode.Spread, direction, coverAlone)

fun splitWide(direction: ReadingDirection) = LayoutSettings(LayoutMode.SplitWide, direction, coverAlone = false)

fun layout(pattern: String, settings: LayoutSettings, anchor: Int = 0): List<PageView> {
    val shapes = shapesOf(pattern)
    return layoutViews(shapes.size, shapes::get, settings, anchor)
}

val arbShape: Arb<PageShape> = Arb.element(PageShape.Unknown, TALL, WIDE)
val arbKnownShape: Arb<PageShape.Known> = Arb.element(TALL, WIDE)

val arbShapes: Arb<List<PageShape>> = Arb.list(arbShape, 1..24)

val arbResolvedSettings: Arb<LayoutSettings> = arbitrary {
    LayoutSettings(
        mode = Arb.element(LayoutMode.Single, LayoutMode.Spread, LayoutMode.SplitWide).bind(),
        direction = Arb.enum<ReadingDirection>().bind(),
        coverAlone = Arb.boolean().bind(),
    )
}

data class Sample(val shapes: List<PageShape>, val settings: LayoutSettings, val anchor: Int) {
    val views: List<PageView> get() = layoutViews(shapes.size, shapes::get, settings, anchor)
}

val arbSample: Arb<Sample> = arbitrary {
    val shapes = arbShapes.bind()
    Sample(shapes, arbResolvedSettings.bind(), Arb.int(shapes.indices).bind())
}
