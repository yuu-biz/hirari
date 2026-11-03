package io.github.yuubiz.hirari.core.data

import androidx.datastore.core.DataStore
import io.github.yuubiz.hirari.core.model.LocationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The record's own logic over an in-memory store, and its file format through the serializer. A file-backed
 * DataStore cannot write a file twice on a Windows JVM, whose rename does not replace an existing file.
 */
class LaunchRepositoryTest {
    private class MemoryStore(initial: StoredLaunch = StoredLaunch()) : DataStore<StoredLaunch> {
        val value = MutableStateFlow(initial)
        override val data: Flow<StoredLaunch> = value

        override suspend fun updateData(transform: suspend (t: StoredLaunch) -> StoredLaunch): StoredLaunch =
            transform(value.value).also { value.value = it }
    }

    /** What a restart reads back: the stored value through the file format. */
    private fun MemoryStore.restarted(): MemoryStore = runBlocking {
        val bytes = ByteArrayOutputStream().also { LaunchSerializer.writeTo(value.value, it) }.toByteArray()
        MemoryStore(LaunchSerializer.readFrom(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `starts that never finish add up across restarts and a finished one clears them`() = runBlocking {
        val store = MemoryStore()
        DataStoreLaunchRepository(store).beginStart()
        DataStoreLaunchRepository(store).beginStart()
        val launch = DataStoreLaunchRepository(store.restarted())
        assertEquals(2, launch.beginStart().unfinishedStarts)
        launch.finishStart()
        assertEquals(0, launch.beginStart().unfinishedStarts)
    }

    @Test
    fun `the last folder survives a restart until it is cleared`() = runBlocking {
        val store = MemoryStore()
        val last = LastFolder(LocationId(3), "漫画/作品", "primary:漫画/作品")
        DataStoreLaunchRepository(store).setLastFolder(last)
        val launch = DataStoreLaunchRepository(store.restarted())
        assertEquals(last, launch.beginStart().lastFolder)
        launch.setLastFolder(null)
        assertEquals(null, launch.beginStart().lastFolder)
    }

    @Test
    fun `the guide is unseen until it is marked and stays seen across restarts`() = runBlocking {
        val store = MemoryStore()
        assertEquals(false, DataStoreLaunchRepository(store).readerGuideSeen())
        DataStoreLaunchRepository(store).markReaderGuideSeen()
        val launch = DataStoreLaunchRepository(store.restarted())
        assertEquals(true, launch.readerGuideSeen())
        launch.beginStart()
        launch.finishStart()
        launch.setLastFolder(null)
        assertEquals(true, launch.readerGuideSeen())
    }

    @Test
    fun `a record written before the guide existed reads as not seen`() = runBlocking {
        val old = """{"unfinishedStarts":1}""".toByteArray()
        assertEquals(StoredLaunch(unfinishedStarts = 1), LaunchSerializer.readFrom(ByteArrayInputStream(old)))
    }

    @Test
    fun `an unreadable file starts over`() = runBlocking {
        assertEquals(StoredLaunch(), LaunchSerializer.readFrom(ByteArrayInputStream("not json".toByteArray())))
        assertEquals(StoredLaunch(), LaunchSerializer.readFrom(ByteArrayInputStream("[1, 2]".toByteArray())))
    }
}
