package io.github.yuubiz.hirari.core.model

sealed interface PaginationEvent {
    data object Next : PaginationEvent
    data object Prev : PaginationEvent
    data class GoTo(val page: Int) : PaginationEvent
    data class Shift(val forward: Boolean) : PaginationEvent
    data class ShapeLearned(val page: Int, val shape: PageShape.Known) : PaginationEvent
    data class SettingsChanged(val settings: LayoutSettings) : PaginationEvent
    data class OrientationChanged(val orientation: ScreenOrientation) : PaginationEvent

    /** Every shape at once, as after the pages were rotated. The views are laid out again from the current one. */
    data class ShapesChanged(val shapes: List<PageShape>) : PaginationEvent
}

/** The views of an open book and the one on screen (docs/spec.md section 2.5). */
class Pagination private constructor(
    val shapes: List<PageShape>,
    val settings: LayoutSettings,
    val orientation: ScreenOrientation,
    val anchor: Int,
    val views: List<PageView>,
    val currentIndex: Int,
) {
    val current: PageView get() = views[currentIndex]

    fun reduce(event: PaginationEvent): Pagination = when (event) {
        PaginationEvent.Next -> moveTo(currentIndex + 1)
        PaginationEvent.Prev -> moveTo(currentIndex - 1)
        is PaginationEvent.GoTo -> {
            val page = event.page.coerceIn(shapes.indices)
            layout(shapes, settings, orientation, anchor = page, keep = null, fallback = page)
        }
        is PaginationEvent.Shift -> shift(event.forward)
        is PaginationEvent.ShapeLearned ->
            if (shapes[event.page] == event.shape) {
                this
            } else {
                val beforeAnchor = currentIndex < views.indexOfFirst { anchor in it.pages }
                val edge = if (beforeAnchor) current.pages.last() else current.pages.first()
                val learned = shapes.toMutableList().also { it[event.page] = event.shape }
                layout(learned, settings, orientation, anchor, keep = current, fallback = edge, fallbackFromEnd = beforeAnchor)
            }
        is PaginationEvent.SettingsChanged ->
            if (event.settings == settings) this else relayoutFromCurrent(event.settings, orientation)
        is PaginationEvent.OrientationChanged ->
            if (event.orientation == orientation) this else relayoutFromCurrent(settings, event.orientation)
        is PaginationEvent.ShapesChanged -> {
            require(event.shapes.size == shapes.size) { "${event.shapes.size} shapes for ${shapes.size} pages" }
            if (event.shapes == shapes) this else relayoutFromCurrent(settings, orientation, event.shapes)
        }
    }

    private fun moveTo(index: Int): Pagination =
        if (index in views.indices) Pagination(shapes, settings, orientation, anchor, views, index) else this

    private fun shift(forward: Boolean): Pagination {
        if (settings.resolve(orientation).mode != LayoutMode.Spread) return this
        val head = current.pages.first()
        val newAnchor = (if (forward) head + 1 else head - 1).coerceIn(shapes.indices)
        if (newAnchor == anchor) return this
        return layout(shapes, settings, orientation, newAnchor, keep = current, fallback = newAnchor)
    }

    private fun relayoutFromCurrent(settings: LayoutSettings, orientation: ScreenOrientation, shapes: List<PageShape> = this.shapes): Pagination {
        val head = current.pages.first()
        return layout(shapes, settings, orientation, anchor = head, keep = current, fallback = head)
    }

    companion object {
        /** [start] is the saved head page of the view, or 0 for a book opened for the first time. */
        fun open(pageCount: Int, settings: LayoutSettings, orientation: ScreenOrientation, start: Int = 0): Pagination {
            require(pageCount > 0) { "a book needs at least one page" }
            val shapes = List<PageShape>(pageCount) { PageShape.Unknown }
            val page = start.coerceIn(shapes.indices)
            return layout(shapes, settings, orientation, anchor = page, keep = null, fallback = page)
        }

        /**
         * The current view is [keep] if the new layout still has it. Otherwise it is the first view
         * with [fallback], or the last one when [fallbackFromEnd] is set.
         */
        private fun layout(
            shapes: List<PageShape>,
            settings: LayoutSettings,
            orientation: ScreenOrientation,
            anchor: Int,
            keep: PageView?,
            fallback: Int,
            fallbackFromEnd: Boolean = false,
        ): Pagination {
            val views = layoutViews(shapes.size, shapes::get, settings.resolve(orientation), anchor)
            val kept = if (keep == null) -1 else views.indexOf(keep)
            val index = when {
                kept >= 0 -> kept
                fallbackFromEnd -> views.indexOfLast { fallback in it.pages }
                else -> views.indexOfFirst { fallback in it.pages }
            }
            return Pagination(shapes, settings, orientation, anchor, views, index)
        }
    }
}
