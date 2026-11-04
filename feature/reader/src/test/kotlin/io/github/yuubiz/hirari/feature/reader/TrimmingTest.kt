package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.image.LumaImage
import io.github.yuubiz.hirari.core.image.MarginScan
import io.github.yuubiz.hirari.core.image.PixelRect
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.scanMargins
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.GutterStyle
import io.github.yuubiz.hirari.core.model.HalfPart
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.LoupeZoom
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.core.model.TrimStrength
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchInput
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The margins of a white page of [page] pixels, read at that size, with black content on [content]. */
internal fun scanOf(page: PixelSize, content: PixelRect): MarginScan {
    val pixels = ByteArray(page.width * page.height) { i ->
        val inside = i % page.width in content.left until content.right && i / page.width in content.top until content.bottom
        if (inside) 0 else -1
    }
    return scanMargins(LumaImage(page.width, page.height, pixels), page)
}

/** A tall page with a fifth of its height below the content and a tenth on every other side. */
internal val DEEP_BOTTOM = scanOf(PixelSize(300, 450), PixelRect(30, 45, 270, 360))

/** What the strongest trim keeps of [DEEP_BOTTOM]. */
private val DEEP_BOTTOM_KEPT = PagePart(0.1f, 0.1f, 0.9f, 0.8f)

private val SINGLE = ReaderConfig(layout = LayoutSettings(LayoutMode.Single, ReadingDirection.RightToLeft, coverAlone = false))

private fun ReaderState.learned(vararg scans: Pair<Int, MarginScan>) =
    scans.fold(this) { state, (page, scan) -> state.reduce(ReaderEvent.MarginsLearned(page, scan)) }

private fun ReaderState.configured(change: (ReaderConfig) -> ReaderConfig) = reduce(ReaderEvent.ConfigChanged(change(config)))

private fun assertPart(expected: PagePart, actual: PagePart) {
    val near = listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)
        .all { (e, a) -> kotlin.math.abs(e - a) < 0.002f }
    assertTrue(near, "expected $expected, was $actual")
}

class TrimmingTest {
    @Test
    fun theKeptPartOfAPageFillsTheScreenAndIsWhatThePageIsPreparedAs() {
        val state = readerOf(List(3) { TALL }, SINGLE.copy(trim = TrimStrength.Strongest), PORTRAIT)
        assertEquals(1620f, state.geometry()!!.size.height, 0.5f)
        val trimmed = state.learned(0 to DEEP_BOTTOM)
        val geometry = trimmed.geometry()!!
        assertEquals(1080f, geometry.size.width, 0.5f)
        assertEquals(1080f * (TALL.height * 0.7f) / (TALL.width * 0.8f), geometry.size.height, 0.5f)
        val target = trimmed.prefetchTarget(trimmed.current)!!
        assertPart(DEEP_BOTTOM_KEPT, target.steps.single().part)
        assertEquals(PixelSize(1080, 1418), target.sizes.single())
    }

    @Test
    fun thePagesOfAPairCutTheirTopAndBottomByTheSmallerShare() {
        val deepTop = scanOf(PixelSize(300, 450), PixelRect(30, 90, 270, 405))
        val shallow = scanOf(PixelSize(300, 450), PixelRect(30, 45, 270, 405))
        val strongest = spreadConfig().copy(trim = TrimStrength.Strongest)
        val pair = readerOf(List(2) { TALL }, strongest).learned(0 to deepTop, 1 to shallow)
        assertEquals(PageView.Two(0, 1), pair.current)
        val (first, second) = pair.keptParts(pair.current)
        assertPart(PagePart(0.1f, 0.1f, 0.9f, 0.9f), first)
        assertPart(second, first)
        val geometry = pair.geometry()!!
        assertEquals(geometry.pages[0].height, geometry.pages[1].height)
        val alone = readerOf(List(2) { TALL }, SINGLE.copy(trim = TrimStrength.Strongest)).learned(0 to deepTop)
        assertPart(PagePart(0.1f, 0.2f, 0.9f, 0.9f), alone.keptParts(alone.current).single())
    }

