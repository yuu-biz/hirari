package io.github.yuubiz.hirari.core.model

import io.github.yuubiz.hirari.core.model.HalfPart.First
import io.github.yuubiz.hirari.core.model.HalfPart.Second
import io.github.yuubiz.hirari.core.model.PageView.Half
import io.github.yuubiz.hirari.core.model.PageView.One
import io.github.yuubiz.hirari.core.model.PageView.Two
import io.github.yuubiz.hirari.core.model.PaginationEvent.GoTo
import io.github.yuubiz.hirari.core.model.PaginationEvent.Next
import io.github.yuubiz.hirari.core.model.PaginationEvent.OrientationChanged
import io.github.yuubiz.hirari.core.model.PaginationEvent.Prev
import io.github.yuubiz.hirari.core.model.PaginationEvent.SettingsChanged
import io.github.yuubiz.hirari.core.model.PaginationEvent.ShapeLearned
import io.github.yuubiz.hirari.core.model.PaginationEvent.Shift
import io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight
import io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft
import io.github.yuubiz.hirari.core.model.ScreenOrientation.Landscape
import io.github.yuubiz.hirari.core.model.ScreenOrientation.Portrait
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
import kotlin.test.assertSame

class PaginationTest {
    /** Opens a book whose shapes are all known, as if the reader had already looked at every page. */
    private fun book(pattern: String, settings: LayoutSettings = spread(), start: Int = 0): Pagination =
        shapesOf(pattern).foldIndexed(Pagination.open(pattern.length, settings, Landscape, start)) { page, state, shape ->
            if (shape is PageShape.Known) state.reduce(ShapeLearned(page, shape)) else state
        }

    private fun Pagination.after(vararg events: PaginationEvent) = events.fold(this) { state, event -> state.reduce(event) }

    @Test
    fun opensOnTheSavedViewStart() {
        val state = book("TTTTT", start = 3)
        assertEquals(Two(3, 4), state.current)
        assertEquals(Turn.Forward, state.entered)
    }

    @Test
    fun nextAndPrevStopAtTheEnds() {
        val start = book("TTTT")
        assertSame(start, start.reduce(Prev))
        val end = start.after(Next)
        assertEquals(Two(2, 3), end.current)
        assertSame(end, end.reduce(Next))
    }

    @Test
    fun goToAnchorsOnTheTargetPage() {
        val state = book("TTTTT").reduce(GoTo(3))
        assertEquals(listOf(One(0), Two(1, 2), Two(3, 4)), state.views)
        assertEquals(Two(3, 4), state.current)
    }

    @Test
    fun shiftForwardShowsTheViewThatStartsOnTheNextPage() {
        val state = book("TTTWTT").reduce(Shift(forward = true))
        assertEquals(listOf(One(0), Two(1, 2), One(3), Two(4, 5)), state.views)
        assertEquals(Two(1, 2), state.current)
    }

    @Test
    fun shiftBackUndoesShiftForward() {
        val state = book("TTTWTT").after(Shift(forward = true), Shift(forward = false))
        assertEquals(Two(0, 1), state.current)
    }

    @Test
    fun shiftPastTheFirstPageDoesNothing() {
        val state = book("TTTT")
        assertSame(state, state.reduce(Shift(forward = false)))
    }

    @Test
    fun shiftForwardOnAWidePageShowsThePagesAfterItPairedAgain() {
        val onWide = book("TTTWTTT").after(GoTo(5), Prev, Prev)
        assertEquals(One(3), onWide.current)
        val shifted = onWide.reduce(Shift(forward = true))
        assertEquals(Two(4, 5), shifted.current)
        assertEquals(One(6), shifted.reduce(Next).current)
    }

    @Test
    fun shiftBackKeepsAViewWhoseHeadDoesNotPairWithThePageBeforeIt() {
        val onWide = book("TTTWTT").after(Next, Next)
        assertEquals(One(3), onWide.current)
        assertSame(onWide, onWide.reduce(Shift(forward = false)))
        val afterWide = onWide.after(Next)
        assertEquals(Two(4, 5), afterWide.current)
        assertSame(afterWide, afterWide.reduce(Shift(forward = false)))
    }

    @Test
    fun readingBackPairsFromThePageBeforeTheView() {
        val forward = book("TTTWTT").after(Next, Next, Next)
        assertEquals(Two(4, 5), forward.current)
        val back = generateSequence(forward) { it.reduce(Prev).takeIf { prev -> prev !== it } }.map { it.current }.toList()
        assertEquals(listOf(Two(4, 5), One(3), Two(1, 2), One(0)), back)
        val again = generateSequence(book("TTTWTT").after(Next, Next, Next, Prev, Prev, Prev)) { it.reduce(Next).takeIf { next -> next !== it } }
            .map { it.current }.toList()
        assertEquals(listOf(One(0), Two(1, 2), One(3), Two(4, 5)), again)
    }

