package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchTarget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.random.Random

/** [rgb] in 0 to 255 through [matrix] as the platform applies it, clamped like the GPU does. */
private fun apply(matrix: FloatArray, vararg rgb: Int): List<Int> = (0 until 3).map { row ->
    val value = (0 until 3).fold(matrix[row * 5 + 4]) { sum, k -> sum + matrix[row * 5 + k] * rgb[k] } + matrix[row * 5 + 3] * 255
    Math.round(value).coerceIn(0, 255)
}

/** [pixels] sharpened row by row back into [pixels], as a mutable bitmap is. */
private fun sharpenedInPlace(pixels: IntArray, width: Int, height: Int): IntArray {
    sharpenRows(width, height, read = { y, row -> pixels.copyInto(row, 0, y * width, y * width + width) }, write = { y, row -> row.copyInto(pixels, y * width) })
    return pixels
}

private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

class ImageAdjustmentTest {
    @Test
    fun noAdjustmentNeedsNoFilter() {
        assertNull(ImageAdjust().colorMatrix())
        assertNull(ImageAdjust(sharpen = true).colorMatrix())
    }

    @Test
    fun aBrighterLevelKeepsBlackAndLiftsTheLightGreysToWhite() {
        val matrix = ImageAdjust(level = 5).colorMatrix()!!
        assertEquals(listOf(0, 0, 0), apply(matrix, 0, 0, 0))
        assertEquals(listOf(150, 150, 150), apply(matrix, 100, 100, 100))
        assertEquals(listOf(255, 255, 255), apply(matrix, 170, 170, 170))
        assertEquals(1f + 5 * LEVEL_STEP, matrix[0], 1e-6f)
        assertEquals(1f, matrix[18], "alpha passes through")
    }

    @Test
    fun aDarkerLevelKeepsWhiteAndPullsTheDarkGreysToBlack() {
        val matrix = ImageAdjust(level = -5).colorMatrix()!!
        assertEquals(listOf(255, 255, 255), apply(matrix, 255, 255, 255))
        assertEquals(listOf(0, 0, 0), apply(matrix, 85, 85, 85))
        assertEquals(listOf(128, 128, 128), apply(matrix, 170, 170, 170))
    }

    @Test
    fun eachStepOfTheLevelMovesTheMidtonesTheSameWay() {
        val mids = (-5..5).map { level -> ImageAdjust(level = level).colorMatrix()?.let { apply(it, 128, 128, 128)[0] } ?: 128 }
        assertEquals(mids.sorted(), mids)
        assertEquals(11, mids.toSet().size)
    }

    @Test
    fun grayscaleWeighsTheChannelsByBrightness() {
        val matrix = ImageAdjust(mono = true).colorMatrix()!!
        assertEquals(listOf(54, 54, 54), apply(matrix, 255, 0, 0))
        assertEquals(listOf(182, 182, 182), apply(matrix, 0, 255, 0))
        assertEquals(listOf(255, 255, 255), apply(matrix, 255, 255, 255))
    }

    @Test
    fun negativeTurnsBlackWhite() {
        val matrix = ImageAdjust(negative = true).colorMatrix()!!
        assertEquals(listOf(255, 255, 255), apply(matrix, 0, 0, 0))
        assertEquals(listOf(155, 55, 0), apply(matrix, 100, 200, 255))
    }

    @Test
    fun theLevelComesAfterTheNegative() {
        val matrix = ImageAdjust(level = 5, negative = true).colorMatrix()!!
        assertEquals(listOf(255, 255, 255), apply(matrix, 0, 0, 0), "black paper turns white, and the brighter level keeps it white")
        assertEquals(listOf(0, 0, 0), apply(matrix, 255, 255, 255))
        assertEquals(listOf(233, 233, 233), apply(matrix, 100, 100, 100))
    }

    @Test
    fun sharpenLeavesAFlatAreaAndAlphaAlone() {
        val flat = IntArray(9) { argb(90, 120, 200, 128) }
        assertContentEquals(flat, sharpenedInPlace(flat.copyOf(), 3, 3))
    }

    @Test
    fun sharpenDeepensBothSidesOfAnEdge() {
        val step = IntArray(4) { x -> if (x < 2) argb(100, 100, 100) else argb(200, 200, 200) }
        val out = sharpenedInPlace(step, 4, 1).map { it shr 16 and 0xFF }
        assertEquals(listOf(100, 80, 220, 200), out)
    }

    @Test
    fun sharpenDeepensAnEdgeAcrossRowsToo() {
        val step = IntArray(4) { y -> if (y < 2) argb(100, 100, 100) else argb(200, 200, 200) }
        val out = sharpenedInPlace(step, 1, 4).map { it shr 8 and 0xFF }
        assertEquals(listOf(100, 80, 220, 200), out)
    }

    @Test
    fun sharpenInPlaceReadsOnlyTheRowsItHasNotWritten() {
        val random = Random(7)
        val width = 13
        val height = 11
        val image = IntArray(width * height) { random.nextInt() }
        val apart = IntArray(image.size)
        sharpenRows(width, height, read = { y, row -> image.copyInto(row, 0, y * width, y * width + width) }, write = { y, row -> row.copyInto(apart, y * width) })
        assertContentEquals(apart, sharpenedInPlace(image.copyOf(), width, height))
    }

    @Test
    fun onlySharpenPreparesTheViewAgain() {
        val state = readerOf(List(2) { TALL })
        val target = state.prefetchTarget(state.current)
        val brighter = state.reduce(ReaderEvent.ConfigChanged(state.config.copy(adjust = ImageAdjust(level = 3, mono = true))))
        assertEquals(target, brighter.prefetchTarget(brighter.current))
        val sharper = state.reduce(ReaderEvent.ConfigChanged(state.config.copy(adjust = ImageAdjust(sharpen = true))))
        assertNotEquals(target, sharper.prefetchTarget(sharper.current))
        assertEquals(state.current, sharper.current)
    }

    @Test
    fun theCommandOpensTheAdjustSheet() {
        assertEquals(CommandEffect.Show(ReaderSheet.Adjust), ReaderConfig().effectOf(ReaderCommand.ImageAdjust))
        assertEquals(false, ReaderSheet.Adjust.takes(KeyCodes.DPAD_LEFT))
        assertEquals(true, ReaderSheet.Adjust.takes(KeyCodes.BACK))
    }
}
