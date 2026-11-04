package io.github.yuubiz.hirari.core.model

enum class ReadingDirection { RightToLeft, LeftToRight }

/** [Vertical] is 縦に連続: every page is a view of its own, and the reader scrolls them as one [Strip]. */
enum class LayoutMode { Single, Spread, SplitWide, Auto, Vertical }

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
 * The view that [page] starts going forward, or ends going back (docs/spec.md section 2.5). [settings] must already be
 * resolved, so its mode is not [LayoutMode.Auto].
 */
fun viewAt(pageCount: Int, shape: (Int) -> PageShape, settings: LayoutSettings, page: Int, toward: Turn): PageView {
    require(settings.mode != LayoutMode.Auto) { "resolve LayoutMode.Auto with LayoutSettings.resolve first" }
    require(page in 0 until pageCount) { "page $page is outside 0 until $pageCount" }
    return when (settings.mode) {
        LayoutMode.Single, LayoutMode.Vertical -> PageView.One(page)
        LayoutMode.SplitWide ->
            if (shape(page).isWide) PageView.Half(page, if (toward == Turn.Forward) HalfPart.First else HalfPart.Second) else PageView.One(page)
        LayoutMode.Spread -> Pairing(pageCount, shape, settings.coverAlone).let { if (toward == Turn.Forward) it.from(page) else it.to(page) }
        LayoutMode.Auto -> error("unreachable")
    }
}

/** Whether [view] is one that [viewAt] makes from one of its ends, so a layout can be built around it. */
fun isView(view: PageView, pageCount: Int, shape: (Int) -> PageShape, settings: LayoutSettings): Boolean =
    view.pages.all { it in 0 until pageCount } &&
        (viewAt(pageCount, shape, settings, view.pages.first(), Turn.Forward) == view ||
            viewAt(pageCount, shape, settings, view.pages.last(), Turn.Back) == view)

/**
 * The views of a book in reading order around [current] (docs/spec.md section 2.5), which [isView] must accept. The
 * views after it pair forward from the page after it, and the views before it pair backward from the page before it,
 * so they are the views that turning forward or back from [current] shows.
 */
fun layoutViews(pageCount: Int, shape: (Int) -> PageShape, settings: LayoutSettings, current: PageView): List<PageView> {
    require(isView(current, pageCount, shape, settings)) { "$current is not a view of $settings" }
    val pages = 0 until pageCount
    return when (settings.mode) {
        LayoutMode.Single, LayoutMode.Vertical -> pages.map { PageView.One(it) }
        LayoutMode.SplitWide -> pages.flatMap {
            if (shape(it).isWide) {
                listOf(PageView.Half(it, HalfPart.First), PageView.Half(it, HalfPart.Second))
            } else {
                listOf(PageView.One(it))
            }
        }
        LayoutMode.Spread -> {
            val pairing = Pairing(pageCount, shape, settings.coverAlone)
            val before = generateSequence(current.pages.first() - 1) { pairing.to(it).pages.first() - 1 }
                .takeWhile { it >= 0 }.map(pairing::to).toList().asReversed()
            val after = generateSequence(current.pages.last() + 1) { pairing.from(it).pages.last() + 1 }
                .takeWhile { it < pageCount }.map(pairing::from).toList()
            before + current + after
        }
        LayoutMode.Auto -> error("unreachable")
    }
}

/** The greedy pairing of 見開き: two tall pages next to each other make a pair, and every other page stands alone. */
private class Pairing(val pageCount: Int, val shape: (Int) -> PageShape, val coverAlone: Boolean) {
    private fun pairable(page: Int) = !shape(page).isWide && !(coverAlone && page == 0)

    /** The view that starts at [first]. */
    fun from(first: Int): PageView =
        if (first + 1 < pageCount && pairable(first) && pairable(first + 1)) PageView.Two(first, first + 1) else PageView.One(first)

    /** The view that ends at [last]. */
    fun to(last: Int): PageView =
        if (last >= 1 && pairable(last) && pairable(last - 1)) PageView.Two(last - 1, last) else PageView.One(last)
}