    @Test
    fun aPairStaysWholeUntilBothPagesAreRead() {
        val pair = readerOf(List(2) { TALL }, spreadConfig().copy(trim = TrimStrength.Strongest)).learned(0 to DEEP_BOTTOM)
        assertEquals(listOf(PagePart.Whole, PagePart.Whole), pair.keptParts(pair.current))
    }

    @Test
    fun theHalvesOfAWidePageSplitWhatTheWholePageKeepsAtTheFold() {
        val offCentre = scanOf(PixelSize(400, 300), PixelRect(20, 30, 360, 270))
        val config = ReaderConfig(layout = LayoutSettings(LayoutMode.SplitWide, ReadingDirection.RightToLeft, coverAlone = false), trim = TrimStrength.Strongest)
        val state = readerOf(listOf(WIDE), config, PORTRAIT).learned(0 to offCentre)
        val first = assertIs<PageView.Half>(state.current)
        assertEquals(HalfPart.First, first.part)
        val right = state.prefetchTarget(first)!!.steps.single().part
        assertPart(PagePart(0.5f, 0.1f, 0.95f, 0.9f), right)
        val second = state.pagination.views[1]
        assertPart(PagePart(0.05f, 0.1f, 0.5f, 0.9f), state.prefetchTarget(second)!!.steps.single().part)
        val rect = state.geometry()!!.pages.single()
        assertEquals(WIDE.width * 0.45f / (WIDE.height * 0.8f), rect.width / rect.height, 0.01f)
    }

    @Test
    fun learningTheMarginsNeverChangesHowTheViewsAreMadeUp() {
        val narrowed = scanOf(PixelSize(400, 300), PixelRect(60, 10, 340, 290))
        val config = ReaderConfig(layout = LayoutSettings(LayoutMode.Auto, ReadingDirection.RightToLeft, coverAlone = false), trim = TrimStrength.Strongest)
        val before = readerOf(listOf(TALL, WIDE, TALL, TALL), config)
        val after = before.learned(0 to DEEP_BOTTOM, 1 to narrowed, 2 to DEEP_BOTTOM, 3 to DEEP_BOTTOM)
        assertEquals(before.pagination.views, after.pagination.views)
        assertEquals(before.pagination.shapes, after.pagination.shapes)
    }

    @Test
    fun offTrimsNothingWhateverIsKnown() {
        val state = readerOf(List(3) { TALL }, SINGLE, PORTRAIT)
        val learned = state.learned(0 to DEEP_BOTTOM)
        assertEquals(state.geometry(), learned.geometry())
        assertEquals(state.prefetchTarget(state.current), learned.prefetchTarget(learned.current))
        assertEquals(false, learned.prefetchInput().readsMargins(1))
    }

    @Test
    fun anotherStrengthTrimsFromTheSameMarginsWithoutReadingThemAgain() {
        val strongest = readerOf(List(3) { TALL }, SINGLE.copy(trim = TrimStrength.Strongest), PORTRAIT).learned(0 to DEEP_BOTTOM)
        val weak = strongest.configured { it.copy(trim = TrimStrength.Weak) }
        assertEquals(strongest.margins, weak.margins)
        assertEquals(false, weak.prefetchInput().readsMargins(0))
        assertEquals(true, weak.prefetchInput().readsMargins(1))
        assertPart(PagePart(0.06f, 0.06f, 0.94f, 0.88f), weak.keptParts(weak.current).single())
    }

    @Test
    fun aPageReadTwiceKeepsWhatItReadFirst() {
        val other = scanOf(PixelSize(300, 450), PixelRect(10, 10, 290, 440))
        val state = readerOf(List(3) { TALL }, SINGLE.copy(trim = TrimStrength.Strongest), PORTRAIT).learned(0 to DEEP_BOTTOM, 0 to other)
        assertPart(DEEP_BOTTOM_KEPT, state.keptParts(state.current).single())
    }