    @Test
    fun shiftOutsideSpreadDoesNothing() {
        val split = book("WTT", splitWide(RightToLeft))
        assertSame(split, split.reduce(Shift(forward = true)))
        val tall = split.after(Next, Next)
        assertEquals(One(1), tall.current)
        assertSame(tall, tall.reduce(Shift(forward = false)))
        val single = book("TTT", LayoutSettings(LayoutMode.Single, RightToLeft, false)).after(Next)
        assertSame(single, single.reduce(Shift(forward = true)))
    }

    @Test
    fun learningAWideShapeSplitsTheViewBeforeItIsShown() {
        val state = Pagination.open(4, spread(), Landscape)
            .after(ShapeLearned(0, TALL), ShapeLearned(1, TALL), Next)
        assertEquals(Two(2, 3), state.current)
        val learned = state.reduce(ShapeLearned(2, WIDE))
        assertEquals(listOf(Two(0, 1), One(2), One(3)), learned.views)
        assertEquals(One(2), learned.current)
    }

    @Test
    fun learningAWideShapeReadingBackKeepsThePageTheReaderCameFrom() {
        val state = Pagination.open(6, spread(), Landscape, start = 4).after(Prev)
        assertEquals(Two(2, 3), state.current)
        val learned = state.reduce(ShapeLearned(3, WIDE))
        assertEquals(One(3), learned.current)
        assertEquals(Two(4, 5), learned.reduce(Next).current)
    }

    @Test
    fun readingBackIntoAWidePageShowsItsSecondHalf() {
        val state = Pagination.open(3, splitWide(RightToLeft), Portrait, start = 2).after(Prev)
        assertEquals(One(1), state.current)
        assertEquals(Half(1, Second), state.reduce(ShapeLearned(1, WIDE)).current)
    }

    @Test
    fun changingTheLayoutAnchorsOnTheCurrentHead() {
        val state = book("TTTTTT").after(Next).reduce(SettingsChanged(LayoutSettings(LayoutMode.Single, RightToLeft, false)))
        assertEquals(One(2), state.current)
    }

    @Test
    fun unchangedSettingsKeepTheLayout() {
        val state = book("TTTT").reduce(GoTo(3)).after(Prev)
        assertEquals(listOf(One(0), Two(1, 2), One(3)), state.views)
        assertSame(state, state.reduce(SettingsChanged(state.settings)))
        assertSame(state, state.reduce(OrientationChanged(state.orientation)))
    }

    @Test
    fun rotatingTheScreenResolvesAuto() {
        val auto = LayoutSettings(LayoutMode.Auto, RightToLeft, coverAlone = false)
        val landscape = book("TTWT", auto)
        assertEquals(listOf(Two(0, 1), One(2), One(3)), landscape.views)
        val portrait = landscape.after(Next).reduce(OrientationChanged(Portrait))
        assertEquals(Half(2, First), portrait.current)
    }

    @Test
    fun rotatedShapesLayTheBookOutAgainFromTheHeadOfTheCurrentView() {
        val state = book("TTTTT").after(Next)
        assertEquals(Two(2, 3), state.current)
        val turned = state.reduce(PaginationEvent.ShapesChanged(state.shapes.map { (it as PageShape.Known).rotated(Rotation.Quarter) }))
        assertEquals(List(5) { One(it) }, turned.views)
        assertEquals(One(2), turned.current)
        val back = turned.reduce(PaginationEvent.ShapesChanged(state.shapes))
        assertEquals(Two(2, 3), back.current)
        assertSame(back, back.reduce(PaginationEvent.ShapesChanged(back.shapes)))
    }

    @Test
    fun changingTheDirectionKeepsTheSecondHalf() {
        val state = book("WT", splitWide(RightToLeft)).after(Next)
        assertEquals(Half(0, Second), state.current)
        assertEquals(Half(0, Second), state.reduce(SettingsChanged(splitWide(LeftToRight))).current)
    }

    /** Learns the true shapes of the pages on screen, as the reader does before it shows a view. */
    private fun Pagination.shown(truth: List<PageShape.Known>): Pagination {
        var state = this
        while (true) {
            val unknown = state.current.pages.firstOrNull { state.shapes[it] == PageShape.Unknown } ?: return state
            state = state.reduce(ShapeLearned(unknown, truth[unknown]))
        }
    }

    private class Reading(val truth: List<PageShape.Known>, val state: Pagination) {
        override fun toString() = "Reading(truth=${truth.joinToString("") { if (it.isWide) "W" else "T" }}, " +
            "shapes=${state.shapes.joinToString("") { if (it is PageShape.Known) (if (it.isWide) "W" else "T") else "?" }}, " +
            "settings=${state.settings}, orientation=${state.orientation}, entered=${state.entered}, " +
            "views=${state.views}, current=${state.current})"
    }

