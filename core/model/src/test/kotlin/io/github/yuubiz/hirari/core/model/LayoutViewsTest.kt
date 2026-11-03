package io.github.yuubiz.hirari.core.model

import io.github.yuubiz.hirari.core.model.HalfPart.First
import io.github.yuubiz.hirari.core.model.HalfPart.Second
import io.github.yuubiz.hirari.core.model.PageView.Half
import io.github.yuubiz.hirari.core.model.PageView.One
import io.github.yuubiz.hirari.core.model.PageView.Two
import io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight
import io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LayoutViewsTest {
    @Test
    fun spreadPairsTallPagesAroundAWidePage() =
        assertEquals(listOf(Two(0, 1), One(2), One(3), Two(4, 5)), layout("TTTWTT", spread()))

    @Test
    fun coverAloneKeepsTheFirstPageSingle() =
        assertEquals(listOf(One(0), Two(1, 2), One(3), Two(4, 5)), layout("TTTWTT", spread(coverAlone = true)))

    @Test
    fun anchorOneShiftsThePairs() =
        assertEquals(listOf(One(0), Two(1, 2), One(3), Two(4, 5)), layout("TTTWTT", spread(), anchor = 1))

    @Test
    fun leftoverLastPageStandsAlone() =
        assertEquals(listOf(Two(0, 1), Two(2, 3), One(4)), layout("TTTTT", spread()))

    @Test
    fun pagesBeforeTheAnchorPairBackward() =
        assertEquals(listOf(One(0), Two(1, 2), Two(3, 4)), layout("TTTTT", spread(), anchor = 3))

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
                assertEquals(listOf(One(0)), layout("T", LayoutSettings(mode, RightToLeft, cover)))
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
    fun anchorHeadsAView(): Unit = runBlocking {
        checkAll(arbSample) { sample ->
            val heads = sample.views.filter { it !is Half || it.part == First }.map { it.pages.first() }
            assertTrue(sample.anchor in heads, "anchor ${sample.anchor} heads none of ${sample.views}")
        }
    }

    @Test
    fun learningLaterShapesKeepsTheViewsUpToTheCurrentOne(): Unit = runBlocking {
        val cases = arbitrary {
            val sample = arbSample.bind()
            val views = sample.views
            val anchorView = views.indexOfFirst { sample.anchor in it.pages }
            val current = Arb.int(anchorView until views.size).bind()
            val lastShown = views[current].pages.last()
            val learned = sample.shapes.mapIndexed { page, shape ->
                if (page > lastShown && shape == PageShape.Unknown) arbKnownShape.bind() else shape
            }
            Triple(sample, current, learned)
        }
        checkAll(cases) { (sample, current, learned) ->
            val after = sample.copy(shapes = learned).views
            assertEquals(sample.views.take(current + 1), after.take(current + 1))
        }
    }
}
