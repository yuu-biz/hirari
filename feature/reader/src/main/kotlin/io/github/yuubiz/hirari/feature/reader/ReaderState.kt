package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Pagination
import io.github.yuubiz.hirari.core.model.PaginationEvent
import io.github.yuubiz.hirari.core.model.Point

/** Where the screen rests in the current view. */
sealed interface Place {
    /** The first stop after a forward turn, the last after a turn back, resolved once the geometry is known. */
    data class Entered(val turn: Turn) : Place

    data class AtStop(val index: Int) : Place

    /** Moved by a finger or a pinch, off the stops. */
    data class Panned(val position: Point) : Place
}

/**
 * Two views on screen at once: a turn in progress, or a pull going back to rest. [incoming] is the current view
 * during a turn, and the neighbor during a return. Both are drawn at the positions given here.
 */
data class Transition(
    val outgoing: PageView,
    val outgoingPlace: Place,
    val incoming: PageView,
    val incomingPlace: Place,
    val animation: TurnAnimation,
)

/** A finger pulling toward [neighbor], which is `null` at the end of the book. [pulledDp] is the resisted pull. */
data class Pull(val turn: Turn, val neighbor: PageView?, val pulledDp: Float)

/** A move between two stops, ending on [index]. */
data class StopTravel(val move: StopMove, val index: Int)

/**
 * The image reader (docs/spec.md sections 2.5, 2.6, and 4.8) as one immutable value. Every input becomes a
 * [ReaderEvent], and [reduce] makes the next state. Animations are values with a start time, so drawing a frame
 * reads them at the frame time and nothing here depends on a clock.
 */
