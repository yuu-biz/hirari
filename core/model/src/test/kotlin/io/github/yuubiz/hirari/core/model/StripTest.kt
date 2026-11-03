package io.github.yuubiz.hirari.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val STRIP_SHAPE: Arb<PageShape> =
    Arb.element(PageShape.Unknown, PageShape.Known(70, 100), PageShape.Known(140, 100), PageShape.Known(800, 12_000), PageShape.Known(800, 1_280))
private val arbStripShapes = Arb.list(STRIP_SHAPE, 1..40)
private val PHONE = StripScreen(1080f, 2400f)
private const val DENSITY = 3f

/** A finite float from [min] to [max]. */
private fun between(min: Float, max: Float): Arb<Float> = Arb.int(0, 1_000_000).map { min + (max - min) * it / 1_000_000f }

private fun close(expected: Double, actual: Double, tolerance: Double = 1e-3) =
    assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")

class StripTest {
    @Test
    fun pagesStackWithoutGapsAtTheirHeightOverWidth() {
        val strip = Strip.of(listOf(PageShape.Known(100, 150), PageShape.Known(200, 100)))
        assertEquals(0.0, strip.top(0))
        assertEquals(1.5, strip.bottom(0))
        assertEquals(1.5, strip.top(1))
        assertEquals(2.0, strip.length)
    }

    @Test
    fun anUnknownPageTakesTheMeanOfTheKnownOrRootTwoBeforeAny() {
        assertEquals(UNKNOWN_PAGE_ASPECT, Strip.of(listOf(PageShape.Unknown)).height(0))
        val strip = Strip.of(listOf(PageShape.Known(100, 100), PageShape.Unknown, PageShape.Known(100, 300)))
        assertEquals(2.0, strip.height(1))
    }

    @Test
    fun everyPointOfTheStripIsInExactlyOnePage(): Unit = runBlocking {
        val cases = arbitrary { Strip.of(arbStripShapes.bind()).let { it to Arb.int(0, 999_999).bind() / 1e6 * it.length } }
        checkAll(cases) { (strip, y) ->
            val page = strip.pageAt(y)
            val holding = (0 until strip.pageCount).filter { strip.top(it) <= y && y < strip.bottom(it) }
            if (y < strip.length) assertEquals(listOf(page), holding)
            for (i in 1 until strip.pageCount) assertEquals(strip.bottom(i - 1), strip.top(i))
        }
    }

    @Test
    fun aPositionFindsItsWayBack(): Unit = runBlocking {
        val cases = arbitrary {
            val strip = Strip.of(arbStripShapes.bind())
            strip to StripPosition(Arb.int(0 until strip.pageCount).bind(), between(0f, 0.999f).bind())
        }
        checkAll(cases) { (strip, position) ->
            val back = strip.positionAt(strip.offsetOf(position))
            assertEquals(position.page, back.page)
            assertTrue(abs(position.fraction - back.fraction) < 1e-4f, "$position came back as $back")
        }
    }

    @Test
    fun theScreenShowsAContiguousRunHoldingThePageItIsOn(): Unit = runBlocking {
        val cases = arbitrary {
            val strip = Strip.of(arbStripShapes.bind())
            val scroll = StripScroll().panned(0f, -between(0f, 50_000f).bind(), strip, PHONE)
            strip to scroll
        }
        checkAll(cases) { (strip, scroll) ->
            val visible = scroll.visible(strip, PHONE)
            assertTrue(!visible.isEmpty() && visible.first >= 0 && visible.last < strip.pageCount, "$visible")
            assertTrue(scroll.page(strip, PHONE) in visible)
            assertEquals(scroll.anchor.page, visible.first)
        }
    }

