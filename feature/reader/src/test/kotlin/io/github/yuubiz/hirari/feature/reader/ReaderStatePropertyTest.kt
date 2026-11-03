package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.Turn
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/spec.md section 9, M1: going forward and then back returns to the same view, and to the same stop. 縦に連続
 * has neither views to turn nor stops; its steps are checked in `StripTest`.
 */
class ReaderStatePropertyTest {
    private class Reading(val truth: List<PageShape.Known>, val state: ReaderState) {
        override fun toString() = "Reading(truth=${truth.joinToString("") { if (it.isWide) "W" else "T" }}, " +
            "config=${state.config}, viewport=${state.viewport}, views=${state.pagination.views}, " +
            "current=${state.current}, place=${state.place})"
    }

    /** Prefetch reads the shapes of a view before it is drawn, which this stands in for. */
    private fun ReaderState.shown(truth: List<PageShape.Known>): ReaderState =
        current.pages.fold(this) { state, page -> state.reduce(ReaderEvent.ShapeLearned(page, truth[page])) }
            .let { if (it.current == current) it else it.shown(truth) }

    private val arbShape: Arb<PageShape.Known> = Arb.choice(
        Arb.element(TALL, PageShape.Known(800, 1200), PageShape.Known(1200, 1700)),
        Arb.element(WIDE, PageShape.Known(2400, 1700)),
    )

    private val arbConfig: Arb<ReaderConfig> = arbitrary {
        ReaderConfig(
            layout = LayoutSettings(Arb.element(LayoutMode.entries - LayoutMode.Vertical).bind(), Arb.enum<ReadingDirection>().bind(), Arb.boolean().bind()),
            scaleMode = Arb.enum<ScaleMode>().bind(),
            stopOrder = Arb.enum<StopOrder>().bind(),
            stopMaxStep = Arb.int(1..20).bind() * 0.05f,
        )
    }

    private val arbReading: Arb<Reading> = arbitrary {
        val truth = Arb.list(arbShape, 1..24).bind()
        val viewport = Arb.element(PORTRAIT, LANDSCAPE, Viewport(800f, 1280f, 2f)).bind()
        val events = Arb.list(
            Arb.choice(
                Arb.constant(ReaderAction.TurnView(Turn.Forward)),
                Arb.constant(ReaderAction.TurnView(Turn.Back)),
                Arb.constant(ReaderAction.StepStops(Turn.Forward)),
                Arb.constant(ReaderAction.StepStops(Turn.Back)),
                Arb.boolean().map { ReaderAction.Shift(it) },
            ).map { ReaderEvent.Act(it, 0) },
            0..30,
        ).bind()
        val opened = ReaderState.open(truth.size, Arb.int(truth.indices).bind(), arbConfig.bind(), viewport).shown(truth)
        Reading(truth, events.fold(opened) { state, event -> state.reduce(event).shown(truth) })
    }

    private fun ReaderState.mark() = Pair(current, stopIndex())

    /** Goes [k] times toward [there] and back as many times as the state moved. */
    private fun roundTrip(reading: Reading, k: Int, there: ReaderAction, back: ReaderAction): ReaderState {
        var state = reading.state
        var moved = 0
        repeat(k) {
            val next = state.reduce(ReaderEvent.Act(there, 0)).shown(reading.truth)
            if (next.mark() != state.mark()) moved++
            state = next
        }
        repeat(moved) { state = state.reduce(ReaderEvent.Act(back, 0)).shown(reading.truth) }
        return state
    }

    @Test
    fun turnsForwardThenAsManyBackReturnToTheSameView(): Unit = runBlocking {
        checkAll(arbReading, Arb.int(0..12)) { reading, k ->
            val state = roundTrip(reading, k, ReaderAction.TurnView(Turn.Forward), ReaderAction.TurnView(Turn.Back))
            assertEquals(reading.state.current, state.current)
        }
    }

    @Test
    fun turnsBackThenAsManyForwardReturnToTheSameView(): Unit = runBlocking {
        checkAll(arbReading, Arb.int(0..12)) { reading, k ->
            val state = roundTrip(reading, k, ReaderAction.TurnView(Turn.Back), ReaderAction.TurnView(Turn.Forward))
            assertEquals(reading.state.current, state.current)
        }
    }

    @Test
    fun stepsForwardThenAsManyBackReturnToTheSameViewAndStop(): Unit = runBlocking {
        checkAll(arbReading, Arb.int(0..20)) { reading, k ->
            val state = roundTrip(reading, k, ReaderAction.StepStops(Turn.Forward), ReaderAction.StepStops(Turn.Back))
            assertEquals(reading.state.mark(), state.mark())
        }
    }

    @Test
    fun stepsBackThenAsManyForwardReturnToTheSameViewAndStop(): Unit = runBlocking {
        checkAll(arbReading, Arb.int(0..20)) { reading, k ->
            val state = roundTrip(reading, k, ReaderAction.StepStops(Turn.Back), ReaderAction.StepStops(Turn.Forward))
            assertEquals(reading.state.mark(), state.mark())
        }
    }
}
