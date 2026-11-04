package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.image.MarginScan
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.EdgeHit
import io.github.yuubiz.hirari.core.model.Inertia
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.TurnAnimation
import io.github.yuubiz.hirari.core.model.edgeOf
import io.github.yuubiz.hirari.core.model.turnMovement
import io.github.yuubiz.hirari.feature.reader.prefetch.PageSteps
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Pagination
import io.github.yuubiz.hirari.core.model.PaginationEvent
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.ScrollMotion
import io.github.yuubiz.hirari.core.model.Strip
import io.github.yuubiz.hirari.core.model.StripPosition
import io.github.yuubiz.hirari.core.model.StripScreen
import io.github.yuubiz.hirari.core.model.StripScroll
import io.github.yuubiz.hirari.core.model.rotated
import io.github.yuubiz.hirari.core.source.smb.SmbConnectionState
import kotlin.math.abs

/** Positions closer than this, in pixels, count as the same. */
private const val SAME_PX = 0.5f

/** Where the screen rests in the current view. */
sealed interface Place {
    /** The first stop after a forward turn, the last after a turn back, resolved once the geometry is known. */
    data class Entered(val turn: Turn) : Place

    data class AtStop(val index: Int) : Place

    /** Moved by a finger or a pinch, off the stops. */
    data class Panned(val position: Point) : Place

