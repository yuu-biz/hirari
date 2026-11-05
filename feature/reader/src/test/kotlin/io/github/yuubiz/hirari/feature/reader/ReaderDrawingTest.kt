package io.github.yuubiz.hirari.feature.reader

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.core.model.pullFrame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderDrawingTest {
    private fun bitmap(color: Int) = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun render(draw: DrawScope.() -> Unit): Bitmap {
        val target = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target.asImageBitmap()), Size(100f, 100f), draw)
        return target
    }

    private fun DrawScope.page(prepared: Bitmap?, thumbnail: Bitmap?, wanted: PagePart = PagePart.Whole,
        rotation: Rotation = Rotation.None, rect: Rect = Rect(10f, 10f, 90f, 90f)) {
        drawReaderPage(prepared, thumbnail, wanted, PagePart.Whole, rotation, rect, 1f, null)
    }

    @Test
    fun aHeldThumbnailShowsBeforePreparationAndPreparedPixelsReplaceIt() {
        val thumb = bitmap(Color.RED)
        val prepared = bitmap(Color.BLUE)
        assertEquals(Color.RED, render { page(null, thumb) }.getPixel(50, 50))
        assertEquals(Color.BLUE, render { page(prepared, thumb) }.getPixel(50, 50))
        assertEquals(Color.TRANSPARENT, render { page(null, null) }.getPixel(50, 50))
    }

    @Test
    fun eachPageOfASpreadHasItsOwnPlaceholder() {
        val drawn = render {
            page(null, bitmap(Color.RED), rect = Rect(0f, 10f, 48f, 90f))
            page(null, bitmap(Color.BLUE), rect = Rect(52f, 10f, 100f, 90f))
        }
        assertEquals(Color.RED, drawn.getPixel(20, 50))
        assertEquals(Color.BLUE, drawn.getPixel(80, 50))
        assertEquals(Color.TRANSPARENT, drawn.getPixel(50, 50))
    }

    @Test
    fun splitHalvesAndRotationUseThePartOfTheTurnedThumbnail() {
        val thumb = bitmap(Color.RED).apply {
            for (x in 20 until 40) for (y in 0 until 20) setPixel(x, y, Color.BLUE)
        }
        assertEquals(Color.RED, render { page(null, thumb, PagePart(0f, 0f, 0.5f, 1f)) }.getPixel(50, 50))
        assertEquals(Color.BLUE, render { page(null, thumb, PagePart(0.5f, 0f, 1f, 1f)) }.getPixel(50, 50))
        for (rotation in Rotation.entries) {
            val bluePart = when (rotation) {
                Rotation.None -> PagePart(0.5f, 0f, 1f, 1f)
                Rotation.Quarter -> PagePart(0f, 0.5f, 1f, 1f)
                Rotation.Half -> PagePart(0f, 0f, 0.5f, 1f)
                Rotation.ThreeQuarters -> PagePart(0f, 0f, 1f, 0.5f)
            }
            val drawn = render { page(null, thumb, bluePart, rotation) }
            assertEquals(Color.BLUE, drawn.getPixel(50, 50), "$rotation")
            assertEquals(Color.TRANSPARENT, drawn.getPixel(5, 50), "the half is clipped to its place")
        }
    }

    @Test
    fun noEffectDrawsAPartialPullHintAtTheEnteringEdgeProportionalToThePull() {
        for (movement in listOf(-1, 1)) {
            fun hint(progress: Float, effect: TurnEffect = TurnEffect.None) = render {
                drawPullHint(pullFrame(effect, movement, progress), movement)
            }
            val x = if (movement > 0) 1 else 98
            val partial = hint(0.25f)
            assertTrue(Color.alpha(partial.getPixel(x, 50)) in 63..64)
            assertEquals(0, Color.alpha(partial.getPixel(99 - x, 50)))
            assertTrue(Color.alpha(hint(0.5f).getPixel(x, 50)) in 127..128)
            assertEquals(0, Color.alpha(hint(0f).getPixel(x, 50)))
            assertEquals(0, Color.alpha(hint(0.25f, TurnEffect.Slide).getPixel(x, 50)))
        }
    }
}
