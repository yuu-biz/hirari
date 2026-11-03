package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.source.save.SaveProgress
import io.github.yuubiz.hirari.feature.library.SaveJob
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveNotificationsTest {
    private val tree = UriString("content://tree/saves")

    private fun job(id: Long, name: String, progress: SaveProgress?) = SaveJob(id, name, tree, progress)

    @Test
    fun `no save has no progress`() {
        assertEquals(SavingSummary(null, 0, null, null), summarize(emptyList()))
    }

    @Test
    fun `a save that has read nothing is indeterminate`() {
        assertEquals(SavingSummary("vol1.cbz", 1, null, null), summarize(listOf(job(1, "vol1.cbz", null))))
    }

    @Test
    fun `one save shows its name and its own progress`() {
        val summary = summarize(listOf(job(1, "vol1.cbz", SaveProgress(512, 2048, "vol1.cbz", 0, 1))))
        assertEquals(SavingSummary("vol1.cbz", 1, "25% 512 B / 2.0 KB", 25), summary)
    }

    @Test
    fun `several saves show their count and the progress of all together`() {
        val summary = summarize(
            listOf(
                job(1, "a.cbz", SaveProgress(1000, 1000, "a.cbz", 0, 1)),
                job(2, "b.cbz", SaveProgress(0, 3000, "b.cbz", 0, 1)),
                job(3, "c.cbz", null),
            ),
        )
        assertEquals(SavingSummary(null, 3, null, 25), summary)
    }
}