    @Test
    fun aRotationForgetsTheMarginsReadTheOtherWayUp() {
        val state = readerOf(List(3) { TALL }, SINGLE.copy(trim = TrimStrength.Strongest), PORTRAIT).learned(0 to DEEP_BOTTOM)
        val turned = state.configured { it.copy(rotation = Rotation.Half) }
        assertTrue(turned.margins.isEmpty())
        assertEquals(PagePart.Whole, turned.keptParts(turned.current).single())
        assertEquals(true, turned.prefetchInput().readsMargins(0))
    }

    @Test
    fun theMarginsOfALongBookAreHeldForThePagesNearestTheReader() {
        val state = readerOf(List(400) { TALL }, SINGLE.copy(trim = TrimStrength.Strongest), PORTRAIT, start = 300)
        val learned = state.learned(*Array(400) { it to DEEP_BOTTOM })
        assertEquals(MARGINS_HELD, learned.margins.size)
        assertTrue(300 in learned.margins && 399 in learned.margins && 0 !in learned.margins, "held ${learned.margins.keys.min()}..${learned.margins.keys.max()}")
    }

    @Test
    fun theLoupeReadsTheKeptPartAtItsOwnSize() {
        val big = PageShape.Known(3000, 4500)
        val config = SINGLE.copy(trim = TrimStrength.Strongest, loupeZoom = LoupeZoom.Source100)
        val state = readerOf(List(2) { big }, config, PORTRAIT).learned(0 to DEEP_BOTTOM).reduce(ReaderEvent.LoupeShown(Point(500f, 900f)))
        val source = assertNotNull(state.loupeSource())
        assertPart(DEEP_BOTTOM_KEPT, source.steps.part)
        assertEquals(PixelSize(2400, 3150), source.size)
        val shown = state.geometry()!!.pages.single().height / 3150f
        assertEquals(1f / shown, state.loupeView(0)!!.magnification, 0.001f)
    }

    @Test
    fun theTrimCommandGoesThroughEveryStrengthAndBackToOff() {
        val strengths = generateSequence(ReaderConfig()) { assertIs<CommandEffect.Configure>(it.effectOf(ReaderCommand.Trim)).config }
            .take(6).map { it.trim }.toList()
        assertEquals(
            listOf(TrimStrength.Off, TrimStrength.Weak, TrimStrength.Medium, TrimStrength.Strong, TrimStrength.Strongest, TrimStrength.Off),
            strengths,
        )
    }

    @Test
    fun aStrengthChosenInTheReaderIsKeptForTheBookUntilItMatchesTheGlobalOneAgain() {
        val global = ReaderConfig(trim = TrimStrength.Weak)
        val stronger = global.copy(trim = TrimStrength.Strong)
        val kept = BookOverrides(rotation = Rotation.Quarter).changedBy(global, stronger, global)
        assertEquals(BookOverrides(rotation = Rotation.Quarter, trim = TrimStrength.Strong), kept)
        assertEquals(BookOverrides(rotation = Rotation.Quarter), kept.changedBy(stronger, global, global))
        assertEquals(kept, kept.changedBy(stronger, stronger.copy(gutter = GutterStyle.None), global))
    }

    @Test
    fun aPagePreparedForAnotherTrimDrawsTheSharedPartAtTheRightScale() {
        val bitmap = PixelSize(1000, 1500)
        val untrimmed = pageDraw(DEEP_BOTTOM_KEPT, PagePart.Whole, bitmap, Rect(0f, 0f, 800f, 1050f))!!
        assertEquals(PixelRect(100, 150, 900, 1200), untrimmed.source)
        assertEquals(Rect(0f, 0f, 800f, 1050f), untrimmed.destination)
        val trimmed = pageDraw(PagePart.Whole, DEEP_BOTTOM_KEPT, PixelSize(800, 1050), Rect(0f, 0f, 1000f, 1500f))!!
        assertEquals(PixelRect(0, 0, 800, 1050), trimmed.source)
        assertEquals(Rect(100f, 150f, 900f, 1200f), trimmed.destination)
        assertNull(pageDraw(PagePart.Whole.half(Side.Left), PagePart.Whole.half(Side.Right), bitmap, Rect(0f, 0f, 1f, 1f)))
    }
}
