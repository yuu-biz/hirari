package io.github.yuubiz.hirari.core.model

import io.github.yuubiz.hirari.core.model.HalfPart.First
import io.github.yuubiz.hirari.core.model.HalfPart.Second
import io.github.yuubiz.hirari.core.model.PageView.Half
import io.github.yuubiz.hirari.core.model.PageView.One
import io.github.yuubiz.hirari.core.model.PageView.Two
import io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight
import io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutViewsTest {
    /** One row of the pairing table: the pages, the view the layout is built around, and the views in reading order. */
    private data class Row(val pattern: String, val page: Int, val toward: Turn, val views: List<PageView>, val coverAlone: Boolean = false)

    @Test
    fun spreadPairsForwardAfterTheCurrentViewAndBackwardBeforeIt() {
        val rows = listOf(
            Row("TTTWTT", 0, Turn.Forward, listOf(Two(0, 1), One(2), One(3), Two(4, 5))),
            Row("TTTWTT", 5, Turn.Back, listOf(One(0), Two(1, 2), One(3), Two(4, 5))),
            Row("TTTWTT", 3, Turn.Back, listOf(One(0), Two(1, 2), One(3), Two(4, 5))),
            Row("TTTWTT", 2, Turn.Back, listOf(One(0), Two(1, 2), One(3), Two(4, 5))),
            Row("TTTWTT", 1, Turn.Forward, listOf(One(0), Two(1, 2), One(3), Two(4, 5))),
            Row("TTTWTT", 0, Turn.Forward, listOf(One(0), Two(1, 2), One(3), Two(4, 5)), coverAlone = true),
            Row("TTTWTT", 1, Turn.Back, listOf(One(0), One(1), One(2), One(3), Two(4, 5)), coverAlone = true),
            Row("TTTTT", 0, Turn.Forward, listOf(Two(0, 1), Two(2, 3), One(4))),
            Row("TTTTT", 3, Turn.Forward, listOf(One(0), Two(1, 2), Two(3, 4))),
            Row("TTTTT", 4, Turn.Back, listOf(One(0), Two(1, 2), Two(3, 4))),
            Row("TTTTT", 2, Turn.Back, listOf(One(0), Two(1, 2), Two(3, 4))),
            Row("TTWTTWTT", 7, Turn.Back, listOf(Two(0, 1), One(2), Two(3, 4), One(5), Two(6, 7))),
            Row("TWTTTWT", 4, Turn.Back, listOf(One(0), One(1), One(2), Two(3, 4), One(5), One(6))),
            Row("TWTTTWT", 2, Turn.Forward, listOf(One(0), One(1), Two(2, 3), One(4), One(5), One(6))),
        )
        for (row in rows) {
            assertEquals(row.views, layout(row.pattern, spread(row.coverAlone), row.page, row.toward), row.toString())
        }
    }

    @Test
    fun theViewAtAPageStartsThereGoingForwardAndEndsThereGoingBack() {
        val shapes = shapesOf("TTTWTT")
        fun at(page: Int, toward: Turn) = viewAt(shapes.size, shapes::get, spread(), page, toward)
        assertEquals(Two(1, 2), at(1, Turn.Forward))
        assertEquals(Two(0, 1), at(1, Turn.Back))
        assertEquals(One(2), at(2, Turn.Forward))
        assertEquals(One(3), at(3, Turn.Back))
        assertEquals(One(5), at(5, Turn.Forward))
        assertEquals(Two(4, 5), at(5, Turn.Back))
        val split = shapesOf("WT")
        assertEquals(Half(0, First), viewAt(split.size, split::get, splitWide(RightToLeft), 0, Turn.Forward))
        assertEquals(Half(0, Second), viewAt(split.size, split::get, splitWide(RightToLeft), 0, Turn.Back))
    }

    @Test
    fun aViewIsOneThatPairsFromEitherOfItsEnds() {
        val shapes = shapesOf("TTTWTT")
        fun isView(view: PageView) = isView(view, shapes.size, shapes::get, spread())
        assertTrue(isView(Two(1, 2)))
        assertTrue(isView(One(0)), "the first page alone is what pairing back from it makes")
        assertTrue(isView(One(2)))
        assertFalse(isView(Two(2, 3)), "a wide page never pairs")
        assertFalse(isView(One(1)), "a tall page between two tall pages pairs from either end")
        assertFalse(isView(Two(5, 6)), "past the last page")
        assertFalse(isView(Half(3, First)), "Spread never splits")
    }

    @Test
    fun splitWideRightToLeftShowsTheRightHalfFirst() {
        val views = layout("WTT", splitWide(RightToLeft))
        assertEquals(listOf(Half(0, First), Half(0, Second), One(1), One(2)), views)
        assertEquals(listOf(Side.Right, Side.Left), views.take(2).map { (it as Half).part.side(RightToLeft) })
    }

    @Test
    fun splitWideLeftToRightShowsTheLeftHalfFirst() {
        val views = layout("WTT", splitWide(LeftToRight))
        assertEquals(listOf(Half(0, First), Half(0, Second), One(1), One(2)), views)
        assertEquals(listOf(Side.Left, Side.Right), views.take(2).map { (it as Half).part.side(LeftToRight) })
    }

    @Test
    fun unknownPageCountsAsTall() {
        assertEquals(listOf(Two(0, 1), One(2)), layout("TT?", spread()))
        assertEquals(listOf(Two(0, 1), One(2)), layout("TTW", spread()))
        assertEquals(listOf(Two(0, 1), Two(2, 3)), layout("TT?T", spread()))
        assertEquals(listOf(Two(0, 1), One(2), One(3)), layout("TTWT", spread()))
    }

    @Test
    fun twoPutsTheEarlierPageOnTheLeadingSide() {
        assertEquals(4, Two(4, 5).pageOn(Side.Right, RightToLeft))
        assertEquals(5, Two(4, 5).pageOn(Side.Left, RightToLeft))
        assertEquals(4, Two(4, 5).pageOn(Side.Left, LeftToRight))
    }

    @Test
    fun singleShowsEveryPageAlone() =
        assertEquals(listOf(One(0), One(1), One(2)), layout("TWT", LayoutSettings(LayoutMode.Single, RightToLeft, false)))

    @Test
    fun onePageBookIsOneView() {
        for (mode in listOf(LayoutMode.Single, LayoutMode.Spread, LayoutMode.SplitWide, LayoutMode.Vertical)) {
            for (cover in listOf(false, true)) {
                for (toward in Turn.entries) assertEquals(listOf(One(0)), layout("T", LayoutSettings(mode, RightToLeft, cover), toward = toward))
            }
        }
        assertEquals(listOf(Half(0, First), Half(0, Second)), layout("W", splitWide(RightToLeft)))
    }

    @Test
    fun autoMustBeResolvedFirst() {
        val auto = LayoutSettings(LayoutMode.Auto, RightToLeft, false)
        assertFailsWith<IllegalArgumentException> { layout("TT", auto) }
        assertEquals(LayoutMode.Spread, auto.resolve(ScreenOrientation.Landscape).mode)
        assertEquals(LayoutMode.SplitWide, auto.resolve(ScreenOrientation.Portrait).mode)
    }

    @Test
    fun everyPageIsInExactlyOneViewInOrder(): Unit = runBlocking {
        checkAll(arbSample) { sample ->
            val views = sample.views
            val covered = views.flatMap { view ->
                if (view is Half && view.part == Second) emptyList() else view.pages
            }
            assertEquals(sample.shapes.indices.toList(), covered)
            for ((i, view) in views.withIndex()) {
                if (view is Two) assertEquals(view.earlier + 1, view.later)
                if (view is Half) {
                    val partner = if (view.part == First) views.getOrNull(i + 1) else views.getOrNull(i - 1)
                    val expected = Half(view.page, if (view.part == First) Second else First)
                    assertEquals(expected, partner, "every Half has its other half next to it")
                }
            }
        }
    }

    @Test
    fun viewsFollowTheModeRules(): Unit = runBlocking {
        checkAll(arbSample) { sample ->
            fun isWide(page: Int) = (sample.shapes[page] as? PageShape.Known)?.isWide == true
            for (view in sample.views) {
                when (sample.settings.mode) {
                    LayoutMode.Single, LayoutMode.Vertical -> assertTrue(view is One, "${sample.settings.mode} gives only One: $view")
                    LayoutMode.SplitWide -> when (view) {
                        is One -> assertTrue(!isWide(view.page), "a wide page is split: $view")
                        is Half -> assertTrue(isWide(view.page), "only wide pages are split: $view")
                        is Two -> error("SplitWide never pairs: $view")
                    }
                    LayoutMode.Spread -> {
                        assertTrue(view !is Half, "Spread never splits: $view")
                        if (view is Two) {
                            assertTrue(!isWide(view.earlier) && !isWide(view.later), "a wide page stands alone: $view")
                            assertTrue(!(sample.settings.coverAlone && view.earlier == 0), "the cover stands alone: $view")
                        }
                    }
                    LayoutMode.Auto -> error("Auto is resolved before layout")
                }
            }
        }
    }

    @Test
    fun theCurrentViewStartsOnItsPageGoingForwardAndEndsOnItGoingBack(): Unit = runBlocking {
        checkAll(arbSample) { sample ->
            val current = sample.current
            assertTrue(current in sample.views, "$current is one of ${sample.views}")
            val end = if (sample.toward == Turn.Forward) current.pages.first() else current.pages.last()
            assertEquals(sample.page, end, "$current from page ${sample.page} going ${sample.toward}")
        }
    }

    @Test
    fun theViewsNextToTheCurrentOnePairFromThePagesNextToIt(): Unit = runBlocking {
        checkAll(arbSample) { sample ->
            val views = sample.views
            val at = views.indexOf(sample.current)
            fun viewAt(page: Int, toward: Turn) = viewAt(sample.shapes.size, sample.shapes::get, sample.settings, page, toward)
            for (i in at + 1 until views.size) {
                if (views[i] is Half) continue
                assertEquals(viewAt(views[i - 1].pages.last() + 1, Turn.Forward), views[i], "after ${views[i - 1]} in $views")
            }
            for (i in 0 until at) {
                if (views[i] is Half) continue
                assertEquals(viewAt(views[i + 1].pages.first() - 1, Turn.Back), views[i], "before ${views[i + 1]} in $views")
            }
        }
    }

    @Test
    fun learningShapesAfterTheCurrentViewKeepsTheViewsUpToIt(): Unit = runBlocking {
        val cases = arbitrary {
            val sample = arbSample.bind()
            val lastShown = sample.current.pages.last()
            val learned = sample.shapes.mapIndexed { page, shape ->
                if (page > lastShown && shape == PageShape.Unknown) arbKnownShape.bind() else shape
            }
            sample to learned
        }
        checkAll(cases) { (sample, learned) ->
            val before = sample.views
            val after = layoutViews(learned.size, learned::get, sample.settings, sample.current)
            val upTo = before.indexOf(sample.current) + 1
            assertEquals(before.take(upTo), after.take(upTo))
        }
    }
}
