package io.github.yuubiz.hirari.feature.reader

import android.graphics.Bitmap
import io.github.yuubiz.hirari.core.image.PixelRect
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.TileKey
import io.github.yuubiz.hirari.core.image.TilePlan
import io.github.yuubiz.hirari.core.image.TileSource
import io.github.yuubiz.hirari.core.image.planTiles
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rotation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val STRIP = ReaderConfig(layout = LayoutSettings(LayoutMode.Vertical, ReadingDirection.RightToLeft, coverAlone = false))

/** 800 × 6000: narrower than 1:3, so it shows as tiles, 8100 pixels tall on the portrait screen. */
private val LONG = PageShape.Known(800, 6000)

private class FakeTiles(override val size: PixelSize) : TileSource {
    val asked = mutableListOf<TileKey>()
    var closed = false
    var failing = false

    override fun plan(viewport: PixelRect, scale: Double): TilePlan = planTiles(size, viewport, scale, maxTiles = 6)

    override suspend fun tile(key: TileKey): PageResult<Bitmap> {
        asked += key
        if (failing) return PageResult.Failed(FailureReason.ConnectionLost, retryable = true)
        return PageResult.Ready(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
    }

    override fun close() {
        closed = true
    }
}

@RunWith(RobolectricTestRunner::class)
class StripTilesTest {
    private fun TestScope.tiles(sources: MutableMap<Int, FakeTiles>, changes: IntArray = IntArray(1)) = StripTiles(
        CoroutineScope(StandardTestDispatcher(testScheduler)),
        open = { page -> TileOpening.Tiled(sources.getOrPut(page) { FakeTiles(PixelSize(LONG.width, LONG.height)) }) },
        close = { it.close() },
        changed = { changes[0]++ },
    )

    @Test
    fun onlyLongPagesOnScreenShowAsTilesAndOnlyWhatTheScreenShowsOfThem() {
        val state = readerOf(listOf(LONG, TALL, LONG), STRIP, PORTRAIT).reduce(ReaderEvent.Pan(0f, -1000f))
        val windows = state.tileWindows()
        assertEquals(setOf(0), windows.keys)
        val window = windows.getValue(0)
        assertEquals(1080.0 / 800, window.scale, 1e-9)
        assertEquals(PixelRect(0, 740, 800, 2163), window.shown)
        assertEquals(emptyMap(), state.reduce(ReaderEvent.ConfigChanged(state.config.copy(rotation = Rotation.Half))).tileWindows())
        assertEquals(emptyMap(), readerOf(listOf(LONG), ReaderConfig(), PORTRAIT).tileWindows())
    }

    @Test
    fun thePlannedTilesAreReadAndDrawnAndAPageOffScreenClosesItsSource() = runTest {
        val sources = HashMap<Int, FakeTiles>()
        val changes = IntArray(1)
        val tiles = tiles(sources, changes)
        val window = TileWindow(PixelRect(0, 0, 800, 1422), 1.35)
        tiles.update(mapOf(0 to window))
        advanceUntilIdle()
        val source = sources.getValue(0)
        assertEquals(source.plan(window.shown, window.scale).all.toSet(), source.asked.toSet())
        assertEquals(source.asked.size, tiles.drawn(0).size)
        assertTrue(changes[0] > 0)
        tiles.update(emptyMap())
        assertTrue(source.closed)
        assertEquals(emptyList(), tiles.drawn(0))
    }

    @Test
    fun aPageThatComesBackWholeIsNotAskedAgain() = runTest {
        var opened = 0
        val tiles = StripTiles(CoroutineScope(StandardTestDispatcher(testScheduler)), open = { opened++; TileOpening.Whole }, close = {}, changed = {})
        tiles.update(mapOf(0 to TileWindow(PixelRect(0, 0, 10, 10), 1.0)))
        advanceUntilIdle()
        tiles.update(mapOf(0 to TileWindow(PixelRect(0, 5, 10, 15), 1.0)))
        advanceUntilIdle()
        assertEquals(1, opened)
    }

    @Test
    fun aPageWhoseTilesFailedForNowIsAskedAgainOnceItComesBackOnScreenOrTheConnectionDoes() = runTest {
        var opened = 0
        val source = FakeTiles(PixelSize(LONG.width, LONG.height))
        val tiles = StripTiles(
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            open = { opened++; if (opened < 3) TileOpening.Failed else TileOpening.Tiled(source) },
            close = { it.close() },
            changed = {},
        )
        val window = TileWindow(PixelRect(0, 0, 800, 1422), 1.35)
        tiles.update(mapOf(0 to window))
        advanceUntilIdle()
        tiles.update(mapOf(0 to window.copy(shown = PixelRect(0, 10, 800, 1432))))
        advanceUntilIdle()
        assertEquals(1, opened, "a page still on screen is not asked again at every frame")
        tiles.update(emptyMap())
        tiles.update(mapOf(0 to window))
        advanceUntilIdle()
        assertEquals(2, opened, "a page that comes back on screen is asked again")
        source.failing = true
        tiles.retry()
        advanceUntilIdle()
        assertEquals(3, opened, "a connection that came back asks again")
        assertEquals(emptyList(), tiles.drawn(0))
        source.failing = false
        tiles.retry()
        advanceUntilIdle()
        assertEquals(3, opened)
        assertEquals(source.plan(window.shown, window.scale).all.size, tiles.drawn(0).size, "the tiles that failed are read again")
    }
}
