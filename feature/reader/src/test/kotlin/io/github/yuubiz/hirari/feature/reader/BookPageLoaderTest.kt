package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BookPageLoaderTest {
    @Test
    fun aPageThatRunsOutOfMemoryIsMadeOnceMoreAtHalfItsSides() = runBlocking {
        val tried = mutableListOf<PixelSize>()
        val result = smallerOnOutOfMemory(PixelSize(4000, 3001)) { size ->
            tried += size
            if (size.width > 2000) throw OutOfMemoryError()
            PageResult.Ready(size)
        }
        assertEquals(listOf(PixelSize(4000, 3001), PixelSize(2000, 1500)), tried)
        assertEquals(PageResult.Ready(PixelSize(2000, 1500)), result)
    }

    @Test
    fun aPageThatRunsOutOfMemoryTwiceFailsAndCanBeRetried() = runBlocking {
        var tries = 0
        val result = smallerOnOutOfMemory<Unit>(PixelSize(1, 1)) {
            tries++
            throw OutOfMemoryError()
        }
        assertEquals(2, tries)
        assertEquals(PageResult.Failed(FailureReason.ReadError, retryable = true), result)
    }
}
