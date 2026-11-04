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

/**
 * The views of an open book and the one on screen (docs/spec.md section 2.5). The book is paired around the
 * [current] view, so the views next to it are the ones a turn shows. [entered] is the way the reader came to it,
 * which decides how it pairs again when the shape of one of its pages turns out otherwise.
 */
class Pagination private constructor(
    val shapes: List<PageShape>,
    val settings: LayoutSettings,
    val orientation: ScreenOrientation,
    val current: PageView,
    val entered: Turn,
) {
    private val resolved: LayoutSettings get() = settings.resolve(orientation)

    val views: List<PageView> = layoutViews(shapes.size, shapes::get, resolved, current)

    val currentIndex: Int = views.indexOf(current)

    /** Whether [view] is still a view of the book as its shapes and settings are now. */
    fun holds(view: PageView): Boolean = isView(view, shapes.size, shapes::get, resolved)

    fun reduce(event: PaginationEvent): Pagination = when (event) {
        PaginationEvent.Next -> views.getOrNull(currentIndex + 1)?.let { Pagination(shapes, settings, orientation, it, Turn.Forward) } ?: this
        PaginationEvent.Prev -> views.getOrNull(currentIndex - 1)?.let { Pagination(shapes, settings, orientation, it, Turn.Back) } ?: this
        is PaginationEvent.GoTo -> at(shapes, settings, orientation, event.page.coerceIn(shapes.indices), Turn.Forward)
        is PaginationEvent.Shift -> shift(event.forward)
        is PaginationEvent.ShapeLearned ->
            if (shapes[event.page] == event.shape) {
                this
            } else {
                val learned = shapes.toMutableList().also { it[event.page] = event.shape }
                val end = if (entered == Turn.Forward) current.pages.first() else current.pages.last()
                relaid(learned, settings, orientation) { at(learned, settings, orientation, end, entered) }
            }
        is PaginationEvent.SettingsChanged ->
            if (event.settings == settings) this else relaid(shapes, event.settings, orientation)
        is PaginationEvent.OrientationChanged ->
            if (event.orientation == orientation) this else relaid(shapes, settings, event.orientation)
        is PaginationEvent.ShapesChanged -> {
            require(event.shapes.size == shapes.size) { "${event.shapes.size} shapes for ${shapes.size} pages" }
            if (event.shapes == shapes) this else relaid(event.shapes, settings, orientation)
        }
    }

    /**
     * 組ずらし: forward shows the view that starts on the page after the head of this one. Back pairs the page before
     * the head with the head, and keeps this view when they do not pair.
     */
    private fun shift(forward: Boolean): Pagination {
        if (resolved.mode != LayoutMode.Spread) return this
        val head = current.pages.first()
        val page = (if (forward) head + 1 else head - 1).coerceIn(shapes.indices)
        if (page == head) return this
        val shifted = at(shapes, settings, orientation, page, Turn.Forward)
        return if (forward || head in shifted.current.pages) shifted else this
    }

    /**
     * This view laid out again under new shapes or settings, kept when it is still a view. Otherwise the view
     * [fallback] gives, which is the one that starts on its head unless given.
     */
    private fun relaid(
        shapes: List<PageShape>,
        settings: LayoutSettings,
        orientation: ScreenOrientation,
        fallback: () -> Pagination = { at(shapes, settings, orientation, current.pages.first(), Turn.Forward) },
    ): Pagination =
        if (isView(current, shapes.size, shapes::get, settings.resolve(orientation))) {
            Pagination(shapes, settings, orientation, current, entered)
        } else {
            fallback()
        }

    companion object {
        /** [start] is the saved head page of the view, or 0 for a book opened for the first time. */
        fun open(pageCount: Int, settings: LayoutSettings, orientation: ScreenOrientation, start: Int = 0): Pagination {
            require(pageCount > 0) { "a book needs at least one page" }
            val shapes = List<PageShape>(pageCount) { PageShape.Unknown }
            return at(shapes, settings, orientation, start.coerceIn(shapes.indices), Turn.Forward)
        }

        /** The view that [page] starts going forward, or ends going back. */
        private fun at(shapes: List<PageShape>, settings: LayoutSettings, orientation: ScreenOrientation, page: Int, toward: Turn): Pagination =
            Pagination(shapes, settings, orientation, viewAt(shapes.size, shapes::get, settings.resolve(orientation), page, toward), toward)
    }
}
