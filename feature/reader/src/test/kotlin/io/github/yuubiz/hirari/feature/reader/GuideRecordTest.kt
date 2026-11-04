package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.BookOpening
import io.github.yuubiz.hirari.core.data.LastFolder
import io.github.yuubiz.hirari.core.data.LaunchRecord
import io.github.yuubiz.hirari.core.data.LaunchRepository
import io.github.yuubiz.hirari.core.data.OpeningBook
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class GuideRecordTest {
    private class Launch(private val failure: Throwable?) : LaunchRepository {
        var marks = 0

        override suspend fun beginStart(): LaunchRecord = LaunchRecord()

        override suspend fun finishStart() = Unit

        override suspend fun setLastFolder(folder: LastFolder?) = Unit

        override suspend fun beginOpening(book: OpeningBook): BookOpening = BookOpening(0, book)

        override suspend fun endOpening(opening: BookOpening, shown: Boolean) = Unit

        override suspend fun readerGuideSeen(): Boolean = marks > 0

        override suspend fun markReaderGuideSeen() {
            failure?.let { throw it }
            marks++
        }

        override suspend fun lastSaveFolder(): UriString? = null

        override suspend fun setLastSaveFolder(folder: UriString?) = Unit
    }

    private val uncaught = ArrayList<Throwable>()

    private fun processScope() = CoroutineScope(
        SupervisorJob() + UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, e -> uncaught += e },
    )

    @Test
    fun aGuideSeenIsWrittenToTheLaunchRecord() = runTest {
        val launch = Launch(failure = null)
        processScope().rememberGuideSeen(launch).join()
        assertEquals(1, launch.marks)
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun aWriteThatFailsDoesNotReachTheProcessAsAnUncaughtException() = runTest {
        val launch = Launch(IOException("no space left"))
        processScope().rememberGuideSeen(launch).join()
        assertEquals(0, launch.marks)
        assertEquals(emptyList<Throwable>(), uncaught)
    }
}