    private fun arbSettings(coverAlone: Arb<Boolean> = Arb.boolean()): Arb<LayoutSettings> = arbitrary {
        LayoutSettings(Arb.enum<LayoutMode>().bind(), Arb.enum<ReadingDirection>().bind(), coverAlone.bind())
    }

    /** A book read through random events. A [tallOnly] book has no wide page and never sets the cover alone. */
    private fun arbReading(tallOnly: Boolean = false): Arb<Reading> = arbitrary {
        val truth = Arb.list(if (tallOnly) Arb.constant(TALL) else arbKnownShape, 1..24).bind()
        val settings = if (tallOnly) arbSettings(Arb.constant(false)) else arbSettings()
        val events = Arb.list(
            Arb.choice(
                Arb.constant(Next),
                Arb.constant(Prev),
                Arb.int(truth.indices).map { GoTo(it) },
                Arb.boolean().map { Shift(it) },
                settings.map { SettingsChanged(it) },
                Arb.enum<ScreenOrientation>().map { OrientationChanged(it) },
            ),
            0..30,
        ).bind()
        val opened = Pagination.open(truth.size, settings.bind(), Arb.enum<ScreenOrientation>().bind(), Arb.int(truth.indices).bind())
        Reading(truth, events.fold(opened.shown(truth)) { state, event -> state.reduce(event).shown(truth) })
    }

    @Test
    fun aTurnShowsTheViewThatPairsFromThePageNextToTheCurrentOne(): Unit = runBlocking {
        checkAll(arbReading(), Arb.enum<Turn>()) { reading, turn ->
            val state = reading.state
            val turned = state.reduce(if (turn == Turn.Forward) Next else Prev)
            if (turned !== state && state.current !is Half && turned.current !is Half) {
                val page = if (turn == Turn.Forward) state.current.pages.last() + 1 else state.current.pages.first() - 1
                val settings = state.settings.resolve(state.orientation)
                assertEquals(viewAt(state.shapes.size, state.shapes::get, settings, page, turn), turned.current)
                assertEquals(turn, turned.entered)
            }
        }
    }

    /** The view [Pagination.current] would be if it were paired from its end toward [toward]. */
    private fun Pagination.pairedFrom(toward: Turn): PageView {
        val page = if (toward == Turn.Forward) current.pages.first() else current.pages.last()
        return viewAt(shapes.size, shapes::get, settings.resolve(orientation), page, toward)
    }

    @Test
    fun turningBackAndForwardReturnsToAViewThatPairsForwardFromItsHead(): Unit = runBlocking {
        checkAll(arbReading()) { reading ->
            val state = reading.state
            val back = state.reduce(Prev).shown(reading.truth)
            if (state.pairedFrom(Turn.Forward) == state.current && back !== state) assertEquals(state.current, back.reduce(Next).current)
        }
    }

    @Test
    fun turningForwardAndBackReturnsToAViewThatPairsBackFromItsEnd(): Unit = runBlocking {
        checkAll(arbReading()) { reading ->
            val state = reading.state
            val forward = state.reduce(Next).shown(reading.truth)
            if (state.pairedFrom(Turn.Back) == state.current && forward !== state) assertEquals(state.current, forward.reduce(Prev).current)
        }
    }

    @Test
    fun nextsThenAsManyPrevsReturnToTheSameViewInABookOfTallPages(): Unit = runBlocking {
        checkAll(arbReading(tallOnly = true), Arb.int(0..10)) { reading, k ->
            var state = reading.state
            var moved = 0
            repeat(k) {
                val next = state.reduce(Next).shown(reading.truth)
                if (next !== state) moved++
                state = next
            }
            repeat(moved) { state = state.reduce(Prev).shown(reading.truth) }
            assertEquals(reading.state.current, state.current)
        }
    }

    @Test
    fun prevsThenAsManyNextsReturnToTheSameViewInABookOfTallPages(): Unit = runBlocking {
        checkAll(arbReading(tallOnly = true), Arb.int(0..10)) { reading, k ->
            var state = reading.state
            var moved = 0
            repeat(k) {
                val prev = state.reduce(Prev).shown(reading.truth)
                if (prev !== state) moved++
                state = prev
            }
            repeat(moved) { state = state.reduce(Next).shown(reading.truth) }
            assertEquals(reading.state.current, state.current)
        }
    }

    @Test
    fun learningTheShapeOfAPageOutsideTheCurrentViewKeepsIt(): Unit = runBlocking {
        checkAll(arbReading(), Arb.int(0..23)) { reading, offset ->
            val state = reading.state
            val outside = state.shapes.indices.filter { it !in state.current.pages }
            if (outside.isNotEmpty()) {
                val page = outside[offset % outside.size]
                val learned = state.reduce(ShapeLearned(page, reading.truth[page]))
                assertEquals(state.current, learned.current)
            }
        }
    }
}
