package io.github.yuubiz.hirari.core.data

import kotlinx.coroutines.CoroutineScope
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
    fun `a change outside the allowed range is stored as the nearest allowed value`() = runBlocking {
        val file = File(folder.root, "settings.json")
        val stored = withRepository(file) { repository ->
            repository.update { it.copy(barHeightDp = 1000) }
            repository.settings.first()
        }
        assertEquals(GlobalSettings.BAR_HEIGHT_DP.last, stored.barHeightDp)
    }
}