    /**
     * Carried on by [inertia] from [from] after a finger let go of a pan while moving, and at [at] as of the last frame.
     * It stops at the edges of the view and never turns (docs/spec.md section 4.8, 端引きでめくる).
     */
    data class Gliding(val from: Point, val inertia: Inertia, val at: Point) : Place
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
    /** 自動再生, while it plays. */
    val autoPlay: AutoPlay? = null,
    /** ルーペ, while a finger holds it up. */
    val loupe: Loupe? = null,
    /** The last turn that ran into an edge of the book, for 本の端での動作 and its bounce. */
    val edgeHit: EdgeHit? = null,
    /** The connection to the server of a book on SMB, or `null` for a book on the device (docs/spec.md section 6.5). */
    val connection: SmbConnectionState? = null,
    /** What 余白トリミング read from each page, in the shown orientation, for every strength at once. */
    val margins: Map<Int, MarginScan> = emptyMap(),
    /** Where the screen is over the [strip] in 縦に連続, and `null` in the other layouts (docs/spec.md section 2.5). */
    val scroll: StripScroll? = null,
) {
    val current: PageView get() = pagination.current

    /** The pages one under another, as 縦に連続 lays them out. */
    val strip: Strip by lazy { Strip.of(pagination.shapes) }

    /** The screen over the [strip], with the pinch zoom. */
    val stripScreen: StripScreen get() = StripScreen(viewport.width, viewport.height, zoom.factor)

    /**
     * The last view is on screen and the shapes of its pages are known, so no layout to come makes it another
     * view. Before that, a lone wide page still shows as one view that later splits in two halves.
     */
    val showsLastView: Boolean
        get() = scroll?.let { it.endInView(strip, stripScreen) && pagination.shapes.last() is PageShape.Known }
            ?: (pagination.currentIndex == pagination.views.lastIndex && current.pages.all { pagination.shapes[it] is PageShape.Known })

    /** The geometry of [view] on screen; `null` in 縦に連続, which places its pages by the [strip]. */
    fun geometry(view: PageView = current): ViewGeometry? =
        if (scroll != null) null else ViewGeometry.of(view, pagination.shapes, keptParts(view), config, viewport, zoom.factor)

    /**
     * The geometry of [view] at the zoom of its scale mode, which sets the size of its prepared pages. In 縦に連続
     * every page fits the width of the screen.
     */
    fun preparedGeometry(view: PageView): ViewGeometry? {
        val shown = if (scroll != null) config.copy(scaleMode = ScaleMode.FitWidth, capAtOriginal = false) else config
        return ViewGeometry.of(view, pagination.shapes, keptParts(view), shown, viewport, 1f)
    }

    /** The stop index the screen rests on, or `null` while the geometry is unknown or the view was panned. */
    fun stopIndex(): Int? {
        val path = geometry()?.stops(config) ?: return null
        return when (val place = place) {
            is Place.Entered -> StopCursor.entering(path, place.turn).index
            is Place.AtStop -> place.index.coerceIn(0, path.last)
            is Place.Panned, is Place.Gliding -> null
        }
    }

    /** The screen's top-left corner in [geometry] for [place]. */
    fun positionOf(geometry: ViewGeometry, place: Place): Point {
        val path = geometry.stops(config)
        return when (place) {
            is Place.Entered -> StopCursor.entering(path, place.turn).point
            is Place.AtStop -> path.stops[place.index.coerceIn(0, path.last)].point
            is Place.Panned -> geometry.clamp(place.position)
            is Place.Gliding -> geometry.clamp(place.at)
        }
    }

    /** Where the screen is in the current view at [nowMs], moving between stops, gliding, or resting. */
    fun positionAt(nowMs: Long): Point? {
        val geometry = geometry() ?: return null
        val gliding = place as? Place.Gliding
        return stopTravel?.move?.positionAt(nowMs) ?: gliding?.let { geometry.clamp(it.raw(nowMs)) } ?: positionOf(geometry, place)
    }

    private fun Place.Gliding.raw(nowMs: Long) =
        Point((from.x + inertia.distanceXAt(nowMs)).toFloat(), (from.y + inertia.distanceYAt(nowMs)).toFloat())

    /**
     * The glide at [nowMs]: it moves on, or comes to rest once its time is up or every axis has stopped. An axis stops
     * at an edge of the view; an axis the view fits along, or one the finger did not move along, never moves.
     */
    private fun glided(nowMs: Long): Place {
        val gliding = place as? Place.Gliding ?: return place
        val geometry = geometry() ?: return place
        val raw = gliding.raw(nowMs)
        val at = geometry.clamp(raw)
        fun stopped(velocity: Double, wanted: Float, kept: Float, content: Float, screen: Float) =
            velocity == 0.0 || content <= screen + SAME_PX || abs(wanted - kept) >= SAME_PX
        val done = gliding.inertia.isDoneAt(nowMs) ||
            stopped(gliding.inertia.velocityX, raw.x, at.x, geometry.size.width, viewport.width) &&
            stopped(gliding.inertia.velocityY, raw.y, at.y, geometry.size.height, viewport.height)
        return if (done) Place.Panned(at) else gliding.copy(at = at)
    }

    /**
     * The page of the current view under the loupe's finger, by its place in the view, or the page nearest to the
     * finger when it is between them. `null` without a loupe or before the view's shapes are known.
     */
    fun loupePage(nowMs: Long): Int? {
        val finger = loupe?.finger ?: return null
        if (scroll != null) return null
        val geometry = geometry() ?: return null
        val x = finger.x + (positionAt(nowMs) ?: return null).x
        return geometry.pages.indices.minBy { i ->
            val rect = geometry.pages[i]
            if (x < rect.left) rect.left - x else if (x > rect.right) x - rect.right else 0f
        }
    }

    /** Screen pixels for each pixel of the image of the page at [index] of the current view, as it is turned. */
    private fun displayScale(geometry: ViewGeometry, index: Int): Float {
        val shape = pagination.shapes[current.pages[index]] as PageShape.Known
        return geometry.pages[index].height / (shape.height * keptParts(current)[index].height)
    }

    /** What the loupe shows at [nowMs] (docs/spec.md section 4.8, ルーペ), or `null` without one. */
    fun loupeView(nowMs: Long): LoupeView? {
        val loupe = loupe ?: return null
        if (scroll != null) return stripLoupeView(loupe, nowMs)
        val index = loupePage(nowMs) ?: return null
        val scale = displayScale(geometry() ?: return null, index)
        return LoupeView(loupe.rectAt(nowMs, viewport.size, config.loupeFrame), loupe.finger, config.loupeZoom.magnification(scale))
    }

    /**
     * The page the loupe reads again at the size of its own image, for the magnifications of the source image. `null`
     * when the loupe shows the prepared view: without a loupe, for a magnification of the screen, or when the prepared
     * page already has a pixel for every pixel of the image.
     */
    fun loupeSource(): LoupeSource? {
        if (!config.loupeZoom.readsSource) return null
        if (scroll != null) return stripLoupeSource()
        val index = loupePage(Long.MAX_VALUE) ?: return null
        val geometry = preparedGeometry(current) ?: return null
        if (displayScale(geometry, index) >= 1f) return null
        val shape = pagination.shapes[current.pages[index]] as PageShape.Known
        val part = shownParts(current)[index]
        return LoupeSource(current, index, part.sizeOf(PixelSize(shape.width, shape.height)), PageSteps(part, config.rotation, config.adjust.sharpen))
    }

    /** The neighbor a turn toward [turn] reaches, or `null` at the edge of the book. */
    fun neighbor(turn: Turn): PageView? =
        pagination.views.getOrNull(pagination.currentIndex + if (turn == Turn.Forward) 1 else -1)

    fun reduce(event: ReaderEvent): ReaderState = when (event) {
        is ReaderEvent.Act -> byHand().act(event.action, event.nowMs)
        is ReaderEvent.GoTo -> byHand().moved(PaginationEvent.GoTo(event.page))
        is ReaderEvent.ShapeLearned -> relaid(pagination.reduce(PaginationEvent.ShapeLearned(event.page, event.shape.rotated(config.rotation)))).following()
        is ReaderEvent.MarginsLearned -> copy(margins = margins.learned(event.page, event.scan, current.pages.first()))
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
                    loupe = null,
                    scroll = scroll?.copy(x = 0f, motion = null),
                ).following()
            }
        is ReaderEvent.ConfigChanged -> configured(event.config)
        is ReaderEvent.Tick -> copy(
            place = glided(event.nowMs),
            transition = transition?.takeUnless { it.animation.isDoneAt(event.nowMs) },
            stopTravel = stopTravel?.takeUnless { it.move.motion.isDoneAt(event.nowMs) },
            loupe = loupe?.at(event.nowMs),
            edgeHit = edgeHit?.takeUnless { it.isDoneAt(event.nowMs) },
            scroll = scroll?.ticked(event.nowMs, strip, stripScreen),
        ).following()
        is ReaderEvent.LoupeShown -> byHand().copy(loupe = Loupe.at(event.finger, viewport.size))
        is ReaderEvent.LoupeMoved -> copy(loupe = loupe?.moved(event.finger, viewport.size, viewport.density, event.nowMs))
        ReaderEvent.LoupeHidden -> copy(loupe = null)
        is ReaderEvent.Pan -> byHand().panned(event.dx, event.dy)
        is ReaderEvent.Fling ->
            if (scroll != null) {
                copy(scroll = scroll.flung(event.velocityY, event.nowMs, viewport.density, strip, stripScreen)).following()
            } else {
                flung(event.velocityX, event.velocityY, event.nowMs)
            }
        ReaderEvent.Catch -> if (scroll?.motion is ScrollMotion.Glide || place is Place.Gliding) settled() else this
        is ReaderEvent.Pulling -> byHand().copy(pull = Pull(event.turn, neighbor(event.turn), event.pulledDp))
        is ReaderEvent.PullEnded -> released(event.commit, event.nowMs)
        is ReaderEvent.Zoom -> byHand().zoomed(event.event, event.focus)
        is ReaderEvent.AutoPlayStarted -> copy(autoPlay = AutoPlay(event.nowMs + config.autoPlayMs))
        ReaderEvent.AutoPlayStopped -> copy(autoPlay = null)
        is ReaderEvent.AutoPlayDue -> autoPlayed(event.nowMs)
        is ReaderEvent.ConnectionChanged -> copy(connection = event.connection)
    }

    /** The state with every running animation at its end, as the next input finds it. */
    private fun settled(): ReaderState {
        if (scroll != null) return copy(transition = null, pull = null, scroll = scroll.settled(strip, stripScreen)).following()
        val resting = (place as? Place.Gliding)?.let { Place.Panned(it.at) } ?: place
        val travel = stopTravel ?: return copy(transition = null, pull = null, place = resting)
        return copy(transition = null, pull = null, stopTravel = null, place = Place.AtStop(travel.index))
    }

    /** In 縦に連続 the current view is the page the screen is on, and a move to another page counts as a turn. */
    private fun following(): ReaderState {
        val scroll = scroll ?: return this
        val page = scroll.page(strip, stripScreen)
        val on = current.pages.first()
        if (page == on) return this
        val turn = if (page > on) Turn.Forward else Turn.Back
        return copy(pagination = pagination.reduce(PaginationEvent.GoTo(page)), recentTurns = (recentTurns + turn).takeLast(2))
    }

    /** A move of the reader's own ends 自動再生 (docs/spec.md section 4.8). */
    private fun byHand(): ReaderState = settled().copy(autoPlay = null)

    /**
     * The next step of 自動再生 once it is due: the next stop, or the next view past the last one. At the end of the
     * book it stops there, whatever 本の端での動作 says.
     */
    private fun autoPlayed(nowMs: Long): ReaderState {
        val playing = autoPlay ?: return this
        if (nowMs < playing.dueAtMs) return this
        val from = settled()
        val next = from.stepped(Turn.Forward, nowMs)
        return if (next === from) from.copy(autoPlay = null) else next.copy(autoPlay = AutoPlay(nowMs + config.autoPlayMs))
    }

    private fun act(action: ReaderAction, nowMs: Long): ReaderState = when (action) {
        is ReaderAction.TurnView -> ranInto(if (scroll != null) stepped(action.turn, nowMs) else turned(action.turn, nowMs), action.turn, nowMs)
        is ReaderAction.StepStops -> ranInto(stepped(action.turn, nowMs), action.turn, nowMs)
        is ReaderAction.Shift -> moved(PaginationEvent.Shift(action.forward))
        ReaderAction.First -> moved(PaginationEvent.GoTo(0))
        ReaderAction.Last -> moved(PaginationEvent.GoTo(pagination.shapes.lastIndex))
        ReaderAction.OpenMenu, ReaderAction.Dismiss -> this
    }

    /**
     * [moved], or this state run into the edge toward [turn] when it could not move. A view whose shapes are not
     * known yet may still split, so it is not an edge until they are; in 縦に連続, the same goes for the pages on
     * screen, which may still grow.
     */
    private fun ranInto(moved: ReaderState, turn: Turn, nowMs: Long): ReaderState {
        if (moved !== this || scroll == null && neighbor(turn) != null) return moved
        val shown = scroll?.visible(strip, stripScreen)?.toList() ?: current.pages
        if (shown.any { pagination.shapes[it] !is PageShape.Known }) return moved
        return copy(edgeHit = EdgeHit(edgeOf(turn), nowMs))
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
        if (scroll != null) return scroll.stepped(turn, nowMs, viewport.density, strip, stripScreen)?.let { copy(scroll = it) } ?: this
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
        if (scroll != null && event is PaginationEvent.GoTo) return copy(pagination = next, scroll = StripScroll.at(next.current.pages.first())).following()
        if (next.current == current && next.currentIndex == pagination.currentIndex) return copy(pagination = next)
        return copy(pagination = next, place = Place.Entered(Turn.Forward))
    }

    /**
     * The views laid out again after a shape became known: the current view stays where it was if it is still there.
     * In 縦に連続 a running motion goes on from what the screen shows.
     */
    private fun relaid(next: Pagination): ReaderState {
        if (next === pagination) return this
        val scroll = scroll?.let { if (it.motion == null) it else it.relaid(strip, Strip.of(next.shapes), stripScreen) }
        if (next.current == current) {
            val keepTransition = transition?.takeIf { next.holds(it.incoming) && next.holds(it.outgoing) }
            val keepPull = pull?.takeIf { it.neighbor == null || it.neighbor in next.views }
            return copy(pagination = next, transition = keepTransition, pull = keepPull, scroll = scroll)
        }
        val entered = place as? Place.Entered ?: Place.Entered(Turn.Forward)
        return copy(pagination = next, place = entered, stopTravel = null, transition = null, pull = null, scroll = scroll)
    }

    private fun configured(next: ReaderConfig): ReaderState {
        if (next == config) return this
        val turned = next.rotation != config.rotation
        val modeChanged = turned || next.scaleMode != config.scaleMode || next.capAtOriginal != config.capAtOriginal || next.vertical != config.vertical
        val shapes = if (next.rotation.swapsSides == config.rotation.swapsSides) pagination else pagination.reduce(PaginationEvent.ShapesChanged(swapped(pagination.shapes)))
        val layout = shapes.reduce(PaginationEvent.SettingsChanged(next.layout))
        val viewChanged = layout.current != current
        val settled = settled()
        val scroll = when {
            !next.vertical -> null
            settled.scroll == null -> StripScroll.at(layout.current.pages.first())
            modeChanged -> settled.scroll.copy(x = 0f)
            else -> settled.scroll
        }
        return settled.copy(
            pagination = layout,
            config = next,
            margins = if (turned) emptyMap() else margins,
            zoom = if (modeChanged) zoom.reduce(ZoomEvent.ModeChanged, ZoomLimits(Scale(1f, 1f), 1f, false)) else zoom,
            place = if (modeChanged || viewChanged || next.direction != config.direction) Place.Entered(Turn.Forward) else place,
            scroll = scroll,
        ).following()
    }

    private fun panned(dx: Float, dy: Float): ReaderState {
        if (scroll != null) return copy(scroll = scroll.panned(dx, dy, strip, stripScreen)).following()
        val geometry = geometry() ?: return this
        if (!geometry.overflows) return this
        val from = positionOf(geometry, place)
        return copy(place = Place.Panned(geometry.clamp(Point(from.x - dx, from.y - dy))))
    }

    /**
     * A finger that panned the view let go at [velocityX] and [velocityY] screen pixels per second: the view glides on
     * the way the finger went, as the strip of 縦に連続 does, when it went fast enough.
     */
    private fun flung(velocityX: Float, velocityY: Float, nowMs: Long): ReaderState {
        val panned = place as? Place.Panned ?: return this
        val geometry = geometry()?.takeIf { it.overflows } ?: return this
        val density = viewport.density
        val inertia = Inertia.of(-velocityX / density, -velocityY / density, nowMs, density) ?: return this
        val from = geometry.clamp(panned.position)
        return copy(place = Place.Gliding(from, inertia, from))
    }

    private fun released(commit: Boolean, nowMs: Long): ReaderState {
        val pull = pull ?: return this
        val neighbor = pull.neighbor ?: return copy(pull = null).let { if (commit) it.ranInto(it, pull.turn, nowMs) else it }
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
        if (scroll != null) return stripZoomed(scroll, event, focus)
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

    /**
     * A pinch in 縦に連続 zooms the strip about [focus] from the width of the screen, between half of it and eight
     * times it, and a release below the width of the screen goes back to it (docs/spec.md section 4.8).
     */
    private fun stripZoomed(scroll: StripScroll, event: ZoomEvent, focus: Point?): ReaderState {
        val next = zoom.reduce(event, ZoomLimits(Scale(1f, 1f), 1f, capAtOriginal = false))
        if (next.factor == zoom.factor) return copy(zoom = next)
        val before = stripScreen
        val anchor = focus ?: Point(viewport.width / 2f, viewport.height / 2f)
        return copy(zoom = next, scroll = scroll.zoomed(before, before.copy(zoom = next.factor), anchor, strip)).following()
    }

    private fun swapped(shapes: List<PageShape>): List<PageShape> =
        shapes.map { if (it is PageShape.Known) it.rotated(Rotation.Quarter) else it }

    companion object {
        /**
         * In 縦に連続, [offset] is how far down page [start] the top of the screen is, as a fraction of its height. The
         * bottom of a page opens as the top of the next one, which is then the page the reader is on.
         */
        fun open(pageCount: Int, start: Int, config: ReaderConfig, viewport: Viewport, offset: Float = 0f): ReaderState {
            if (!config.vertical) return ReaderState(Pagination.open(pageCount, config.layout, viewport.orientation, start), config, viewport)
            val anchor = StripPosition(start.coerceIn(0, pageCount - 1), offset.coerceIn(0f, 1f)).named(pageCount)
            val pagination = Pagination.open(pageCount, config.layout, viewport.orientation, anchor.page)
            return ReaderState(pagination, config, viewport, scroll = StripScroll(anchor))
        }
    }
}