    @Test
    fun learningAShapeAboveTheTopLeavesTheScreenOnTheSameSpotOfItsPage(): Unit = runBlocking {
        val cases = arbitrary {
            val shapes = arbStripShapes.bind() + List(10) { PageShape.Known(100, 300) }
            val strip = Strip.of(shapes)
            val scroll = StripScroll().panned(0f, -between(0f, 40_000f).bind(), strip, PHONE)
            val learned = shapes.mapIndexed { page, shape -> if (page < scroll.anchor.page && shape == PageShape.Unknown) PageShape.Known(100, 900) else shape }
            Triple(shapes, Strip.of(learned), scroll)
        }
        checkAll(cases) { (shapes, after, scroll) ->
            val before = Strip.of(shapes)
            val page = scroll.anchor.page
            fun into(strip: Strip) = scroll.top(strip, PHONE) - strip.top(page) * PHONE.scale
            if (!scroll.endInView(after, PHONE) && !scroll.endInView(before, PHONE)) {
                close(scroll.anchor.fraction * after.height(page) * PHONE.scale, into(after), 0.01)
                if (shapes[page] is PageShape.Known) close(into(before), into(after), 0.01)
            }
        }
    }

    @Test
    fun learningTheShapeOfTheTopPageKeepsTheShareOfItAboveTheScreen() {
        val unknown = Strip.of(List(4) { PageShape.Unknown })
        val scroll = StripScroll().panned(0f, -2_000f, unknown, PHONE)
        val known = Strip.of(listOf(PageShape.Unknown) + List(3) { PageShape.Known(100, 1_000) })
        val into = scroll.top(known, PHONE) - known.top(scroll.anchor.page) * PHONE.scale
        close(scroll.anchor.fraction * known.height(scroll.anchor.page) * PHONE.scale, into)
    }

    @Test
    fun aStepMovesNineTenthsOfTheScreenAndBackComesHome(): Unit = runBlocking {
        val strip = Strip.of(List(20) { PageShape.Known(800, 1_280) })
        checkAll(between(3_000f, 20_000f)) { down ->
            val start = StripScroll().panned(0f, -down, strip, PHONE)
            val forward = assertNotNull(start.stepped(Turn.Forward, 0, DENSITY, strip, PHONE)).settled(strip, PHONE)
            close(start.top(strip, PHONE) + PHONE.height * SCROLL_STEP_SHARE, forward.top(strip, PHONE), 0.01)
            val back = assertNotNull(forward.stepped(Turn.Back, 0, DENSITY, strip, PHONE)).settled(strip, PHONE)
            close(start.top(strip, PHONE), back.top(strip, PHONE), 0.01)
        }
    }

    @Test
    fun aStepAtAnEndGoesNowhere() {
        val strip = Strip.of(List(3) { PageShape.Known(800, 1_280) })
        assertNull(StripScroll().stepped(Turn.Back, 0, DENSITY, strip, PHONE))
        val end = StripScroll().panned(0f, -1e7f, strip, PHONE)
        assertTrue(end.endInView(strip, PHONE))
        assertEquals(2, end.page(strip, PHONE))
        assertNull(end.stepped(Turn.Forward, 0, DENSITY, strip, PHONE))
    }

    @Test
    fun aStepMovesAtTheStopSpeed() {
        val strip = Strip.of(List(20) { PageShape.Known(800, 1_280) })
        val step = assertNotNull(StripScroll().stepped(Turn.Forward, 100, DENSITY, strip, PHONE))
        val move = step.motion as ScrollMotion.Move
        assertEquals(kotlin.math.ceil(PHONE.height * SCROLL_STEP_SHARE / DENSITY / STOP_SPEED_DP_PER_MS).toLong(), move.motion.durationMs)
        assertEquals(StripPosition(0, 0f), step.anchor)
        val halfway = step.ticked(100 + move.motion.durationMs / 2, strip, PHONE)
        assertTrue(halfway.top(strip, PHONE) > 0 && halfway.motion != null)
        assertNull(step.ticked(100 + move.motion.durationMs, strip, PHONE).motion)
    }

