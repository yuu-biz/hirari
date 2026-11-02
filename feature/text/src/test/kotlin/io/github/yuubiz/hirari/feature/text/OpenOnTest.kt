package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.model.BookId
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenOnTest {
    private var closes = 0
    private val text = OpenedText(BookId("text"), "a.txt", "a.txt", book = null, pictures = null) { closes++ }

    @Test
    fun `a caller cancelled once the text is made closes the text`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        lateinit var caller: Job
        caller = launch { openOn(io) { TextOpenResult.Opened(text, ByteArray(0)).also { caller.cancel() } } }
        advanceUntilIdle()
        assertEquals(1, closes)
    }

    @Test
    fun `a caller that stays gets the text open`() = runTest {
        val result = openOn(StandardTestDispatcher(testScheduler)) { TextOpenResult.Opened(text, ByteArray(0)) }
        assertIs<TextOpenResult.Opened>(result)
        assertEquals(0, closes)
    }
}
