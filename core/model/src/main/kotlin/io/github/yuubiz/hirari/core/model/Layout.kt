package io.github.yuubiz.hirari.core.model

enum class ReadingDirection { RightToLeft, LeftToRight }

enum class LayoutMode { Single, Spread, SplitWide, Auto }

enum class ScreenOrientation { Portrait, Landscape }

data class LayoutSettings(
    val mode: LayoutMode,
    val direction: ReadingDirection,
    val coverAlone: Boolean,
) {
    fun resolve(orientation: ScreenOrientation): LayoutSettings =
        if (mode != LayoutMode.Auto) {
            this
        } else {
            copy(
                mode = when (orientation) {
                    ScreenOrientation.Landscape -> LayoutMode.Spread
                    ScreenOrientation.Portrait -> LayoutMode.SplitWide
                },
            )
        }
}

/** [First] is the half that comes first in reading order. */
enum class HalfPart { First, Second }

enum class Side { Left, Right }

/** The side that is read first: the right side of a right-to-left book. */
val ReadingDirection.leadingSide: Side
    get() = when (this) {
        ReadingDirection.RightToLeft -> Side.Right
        ReadingDirection.LeftToRight -> Side.Left
    }

fun HalfPart.side(direction: ReadingDirection): Side {
    val leading = direction.leadingSide
    return if (this == HalfPart.First) leading else leading.opposite
}

private val Side.opposite: Side get() = if (this == Side.Left) Side.Right else Side.Left

sealed interface PageView {
    val pages: List<Int>

    data class One(val page: Int) : PageView {
        override val pages: List<Int> get() = listOf(page)
    }

    data class Two(val earlier: Int, val later: Int) : PageView {
        override val pages: List<Int> get() = listOf(earlier, later)

        fun pageOn(side: Side, direction: ReadingDirection): Int =
            if (side == direction.leadingSide) earlier else later
    }

    data class Half(val page: Int, val part: HalfPart) : PageView {
        override val pages: List<Int> get() = listOf(page)
    }
}

/**
 * The views of a book in reading order (docs/spec.md section 2.5). [settings] must already be
 * resolved, so its mode is not [LayoutMode.Auto]. [anchor] always heads a view.
 */
fun layoutViews(
    pageCount: Int,
    shape: (Int) -> PageShape,
    settings: LayoutSettings,
    anchor: Int,
): List<PageView> {
    require(settings.mode != LayoutMode.Auto) { "resolve LayoutMode.Auto with LayoutSettings.resolve first" }
    if (pageCount == 0) return emptyList()
    require(anchor in 0 until pageCount) { "anchor $anchor is outside 0 until $pageCount" }
    val pages = 0 until pageCount
    return when (settings.mode) {
        LayoutMode.Single -> pages.map { PageView.One(it) }
        LayoutMode.SplitWide -> pages.flatMap {
            if (shape(it).isWide) {
                listOf(PageView.Half(it, HalfPart.First), PageView.Half(it, HalfPart.Second))
            } else {
                listOf(PageView.One(it))
            }
        }
        LayoutMode.Spread -> spread(pageCount, shape, settings.coverAlone, anchor)
        LayoutMode.Auto -> error("unreachable")
    }
}

private fun spread(pageCount: Int, shape: (Int) -> PageShape, coverAlone: Boolean, anchor: Int): List<PageView> {
    fun pairable(page: Int) = !shape(page).isWide && !(coverAlone && page == 0)

    val before = ArrayDeque<PageView>()
    var last = anchor - 1
    while (last >= 0) {
        if (last >= 1 && pairable(last) && pairable(last - 1)) {
            before.addFirst(PageView.Two(last - 1, last))
            last -= 2
        } else {
            before.addFirst(PageView.One(last))
            last -= 1
        }
    }

    val after = mutableListOf<PageView>()
    var first = anchor
    while (first < pageCount) {
        if (first + 1 < pageCount && pairable(first) && pairable(first + 1)) {
            after += PageView.Two(first, first + 1)
            first += 2
        } else {
            after += PageView.One(first)
            first += 1
        }
    }
    return before + after
}
