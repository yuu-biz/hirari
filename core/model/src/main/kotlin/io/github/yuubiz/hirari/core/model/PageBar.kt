package io.github.yuubiz.hirari.core.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

const val PAGE_BAR_UNIT_DP = 5f
const val PAGE_BAR_EDGE_DP = 24f
const val LONG_PRESS_MS = 400L

/**
 * The pages a slide of [distance] dp moves (docs/spec.md section 4.8): none below 2u, one from
 * 2u, two from 6u, three from 9u, and one more for every u after that. The sign follows [distance].
 */
fun pageBarSteps(distance: Float, unit: Float = PAGE_BAR_UNIT_DP): Int {
    val units = abs(distance) / unit
    val steps = when {
        units < 2f -> 0
        units < 6f -> 1
        units < 9f -> 2
        else -> 3 + floor(units - 9f).toInt()
    }
    return if (distance < 0f) -steps else steps
}

/** The page bar on screen. Every length is in dp and in screen coordinates. */
data class PageBar(
    val left: Float,
    val right: Float,
    val top: Float,
    val height: Float,
    val pageCount: Int,
    val direction: ReadingDirection,
    val touchSlop: Float,
) {
    fun reduce(state: PageBarState, event: PageBarEvent): PageBarStep = when (event) {
        is PageBarEvent.Down -> {
            val armed = Side.entries.filterNot { inEdge(it, event.point.x) }.toSet()
            PageBarStep(PageBarState.Dragging(event.point, event.timeMs, event.page, event.page, false, false, armed))
        }
        is PageBarEvent.Move -> PageBarStep(if (state is PageBarState.Dragging) moved(state, event.point) else state)
        is PageBarEvent.Up -> when (state) {
            PageBarState.Idle -> PageBarStep(state)
            is PageBarState.Dragging -> {
                val last = moved(state, event.point)
                val effect = when {
                    !last.moved -> PageBarEffect.OpenPagePanel
                    last.cancelled || last.preview == last.startPage -> null
                    else -> PageBarEffect.GoTo(last.preview)
                }
                PageBarStep(PageBarState.Idle, effect)
            }
        }
        is PageBarEvent.Hold ->
            if (state is PageBarState.Dragging && !state.moved && event.timeMs - state.downAtMs >= LONG_PRESS_MS) {
                PageBarStep(PageBarState.Idle, PageBarEffect.OpenPagePanel)
            } else {
                PageBarStep(state)
            }
        PageBarEvent.Cancel -> PageBarStep(PageBarState.Idle)
    }

    private fun moved(state: PageBarState.Dragging, point: Point): PageBarState.Dragging {
        val armed = state.armedEdges + Side.entries.filterNot { inEdge(it, point.x) }
        val edge = pickedEdge(point.x, armed)
        val forward = if (direction.leadingSide == Side.Right) state.origin.x - point.x else point.x - state.origin.x
        val preview = when (edge) {
            null -> (state.startPage + pageBarSteps(forward)).coerceIn(0, pageCount - 1)
            direction.leadingSide -> 0
            else -> pageCount - 1
        }
        return state.copy(
            preview = preview,
            moved = state.moved || hypot(point.x - state.origin.x, point.y - state.origin.y) > touchSlop,
            cancelled = point.y <= top - height,
            armedEdges = armed,
        )
    }

    /** The end of the bar a finger at [x] picks, out of the [armed] edges it has left once since it pressed. */
    fun pickedEdge(x: Float, armed: Set<Side>): Side? = armed.firstOrNull { inEdge(it, x) }

    private fun inEdge(side: Side, x: Float): Boolean =
        if (side == Side.Left) x <= left + PAGE_BAR_EDGE_DP else x >= right - PAGE_BAR_EDGE_DP
}

sealed interface PageBarState {
    data object Idle : PageBarState

    /**
     * [preview] is the page to show above the bar, grayed out while [cancelled]. An edge selects its
     * end page only once [armedEdges] holds it, so a press that starts at an edge has to leave it first.
     */
    data class Dragging(
        val origin: Point,
        val downAtMs: Long,
        val startPage: Int,
        val preview: Int,
        val moved: Boolean,
        val cancelled: Boolean,
        val armedEdges: Set<Side>,
    ) : PageBarState
}

sealed interface PageBarEvent {
    /** [page] is the head page of the current view. */
    data class Down(val point: Point, val timeMs: Long, val page: Int) : PageBarEvent
    data class Move(val point: Point) : PageBarEvent
    data class Up(val point: Point) : PageBarEvent

    /** Sent [LONG_PRESS_MS] after the press. */
    data class Hold(val timeMs: Long) : PageBarEvent
    data object Cancel : PageBarEvent
}

sealed interface PageBarEffect {
    data object OpenPagePanel : PageBarEffect

    /** Move to [page] and anchor the layout there. */
    data class GoTo(val page: Int) : PageBarEffect
}

data class PageBarStep(val state: PageBarState, val effect: PageBarEffect? = null)
