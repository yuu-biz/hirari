package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun ReaderState.run(command: ReaderCommand): ReaderState =
    reduce(ReaderEvent.ConfigChanged(assertIs<CommandEffect.Configure>(config.effectOf(command)).config))

class RotationTest {
    @Test
    fun theRotateCommandTurnsAQuarterClockwiseAndComesBackAfterFour() {
        val rotations = generateSequence(ReaderConfig()) { assertIs<CommandEffect.Configure>(it.effectOf(ReaderCommand.Rotate)).config }
            .take(5).map { it.rotation }.toList()
        assertEquals(listOf(Rotation.None, Rotation.Quarter, Rotation.Half, Rotation.ThreeQuarters, Rotation.None), rotations)
    }

    @Test
    fun aQuarterTurnMakesTallPagesWideAndKeepsTheHeadOfTheView() {
        val state = readerOf(List(5) { TALL }).act(ReaderAction.TurnView(Turn.Forward)).settle()
        assertEquals(PageView.Two(2, 3), state.current)
        val turned = state.run(ReaderCommand.Rotate)
        assertEquals(PageView.One(2), turned.current)
        assertEquals(PageShape.Known(TALL.height, TALL.width), turned.pagination.shapes[2])
        val upsideDown = turned.run(ReaderCommand.Rotate)
        assertEquals(PageView.Two(2, 3), upsideDown.current)
        assertEquals(TALL, upsideDown.pagination.shapes[2])
    }

    @Test
    fun aShapeLearnedLaterIsTurnedByTheBooksRotation() {
        val state = ReaderState.open(3, 0, spreadConfig().copy(rotation = Rotation.ThreeQuarters), LANDSCAPE)
            .reduce(ReaderEvent.ShapeLearned(0, TALL))
        assertEquals(PageShape.Known(TALL.height, TALL.width), state.pagination.shapes[0])
        assertEquals(PageView.One(0), state.current)
    }

    @Test
    fun theTargetCarriesTheRotationSoTurningPreparesTheViewAgain() {
        val state = readerOf(List(2) { TALL }, spreadConfig(), PORTRAIT)
        val before = state.prefetchTarget(state.current)!!
        val turned = state.run(ReaderCommand.Rotate)
        val after = turned.prefetchTarget(turned.current)!!
        assertEquals(Rotation.None, before.steps.first().rotation)
        assertEquals(Rotation.Quarter, after.steps.single().rotation)
        val size = after.sizes.single()
        assertEquals(true, size.width > size.height, "a turned tall page is prepared wide: $size")
    }

    @Test
    fun theOrientationCommandGoesFromTheDeviceToPortraitToLandscape() {
        val orientations = generateSequence(ReaderConfig()) { assertIs<CommandEffect.Configure>(it.effectOf(ReaderCommand.Orientation)).config }
            .take(4).map { it.orientation }.toList()
        assertEquals(listOf(ReaderOrientation.System, ReaderOrientation.Portrait, ReaderOrientation.Landscape, ReaderOrientation.System), orientations)
    }
}

private fun ReaderState.act(action: ReaderAction, nowMs: Long = 0) = reduce(ReaderEvent.Act(action, nowMs))

private fun ReaderState.settle() = reduce(ReaderEvent.Tick(Long.MAX_VALUE / 2))