sealed interface ReaderEvent {
    /** A tap, a key, or a command; [ReaderAction.OpenMenu] and [ReaderAction.Dismiss] belong to the screen. */
    data class Act(val action: ReaderAction, val nowMs: Long) : ReaderEvent

    /** A jump to [page] from the page bar, which pairs forward from it. */
    data class GoTo(val page: Int) : ReaderEvent
    data class ShapeLearned(val page: Int, val shape: PageShape.Known) : ReaderEvent

    /** The margins of [page], read from it as turned when it was prepared. */
    data class MarginsLearned(val page: Int, val scan: MarginScan) : ReaderEvent
    data class ViewportChanged(val viewport: Viewport) : ReaderEvent
    data class ConfigChanged(val config: ReaderConfig) : ReaderEvent

    /** A frame at [nowMs], which ends the animations that are done. */
    data class Tick(val nowMs: Long) : ReaderEvent

    /** A finger moved the zoomed view, or the strip of 縦に連続, by [dx] and [dy] screen pixels. */
    data class Pan(val dx: Float, val dy: Float) : ReaderEvent

    /**
     * A finger that scrolled went up at [velocityX] and [velocityY] pixels per second, positive right and down. A view
     * larger than the screen glides on, and so does the strip of 縦に連続, which takes only [velocityY].
     */
    data class Fling(val velocityX: Float, val velocityY: Float, val nowMs: Long) : ReaderEvent