    @Test
    fun aGlideRunsTheWayTheFingerWentSlowsAndEnds(): Unit = runBlocking {
        val strip = Strip.of(List(200) { PageShape.Known(800, 12_000) })
        val start = StripScroll().panned(0f, -2e6f, strip, PHONE)
        checkAll(between(-24_000f, 24_000f), Arb.long(0L, 10_000L)) { velocity, startMs ->
            val glide = start.flung(velocity, startMs, DENSITY, strip, PHONE)
            val motion = glide.motion as? ScrollMotion.Glide ?: return@checkAll assertTrue(abs(velocity / DENSITY) < GLIDE_MIN_DP_PER_S)
            var previous = start.top(strip, PHONE)
            var scroll = glide
            var now = startMs
            while (scroll.motion != null) {
                now += 16
                scroll = scroll.ticked(now, strip, PHONE)
                val top = scroll.top(strip, PHONE)
                assertTrue(top.isFinite())
                val moved = top - previous
                assertTrue(moved * -velocity >= -1e-6, "a finger moving $velocity moved the strip by $moved")
                previous = top
                assertTrue(now - startMs <= motion.durationMs + 16, "the glide ends")
            }
        }
    }

    @Test
    fun aGlideEndsAtTheEndOfTheStrip() {
        val strip = Strip.of(List(3) { PageShape.Known(800, 1_280) })
        var scroll = StripScroll().flung(-20_000f, 0, DENSITY, strip, PHONE)
        var now = 0L
        while (scroll.motion != null && now < 10_000) {
            now += 16
            scroll = scroll.ticked(now, strip, PHONE)
        }
        assertTrue(now < 10_000 && scroll.endInView(strip, PHONE))
    }

    @Test
    fun aCaughtGlideStaysWhereItIsAndAnInterruptedStepEndsAtOnce() {
        val strip = Strip.of(List(20) { PageShape.Known(800, 1_280) })
        val gliding = StripScroll().flung(-9_000f, 0, DENSITY, strip, PHONE).ticked(100, strip, PHONE)
        assertEquals(gliding.anchor, gliding.settled(strip, PHONE).anchor)
        val step = assertNotNull(StripScroll().stepped(Turn.Forward, 0, DENSITY, strip, PHONE))
        close(PHONE.height * SCROLL_STEP_SHARE.toDouble(), step.settled(strip, PHONE).top(strip, PHONE), 0.01)
    }

    @Test
    fun aStripShorterThanTheScreenSitsInItsMiddle() {
        val strip = Strip.of(listOf(PageShape.Known(1000, 500)))
        val scroll = StripScroll().panned(0f, -300f, strip, PHONE)
        close((540.0 - 2400.0) / 2.0, scroll.top(strip, PHONE))
        assertTrue(scroll.endInView(strip, PHONE))
    }

    @Test
    fun aZoomKeepsThePointUnderTheFingers(): Unit = runBlocking {
        val strip = Strip.of(List(30) { PageShape.Known(800, 1_280) })
        val cases = arbitrary {
            Triple(between(5_000f, 30_000f).bind(), between(1f, 8f).bind(), Point(between(0f, 1080f).bind(), between(0f, 2400f).bind()))
        }
        checkAll(cases) { (down, zoom, focus) ->
            val before = PHONE
            val after = PHONE.copy(zoom = zoom)
            val start = StripScroll().panned(0f, -down, strip, before)
            val zoomed = start.zoomed(before, after, focus, strip)
            val pointBefore = (start.top(strip, before) + focus.y) / before.scale
            val pointAfter = (zoomed.top(strip, after) + focus.y) / after.scale
            close(pointBefore, pointAfter, 1e-3)
            val xBefore = (start.left(before) + focus.x) / before.scale
            val xAfter = (zoomed.left(after) + focus.x) / after.scale
            close(xBefore, xAfter, 1e-3)
        }
    }

    @Test
    fun theScreenOfAnotherWidthShowsTheSamePlace(): Unit = runBlocking {
        val strip = Strip.of(List(30) { PageShape.Known(800, 1_280) })
        checkAll(between(0f, 20_000f), Arb.enum<Turn>()) { down, _ ->
            val scroll = StripScroll().panned(0f, -down, strip, PHONE)
            val landscape = StripScreen(2400f, 1080f)
            if (!scroll.endInView(strip, landscape)) {
                close(strip.offsetOf(scroll.anchor), scroll.top(strip, landscape) / landscape.scale)
            }
        }
    }
}
