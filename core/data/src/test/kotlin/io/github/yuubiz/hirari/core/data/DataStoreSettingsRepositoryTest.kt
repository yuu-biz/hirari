package io.github.yuubiz.hirari.core.data

import kotlinx.coroutines.CoroutineScope
import io.github.yuubiz.hirari.core.model.LibraryGrouping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import java.io.IOException

class DataStoreSettingsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private suspend fun <T> withRepository(file: File, block: suspend (SettingsRepository) -> T): T {
        val job = Job()
        try {
            return block(DataStoreSettingsRepository(openSettings(CoroutineScope(Dispatchers.IO + job)) { file }))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `a change survives reopening the store`() = runBlocking {
        val file = File(folder.root, "settings.json")
        withRepository(file) { repository ->
            repository.update { it.copy(coverAlone = true, tapBandPercent = 40) }
        }
        val reopened = withRepository(file) { it.settings.first() }
        assertEquals(GlobalSettings(coverAlone = true, tapBandPercent = 40), reopened)
    }

    @Test
    fun `circle author merge choice survives reopening with the grouping choice`() = runBlocking {
        val file = File(folder.root, "settings.json")
        withRepository(file) { repository ->
            assertEquals(true, repository.settings.first().mergeCircleAuthors)
            repository.update { it.copy(libraryGrouping = LibraryGrouping.Author, librarySubgrouping = LibraryGrouping.Series, mergeCircleAuthors = false) }
        }
        val reopened = withRepository(file) { it.settings.first() }
        assertEquals(GlobalSettings(libraryGrouping = LibraryGrouping.Author, librarySubgrouping = LibraryGrouping.Series, mergeCircleAuthors = false), reopened)
    }

    @Test
    fun `a change outside the allowed range is stored as the nearest allowed value`() = runBlocking {
        val file = File(folder.root, "settings.json")
        val stored = withRepository(file) { repository ->
            repository.update { it.copy(barHeightDp = 1000) }
            repository.settings.first()
        }
        assertEquals(GlobalSettings.BAR_HEIGHT_DP.last, stored.barHeightDp)
    }

    @Test
    fun `an unreadable file supplies defaults to every reader and reports the failure once`() = runBlocking {
        val directory = folder.newFolder("settings.json")
        withRepository(directory) { repository ->
            assertEquals(GlobalSettings(), repository.settings.first())
            assertIs<IOException>(repository.takeReadFailure())
            repeat(3) {
                assertEquals(GlobalSettings(), repository.settings.first())
                assertNull(repository.takeReadFailure())
            }
        }
    }

    @Test
    fun `corrupt settings reset silently without reporting a read failure`() = runBlocking {
        val file = folder.newFile("settings.json").apply { writeBytes(byteArrayOf(0, -1, 0x7b)) }
        withRepository(file) { repository ->
            assertEquals(GlobalSettings(), repository.settings.first())
            assertNull(repository.takeReadFailure())
        }
        assertEquals(GlobalSettings(), withRepository(file) { it.settings.first() })
    }
}