    /** A finger went down on the strip or the view while it glided, which stops it where it is. */
    data object Catch : ReaderEvent

    /** A finger pulls past the edge toward [turn], by [pulledDp] after resistance. */
    data class Pulling(val turn: Turn, val pulledDp: Float) : ReaderEvent

    /** The pull ended: it turns when [commit], and goes back to rest otherwise. */
    data class PullEnded(val commit: Boolean, val nowMs: Long) : ReaderEvent

    /** [focus] is the point between the fingers, in screen pixels. */
    data class Zoom(val event: ZoomEvent, val focus: Point? = null) : ReaderEvent

    /** A finger held still at [finger], in screen pixels, brings up the loupe. */
    data class LoupeShown(val finger: Point) : ReaderEvent

    data class LoupeMoved(val finger: Point, val nowMs: Long) : ReaderEvent

    data object LoupeHidden : ReaderEvent

    /** 自動再生 starts; its first step comes one interval later. */
    data class AutoPlayStarted(val nowMs: Long) : ReaderEvent

    /** A touch, a key, or leaving the screen ends 自動再生. */
    data object AutoPlayStopped : ReaderEvent

    /** The clock of 自動再生 at [nowMs]. A step that is not due yet changes nothing. */
    data class AutoPlayDue(val nowMs: Long) : ReaderEvent

    /** The connection to the book's server broke, is being made again, or came back. */
    data class ConnectionChanged(val connection: SmbConnectionState) : ReaderEvent
}

/** 自動再生 playing, with its next step at [dueAtMs]. */
data class AutoPlay(val dueAtMs: Long)

/** The page at [index] of [view], read at [size] with [steps] for the loupe. */
data class LoupeSource(val view: PageView, val index: Int, val size: PixelSize, val steps: PageSteps) {
    val page: Int get() = view.pages[index]
}
