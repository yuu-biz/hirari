package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.BookOpening
import io.github.yuubiz.hirari.core.data.LastFolder
import io.github.yuubiz.hirari.core.data.LaunchRecord
import io.github.yuubiz.hirari.core.data.LaunchRepository
import io.github.yuubiz.hirari.core.data.OpeningBook
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class StartUpTest {
    private class FailingLaunch(val failure: Exception = IOException("disk full")) : LaunchRepository {
        var writes = 0
        override suspend fun beginStart() = LaunchRecord()
        override suspend fun finishStart() { writes++; throw failure }
        override suspend fun setLastFolder(folder: LastFolder?) { writes++; throw failure }
        override suspend fun beginOpening(book: OpeningBook) = BookOpening(0, book)
        override suspend fun endOpening(opening: BookOpening, shown: Boolean) = Unit
        override suspend fun readerGuideSeen() = false
        override suspend fun markReaderGuideSeen() = Unit
        override suspend fun lastSaveFolder(): UriString? = null
        override suspend fun setLastSaveFolder(folder: UriString?) = Unit
    }

    @Test
    fun failedFolderWritesDoNotStopLaterNavigationOrStartCompletion() = runTest {
        val launch = FailingLaunch()
        flowOf(null, LastFolder(LocationId(1), "books", null)).collect { launch.recordLastFolder(it) }
        launch.completeStart()
        launch.recordLastFolder(null)
        assertEquals(4, launch.writes)
    }

    @Test
    fun cancellationStillStopsTheCollector() = runTest {
        val cancellation = CancellationException("cancelled")
        val launch = FailingLaunch(cancellation)
        try {
            launch.recordLastFolder(null)
            throw AssertionError("cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertEquals(cancellation, actual)
        }
    }
}