data class ReaderState(
    val pagination: Pagination,
    val config: ReaderConfig,
    val viewport: Viewport,
    val zoom: ZoomState = ZoomState(),
    val place: Place = Place.Entered(Turn.Forward),
    val stopTravel: StopTravel? = null,
    val transition: Transition? = null,
    val pull: Pull? = null,
    /** The last two turns between views, for the prefetch direction. */
    val recentTurns: List<Turn> = emptyList(),
) {
    val current: PageView get() = pagination.current
    val isLastView: Boolean get() = pagination.currentIndex == pagination.views.lastIndex

    fun geometry(view: PageView = current): ViewGeometry? =
        ViewGeometry.of(view, pagination.shapes, config, viewport, zoom.factor)

    /** The geometry of [view] at the zoom of its scale mode, which sets the size of its prepared pages. */
    fun preparedGeometry(view: PageView): ViewGeometry? = ViewGeometry.of(view, pagination.shapes, config, viewport, 1f)

    /** The stop index the screen rests on, or `null` while the geometry is unknown or the view was panned. */
    fun stopIndex(): Int? {
        val path = geometry()?.stops(config) ?: return null
        return when (val place = place) {
            is Place.Entered -> StopCursor.entering(path, place.turn).index
            is Place.AtStop -> place.index.coerceIn(0, path.last)
            is Place.Panned -> null
        }
    }

    /** The screen's top-left corner in [geometry] for [place]. */
    fun positionOf(geometry: ViewGeometry, place: Place): Point {
        val path = geometry.stops(config)
        return when (place) {
            is Place.Entered -> StopCursor.entering(path, place.turn).point
            is Place.AtStop -> path.stops[place.index.coerceIn(0, path.last)].point
            is Place.Panned -> geometry.clamp(place.position)
        }
    }

    /** Where the screen is in the current view at [nowMs], moving between stops or resting. */
    fun positionAt(nowMs: Long): Point? {
        val geometry = geometry() ?: return null
        return stopTravel?.move?.positionAt(nowMs) ?: positionOf(geometry, place)
    }

    /** The neighbor a turn toward [turn] reaches, or `null` at the edge of the book. */
    fun neighbor(turn: Turn): PageView? =
        pagination.views.getOrNull(pagination.currentIndex + if (turn == Turn.Forward) 1 else -1)

    fun reduce(event: ReaderEvent): ReaderState = when (event) {
        is ReaderEvent.Act -> settled().act(event.action, event.nowMs)
        is ReaderEvent.GoTo -> settled().moved(PaginationEvent.GoTo(event.page))
        is ReaderEvent.ShapeLearned -> relaid(pagination.reduce(PaginationEvent.ShapeLearned(event.page, event.shape)))
        is ReaderEvent.ViewportChanged ->
            if (event.viewport == viewport) {
                this
            } else {
                copy(
                    pagination = pagination.reduce(PaginationEvent.OrientationChanged(event.viewport.orientation)),
                    viewport = event.viewport,
                    zoom = ZoomState(),
                    place = Place.Entered(Turn.Forward),
                    stopTravel = null,
                    transition = null,
                    pull = null,
                )
            }
        is ReaderEvent.ConfigChanged -> configured(event.config)
        is ReaderEvent.Tick -> copy(
            transition = transition?.takeUnless { it.animation.isDoneAt(event.nowMs) },
            stopTravel = stopTravel?.takeUnless { it.move.motion.isDoneAt(event.nowMs) },
        )
        is ReaderEvent.Pan -> settled().panned(event.dx, event.dy)
        is ReaderEvent.Pulling -> settled().copy(pull = Pull(event.turn, neighbor(event.turn), event.pulledDp))
        is ReaderEvent.PullEnded -> released(event.commit, event.nowMs)
        is ReaderEvent.Zoom -> settled().zoomed(event.event, event.focus)
    }

    /** The state with every running animation at its end, as the next input finds it. */
    private fun settled(): ReaderState {
        val travel = stopTravel ?: return copy(transition = null, pull = null)
        return copy(transition = null, pull = null, stopTravel = null, place = Place.AtStop(travel.index))
    }

    private fun act(action: ReaderAction, nowMs: Long): ReaderState = when (action) {
        is ReaderAction.TurnView -> turned(action.turn, nowMs)
        is ReaderAction.StepStops -> stepped(action.turn, nowMs)
        is ReaderAction.Shift -> moved(PaginationEvent.Shift(action.forward))
        ReaderAction.First -> moved(PaginationEvent.GoTo(0))
        ReaderAction.Last -> moved(PaginationEvent.GoTo(pagination.shapes.lastIndex))
        ReaderAction.OpenMenu, ReaderAction.Dismiss -> this
    }

    private fun turned(turn: Turn, nowMs: Long, animation: TurnAnimation? = null): ReaderState {
        val next = pagination.reduce(if (turn == Turn.Forward) PaginationEvent.Next else PaginationEvent.Prev)
        if (next === pagination) return this
        val entered = Place.Entered(turn)
        val effect = animation ?: TurnAnimation.turn(config.turnEffect, turnMovement(turn, config.direction), nowMs, config.turnDurationMs)
        return copy(
            pagination = next,
            place = entered,
            transition = Transition(current, place, next.current, entered, effect),
            recentTurns = (recentTurns + turn).takeLast(2),
        )
    }

    private fun stepped(turn: Turn, nowMs: Long): ReaderState {
        val geometry = geometry() ?: return turned(turn, nowMs)
        val path = geometry.stops(config)
        val from = positionOf(geometry, place)
        val step = when (val place = place) {
            is Place.Panned -> if (turn == Turn.Forward) StopCursor(path, 0).forwardFrom(from) else backFrom(path, from)
            else -> StopCursor(path, stopIndex()!!).step(turn)
        }
        return when (step) {
            is StopStep.Leave -> turned(turn, nowMs)
            is StopStep.Move -> copy(
                place = Place.AtStop(step.cursor.index),
                stopTravel = StopTravel(StopMove.between(from, step.cursor.point, nowMs, viewport.density), step.cursor.index),
            )
        }
    }

    /** After a pan, going back goes to the nearest stop before the screen, the mirror of [StopCursor.forwardFrom]. */
    private fun backFrom(path: StopPath, position: Point): StopStep {
        val ahead = path.nextAfter(position) ?: path.stops.size
        val before = (ahead - 1 downTo 0).firstOrNull { path.stops[it].point != position }
        return if (before == null) StopStep.Leave(Turn.Back) else StopStep.Move(StopCursor(path, before))
    }

    /** A move that does not animate: a jump to a page, a shift of the pairing. */
    private fun moved(event: PaginationEvent): ReaderState {
        val next = pagination.reduce(event)
        if (next.current == current && next.currentIndex == pagination.currentIndex) return copy(pagination = next)
        return copy(pagination = next, place = Place.Entered(Turn.Forward))
    }

    /** The views laid out again after a shape became known: the current view stays where it was if it is still there. */
    private fun relaid(next: Pagination): ReaderState {
        if (next === pagination) return this
        if (next.current == current) {
            val keepTransition = transition?.takeIf { it.incoming in next.views && it.outgoing in next.views }
            val keepPull = pull?.takeIf { it.neighbor == null || it.neighbor in next.views }
            return copy(pagination = next, transition = keepTransition, pull = keepPull)
        }
        val entered = place as? Place.Entered ?: Place.Entered(Turn.Forward)
        return copy(pagination = next, place = entered, stopTravel = null, transition = null, pull = null)
    }

    private fun configured(next: ReaderConfig): ReaderState {
        if (next == config) return this
        val modeChanged = next.scaleMode != config.scaleMode || next.capAtOriginal != config.capAtOriginal
        val layout = pagination.reduce(PaginationEvent.SettingsChanged(next.layout))
        val viewChanged = layout.current != current
        return settled().copy(
            pagination = layout,
            config = next,
            zoom = if (modeChanged) zoom.reduce(ZoomEvent.ModeChanged, ZoomLimits(Scale(1f, 1f), 1f, false)) else zoom,
            place = if (modeChanged || viewChanged || next.direction != config.direction) Place.Entered(Turn.Forward) else place,
        )
    }

    private fun panned(dx: Float, dy: Float): ReaderState {
        val geometry = geometry() ?: return this
        if (!geometry.overflows) return this
        val from = positionOf(geometry, place)
        return copy(place = Place.Panned(geometry.clamp(Point(from.x - dx, from.y - dy))))
    }

    private fun released(commit: Boolean, nowMs: Long): ReaderState {
        val pull = pull ?: return this
        val neighbor = pull.neighbor ?: return copy(pull = null)
        val movement = turnMovement(pull.turn, config.direction)
        val pulled = pull.pulledDp * viewport.density / viewport.width
        if (commit) {
            val finish = TurnAnimation.finishPull(config.turnEffect, movement, pulled, nowMs, config.turnDurationMs)
            return copy(pull = null).turned(pull.turn, nowMs, finish)
        }
        val back = TurnAnimation.returnPull(config.turnEffect, movement, pulled, nowMs)
        return copy(pull = null, transition = Transition(current, place, neighbor, Place.Entered(pull.turn), back))
    }

    /** A pinch scales about [focus], in screen pixels, so the point under the fingers stays under them. */
    private fun zoomed(event: ZoomEvent, focus: Point?): ReaderState {
        val before = geometry() ?: return copy(zoom = zoom.reduce(event, ZoomLimits(Scale(1f, 1f), 1f, false)))
        val nextZoom = zoom.reduce(event, before.limits)
        if (nextZoom.factor == zoom.factor) return copy(zoom = nextZoom)
        val after = copy(zoom = nextZoom).geometry() ?: return copy(zoom = nextZoom)
        val from = positionOf(before, place)
        val anchor = focus ?: Point(viewport.width / 2f, viewport.height / 2f)
        val rx = after.size.width / before.size.width
        val ry = after.size.height / before.size.height
        val position = Point((anchor.x + from.x) * rx - anchor.x, (anchor.y + from.y) * ry - anchor.y)
        return copy(zoom = nextZoom, place = Place.Panned(after.clamp(position)))
    }

    companion object {
        fun open(pageCount: Int, start: Int, config: ReaderConfig, viewport: Viewport): ReaderState =
            ReaderState(Pagination.open(pageCount, config.layout, viewport.orientation, start), config, viewport)
    }
}

