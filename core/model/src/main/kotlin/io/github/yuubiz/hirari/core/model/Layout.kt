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
