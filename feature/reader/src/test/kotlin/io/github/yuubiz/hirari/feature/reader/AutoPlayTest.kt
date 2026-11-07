package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BarDisplay
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.Turn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

private val SINGLE = ReaderConfig(barDisplay = BarDisplay.Hidden, layout = LayoutSettings(LayoutMode.Single, ReadingDirection.RightToLeft, coverAlone = false), autoPlayMs = 1500)

/** The clock of 自動再生 at [nowMs], as the screen's timer sends it. */
private fun ReaderState.at(nowMs: Long) = reduce(ReaderEvent.AutoPlayDue(nowMs))

class AutoPlayTest {
    @Test
    fun theFirstStepComesOneIntervalAfterTheStart() {
        val playing = readerOf(List(3) { TALL }, SINGLE).reduce(ReaderEvent.AutoPlayStarted(nowMs = 1000))
        assertEquals(AutoPlay(2500), playing.autoPlay)
        assertSame(playing, playing.at(2499))
        val stepped = playing.at(2500)
        assertEquals(PageView.One(1), stepped.current)
        assertEquals(AutoPlay(4000), stepped.autoPlay)
    }

    @Test
    fun aLateTickStepsOnceAndCountsTheNextIntervalFromItself() {
        val stepped = readerOf(List(3) { TALL }, SINGLE).reduce(ReaderEvent.AutoPlayStarted(0)).at(1900)
        assertEquals(PageView.One(1), stepped.current)
        assertEquals(AutoPlay(3400), stepped.autoPlay)
    }

    @Test
    fun aViewLargerThanTheScreenIsReadStopByStopBeforeTheNextView() {
        val config = SINGLE.copy(scaleMode = ScaleMode.FitWidth)
        var state = readerOf(List(2) { TALL }, config, LANDSCAPE).reduce(ReaderEvent.AutoPlayStarted(0))
        val stops = state.geometry()!!.stops(config).stops.size
        var now = 0L
        repeat(stops - 1) {
            now += config.autoPlayMs
            state = state.at(now)
            assertEquals(PageView.One(0), state.current)
        }
        state = state.at(now + config.autoPlayMs)
        assertEquals(PageView.One(1), state.current)
        assertNotNull(state.autoPlay)
    }

    @Test
    fun itStopsAtTheEndOfTheBookAndStaysThere() {
        val last = readerOf(List(2) { TALL }, SINGLE).reduce(ReaderEvent.AutoPlayStarted(0)).at(1500)
        assertEquals(PageView.One(1), last.current)
        val ended = last.at(3000)
        assertNull(ended.autoPlay)
        assertEquals(PageView.One(1), ended.current)
        assertEquals(ended, ended.at(4500))
    }

    @Test
    fun aMoveOfTheReadersOwnEndsIt() {
        val playing = readerOf(List(4) { TALL }, SINGLE).reduce(ReaderEvent.AutoPlayStarted(0))
        assertNull(playing.reduce(ReaderEvent.Act(ReaderAction.TurnView(Turn.Back), 10)).autoPlay)
        assertNull(playing.reduce(ReaderEvent.GoTo(3)).autoPlay)
        assertNull(playing.reduce(ReaderEvent.Pan(0f, 5f)).autoPlay)
        val stopped = playing.reduce(ReaderEvent.AutoPlayStopped)
        assertNull(stopped.autoPlay)
        assertEquals(PageView.One(0), stopped.at(1500).current)
    }

    @Test
    fun theIntervalComesFromTheSettingsAndTheCommandStartsIt() {
        assertEquals(2500, ReaderConfig.of(GlobalSettings(autoPlaySeconds = 2.5)).autoPlayMs)
        assertEquals(CommandEffect.StartAutoPlay, ReaderConfig(barDisplay = BarDisplay.Hidden).effectOf(ReaderCommand.AutoPlay))
    }
}