sealed interface ReaderEvent {
    /** A tap, a key, or a command; [ReaderAction.OpenMenu] and [ReaderAction.Dismiss] belong to the screen. */
    data class Act(val action: ReaderAction, val nowMs: Long) : ReaderEvent

    /** A jump to [page] from the page bar, which anchors the layout there. */
    data class GoTo(val page: Int) : ReaderEvent
    data class ShapeLearned(val page: Int, val shape: PageShape.Known) : ReaderEvent
    data class ViewportChanged(val viewport: Viewport) : ReaderEvent
    data class ConfigChanged(val config: ReaderConfig) : ReaderEvent

    /** A frame at [nowMs], which ends the animations that are done. */
    data class Tick(val nowMs: Long) : ReaderEvent

    /** A finger moved the zoomed view by [dx] and [dy] screen pixels. */
    data class Pan(val dx: Float, val dy: Float) : ReaderEvent

    /** A finger pulls past the edge toward [turn], by [pulledDp] after resistance. */
    data class Pulling(val turn: Turn, val pulledDp: Float) : ReaderEvent

    /** The pull ended: it turns when [commit], and goes back to rest otherwise. */
    data class PullEnded(val commit: Boolean, val nowMs: Long) : ReaderEvent

    /** [focus] is the point between the fingers, in screen pixels. */
    data class Zoom(val event: ZoomEvent, val focus: Point? = null) : ReaderEvent
}
