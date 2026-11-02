package io.github.yuubiz.hirari.core.image

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

internal const val PAPER = 240
internal const val INK = 20

/** A page drawn at the analysis size. Content drawn with [frame] covers exactly the rectangle it is given. */
internal class TestPage(val width: Int, val height: Int, paper: Int = PAPER) {
    private val pixels = ByteArray(width * height) { paper.toByte() }

    operator fun set(x: Int, y: Int, value: Int) {
        pixels[y * width + x] = value.coerceIn(0, 255).toByte()
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    fun fill(rect: PixelRect, value: Int) {
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) this[x, y] = value
    }

    /** Panels with an [ink] border filling [rect]: screentone above, text marks in the middle, a wash below. */
    fun frame(rect: PixelRect, ink: Int = INK, paper: Int = PAPER) {
        border(rect, ink)
        if (rect.height < 40) return
        val third = rect.height / 3
        val panels = listOf(
            PixelRect(rect.left + 6, rect.top + 6, rect.right - 6, rect.top + third - 3),
            PixelRect(rect.left + 6, rect.top + third + 3, rect.right - 6, rect.top + 2 * third - 3),
            PixelRect(rect.left + 6, rect.top + 2 * third + 3, rect.right - 6, rect.bottom - 6),
        )
        panels.forEach { border(it, ink) }
        tone(panels[0].inset(3), ink)
        for (x in panels[1].left + 8 until panels[1].right - 8 step 12) {
            for (y in panels[1].top + 6 until panels[1].bottom - 6 step 9) fill(PixelRect(x, y, x + 6, y + 6), ink)
        }
        val wash = panels[2].inset(3)
        for (y in wash.top until wash.bottom) for (x in wash.left until wash.right) {
            this[x, y] = paper + (ink - paper) * (x - wash.left) / wash.width.coerceAtLeast(1)
        }
    }

    fun border(rect: PixelRect, ink: Int = INK) {
        fill(PixelRect(rect.left, rect.top, rect.right, rect.top + 2), ink)
        fill(PixelRect(rect.left, rect.bottom - 2, rect.right, rect.bottom), ink)
        fill(PixelRect(rect.left, rect.top, rect.left + 2, rect.bottom), ink)
        fill(PixelRect(rect.right - 2, rect.top, rect.right, rect.bottom), ink)
    }

    /** Dots of 2 x 2 every 4 pixels, a quarter of [rect]. */
    fun tone(rect: PixelRect, ink: Int = INK) {
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            if (x % 4 < 2 && y % 4 < 2) this[x, y] = ink
        }
    }

    /** Uniform grain of up to [amplitude] levels on every pixel. */
    fun grain(amplitude: Int, seed: Long) {
        val random = Random(seed)
        for (y in 0 until height) for (x in 0 until width) this[x, y] = this[x, y] + random.nextInt(-amplitude, amplitude + 1)
    }

    fun luma(): LumaImage = LumaImage(width, height, pixels.copyOf())

    private fun PixelRect.inset(by: Int) = PixelRect(left + by, top + by, right - by, bottom - by)
}

/** A portrait page of [content] at the analysis size of a 1448 x 2048 page. */
internal fun portrait(content: PixelRect, paper: Int = PAPER, ink: Int = INK, draw: TestPage.() -> Unit = {}): TestPage =
    TestPage(362, 512, paper).apply {
        frame(content, ink, paper)
        draw()
    }

internal val PORTRAIT_PAGE = PixelSize(1448, 2048)

/** Content with margins of 30, 40, 28 and 44 lines at the analysis size. */
internal val ORDINARY = PixelRect(30, 40, 334, 468)

/**
 * The analysis copy of a page of [page] pixels of [paper] with a solid block of [content], [contrast] levels darker,
 * as the steps of [downscaleSteps] average it: each step a bilinear filter that samples at pixel centres, which is
 * a 2 x 2 box where it halves an even side.
 */
internal fun averagedCopy(page: PixelSize, content: PixelRect, contrast: Int, paper: Int = PAPER): LumaImage {
    val size = trimAnalysisSize(page)
    val steps = downscaleSteps(page, size)
    val across = coverage(page.width, steps.map { it.width }, content.left, content.right)
    val down = coverage(page.height, steps.map { it.height }, content.top, content.bottom)
    val pixels = ByteArray(size.width * size.height) { i ->
        (paper - contrast * across[i % size.width] * down[i / size.width]).roundToInt().toByte()
    }
    return LumaImage(size.width, size.height, pixels)
}

/** For each pixel of a side of [side] pixels taken through [steps], the share of its value from pixels [from] until [until]. */
private fun coverage(side: Int, steps: List<Int>, from: Int, until: Int): DoubleArray {
    var shares = DoubleArray(side) { if (it in from until until) 1.0 else 0.0 }
    var length = side
    for (next in steps) {
        val source = shares
        val last = length - 1
        shares = DoubleArray(next) { j ->
            val x = ((j + 0.5) * length / next - 0.5).coerceIn(0.0, last.toDouble())
            val i = floor(x).toInt()
            val f = x - i
            source[i] * (1 - f) + source[min(i + 1, last)] * f
        }
        length = next
    }
    return shares
}
