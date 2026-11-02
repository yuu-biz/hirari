package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.TrimStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimTest {
    private fun trimmed(page: TestPage, strength: TrimStrength, size: PixelSize = PORTRAIT_PAGE): PageTrim =
        scanMargins(page.luma(), size).trim(strength)

    /** At 4 page pixels a line, the weak strength cuts 60% of 120, 160, 112 and 176 pixels, rounded down. */
    private val ordinaryWeak = PixelRect(72, 96, 1448 - 67, 2048 - 105)

    @Test
    fun anOrdinaryPageCutsItsShareOfEveryMarginInPagePixels() {
        val trim = trimmed(portrait(ORDINARY), TrimStrength.Weak)
        assertEquals(ordinaryWeak, trim.content)
        assertEquals(emptyList<TrimRule>(), trim.fired)
        assertEquals(PixelRect(120, 160, 1448 - 112, 2048 - 176), trimmed(portrait(ORDINARY), TrimStrength.Strongest).content)
    }

    @Test
    fun offCutsNothing() {
        val trim = trimmed(portrait(ORDINARY), TrimStrength.Off)
        assertEquals(Insets.None, trim.cut)
        assertEquals(emptyList<TrimRule>(), trim.fired)
    }

    @Test
    fun eachStrengthReadsItsRangeAndCutsItsShare() {
        val page = portrait(PixelRect(51, 143, 311, 498))
        val size = PixelSize(362, 512)
        val cuts = TrimStrength.entries.associateWith { trimmed(page, it, size).cut }
        assertEquals(
            mapOf(
                TrimStrength.Off to Insets.None,
                TrimStrength.Weak to Insets(30, 76, 30, 8),
                TrimStrength.Medium to Insets(40, 114, 40, 11),
                TrimStrength.Strong to Insets(45, 128, 45, 12),
                TrimStrength.Strongest to Insets(51, 143, 51, 14),
            ),
            cuts,
        )
    }

    @Test
    fun aDarkMarginTrimsLikeAWhiteOne() {
        val page = portrait(ORDINARY, paper = 12, ink = 235)
        assertEquals(ordinaryWeak, trimmed(page, TrimStrength.Weak).content)
    }

    @Test
    fun grainSpecksAndOneLineScratchesDoNotStopTheScan() {
        val page = portrait(ORDINARY) {
            grain(12, seed = 7)
            for (i in 0 until 30) this[40 + (i * 37) % 280, (i * 7) % 30] = INK
            fill(PixelRect(100, 20, 200, 21), INK)
            fill(PixelRect(15, 100, 16, 200), INK)
            fill(PixelRect(150, 490, 152, 491), INK)
        }
        val trim = trimmed(page, TrimStrength.Weak)
        assertEquals(ordinaryWeak, trim.content)
        assertEquals(emptyList<TrimRule>(), trim.fired)
    }

    @Test
    fun twoContentLinesInARowStartTheContent() {
        val twoLines = portrait(ORDINARY) { fill(PixelRect(100, 20, 200, 22), INK) }
        assertEquals(48, trimmed(twoLines, TrimStrength.Weak).cut.top)
    }

    @Test
    fun theThresholdOfEachStrengthDecidesWhetherASpeckIsContent() {
        val speck = portrait(ORDINARY) { fill(PixelRect(150, 20, 152, 22), INK) }
        assertEquals(48, trimmed(speck, TrimStrength.Weak).cut.top)
        assertEquals(128, trimmed(speck, TrimStrength.Medium).cut.top)
    }

    @Test
    fun marksInTheMarginBoundTheCut() {
        val page = portrait(ORDINARY) {
            fill(PixelRect(12, 200, 18, 300), INK)
            for (digit in 0 until 3) fill(PixelRect(170 + digit * 8, 490, 176 + digit * 8, 498), INK)
        }
        val trim = trimmed(page, TrimStrength.Weak)
        assertEquals(PixelRect(28, 96, 1448 - 67, 2048 - 33), trim.content)
    }

    @Test
    fun aShadowAlongOneEdgeKeepsThatEdgeAndNotTheOthers() {
        val page = portrait(ORDINARY) {
            listOf(60, 100, 140, 180, 210, 230).forEachIndexed { x, value -> fill(PixelRect(x, 0, x + 1, 512), value) }
        }
        val scan = scanMargins(page.luma(), PORTRAIT_PAGE)
        val trim = scan.trim(TrimStrength.Weak)
        assertEquals(setOf(Edge.Left), scan.bleedEdges)
        assertEquals(ordinaryWeak.copy(left = 0), trim.content)
        assertEquals(listOf(TrimRule.BleedEdge), trim.fired)
    }

    @Test
    fun artThatRunsOffAnEdgeKeepsThatEdgeEvenWhereItIsCloseToTheBackground() {
        val page = portrait(ORDINARY.copy(top = 100)) {
            for (y in 0 until 100) for (x in 30 until 334) this[x, y] = 200 + 50 * (x - 30) / 303
        }
        val trim = trimmed(page, TrimStrength.Weak)
        assertEquals(ordinaryWeak.copy(top = 0), trim.content)
        assertEquals(listOf(TrimRule.BleedEdge), trim.fired)
    }

    @Test
    fun aFullBleedSpreadIsNotCut() {
        val spread = TestPage(512, 362).apply {
            tone(PixelRect(0, 0, 512, 362))
            border(PixelRect(0, 0, 512, 362))
            border(PixelRect(40, 30, 300, 200))
        }
        val scan = scanMargins(spread.luma(), PixelSize(2048, 1448))
        val trim = scan.trim(TrimStrength.Strongest)
        assertEquals(Edge.entries.toSet(), scan.bleedEdges)
        assertEquals(Insets.None, trim.cut)
        assertEquals(listOf(TrimRule.BleedEdge), trim.fired)
    }

    @Test
    fun aTrimThatLeaves20PixelsOrLessIsVetoed() {
        val page = TestPage(36, 36).apply { frame(PixelRect(8, 8, 28, 28)) }
        val trim = trimmed(page, TrimStrength.Strongest, PixelSize(36, 36))
        assertEquals(Insets.None, trim.cut)
        assertEquals(listOf(TrimRule.TooSmall), trim.fired)
        val roomier = TestPage(38, 38).apply { frame(PixelRect(8, 8, 30, 30)) }
        assertEquals(Insets(8, 8, 8, 8), trimmed(roomier, TrimStrength.Strongest, PixelSize(38, 38)).cut)
    }

    @Test
    fun aTrimThatLeavesUnderHalfIsVetoed() {
        val trim = trimmed(portrait(PixelRect(101, 143, 261, 369)), TrimStrength.Strongest, PixelSize(362, 512))
        assertEquals(Insets.None, trim.cut)
        assertEquals(listOf(TrimRule.UnderHalf), trim.fired)
    }

    @Test
    fun aTrimThatChangesTheAspectRatioBy30PercentIsVetoed() {
        val trim = trimmed(portrait(PixelRect(31, 106, 331, 406)), TrimStrength.Strongest, PixelSize(362, 512))
        assertEquals(Insets.None, trim.cut)
        assertEquals(listOf(TrimRule.AspectChanged), trim.fired)
    }

    @Test
    fun aBlankPageIsNotCut() {
        val trim = trimmed(TestPage(362, 512), TrimStrength.Strongest)
        assertEquals(Insets.None, trim.cut)
        assertTrue(trim.fired.toString(), TrimRule.TooSmall in trim.fired)
    }

    @Test
    fun aWidePageCutsLeftAndRightByTheSmaller() {
        val spread = TestPage(512, 362).apply { frame(PixelRect(40, 30, 432, 332)) }
        val trim = scanMargins(spread.luma(), PixelSize(2048, 1448)).trim(TrimStrength.Weak)
        assertEquals(Insets(96, 72, 96, 72), trim.cut)
        assertEquals(listOf(TrimRule.WideSymmetric), trim.fired)
    }

    @Test
    fun aWidePageStaysWideWhenItsContentIsTall() {
        val page = TestPage(400, 390).apply { frame(PixelRect(20, 12, 370, 378)) }
        val trim = scanMargins(page.luma(), PixelSize(400, 390)).trim(TrimStrength.Strongest)
        assertEquals(Insets(20, 12, 20, 12), trim.cut)
        assertTrue(trim.content.width < trim.content.height)
        assertEquals(listOf(TrimRule.WideSymmetric), trim.fired)
    }

    @Test
    fun aPairCutsTopAndBottomByTheSmallerShareOfItsHeight() {
        val first = scanMargins(portrait(PixelRect(30, 20, 334, 482)).luma(), PORTRAIT_PAGE).trim(TrimStrength.Weak)
        val second = scanMargins(portrait(PixelRect(30, 40, 334, 502)).luma(), PixelSize(724, 1024)).trim(TrimStrength.Weak)
        val (a, b) = trimPair(first, second)
        assertEquals(Insets(72, 48, 67, 24), a.cut)
        assertEquals(Insets(36, 24, 33, 12), b.cut)
        assertEquals(listOf(TrimRule.PairHeight), a.fired)
        assertEquals(listOf(TrimRule.PairHeight), b.fired)
    }

    @Test
    fun aVetoedPageOfAPairLeavesTheTopAndBottomOfTheOtherUncut() {
        val first = scanMargins(portrait(ORDINARY).luma(), PORTRAIT_PAGE).trim(TrimStrength.Strongest)
        val second = scanMargins(portrait(PixelRect(101, 143, 261, 369)).luma(), PixelSize(362, 512)).trim(TrimStrength.Strongest)
        val (a, b) = trimPair(first, second)
        assertEquals(Insets(120, 0, 112, 0), a.cut)
        assertEquals(listOf(TrimRule.PairHeight), a.fired)
        assertEquals(Insets.None, b.cut)
        assertTrue(b.fired.toString(), TrimRule.UnderHalf in b.fired)
    }

    @Test
    fun aPairIsCheckedAgainAfterItsHeightsAreAligned() {
        val narrow = scanMargins(portrait(PixelRect(72, 100, 290, 412)).luma(), PixelSize(362, 512)).trim(TrimStrength.Strongest)
        assertEquals(Insets(72, 100, 72, 100), narrow.cut)
        val tall = scanMargins(portrait(PixelRect(30, 0, 334, 512)).luma(), PixelSize(362, 512)).trim(TrimStrength.Strongest)
        val (a, b) = trimPair(narrow, tall)
        assertEquals(Insets.None, a.cut)
        assertEquals(listOf(TrimRule.PairHeight, TrimRule.AspectChanged), a.fired)
        assertEquals(tall.cut, b.cut)
    }

    @Test
    fun theAnalysisCopyHasALongerSideOf512() {
        assertEquals(PixelSize(362, 512), trimAnalysisSize(PixelSize(1448, 2048)))
        assertEquals(PixelSize(512, 362), trimAnalysisSize(PixelSize(2896, 2048)))
        assertEquals(PixelSize(171, 512), trimAnalysisSize(PixelSize(1000, 3000)))
        assertEquals(PixelSize(300, 400), trimAnalysisSize(PixelSize(300, 400)))
    }

    @Test
    fun lumaWeighsTheChannelsByBt601() {
        val luma = LumaImage.fromArgb(4, 1, intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt()))
        assertEquals(listOf(255, 0, 76, 149), (0 until 4).map { luma[it, 0] })
    }
}
