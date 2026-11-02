package io.github.yuubiz.hirari

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.model.BOOK_ID_PREFIX_BYTES
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.documentBookId
import io.github.yuubiz.hirari.feature.reader.ReaderServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The central flow of M1 (docs/spec.md section 9) through the real activity: a corpus ZIP opens in the reader,
 * turns forward and back to the same view, and opens again at the view it was closed on. Every step runs under
 * the debug StrictMode, which kills the process on disk access from the main thread.
 */
class ReaderRestoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as HirariApp

    private fun press(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }

    /** The saved progress once [accept] holds, after the writer has written what it holds. */
    private suspend fun ReaderServices.saved(id: BookId, accept: (BookProgress) -> Boolean = { true }): BookProgress =
        withTimeout(15_000) {
            while (true) {
                progress.flush().join()
                val saved = storage.progress.get(id)
                if (saved != null && accept(saved)) return@withTimeout saved
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private suspend fun ReaderServices.viewStart(id: BookId, accept: (Int) -> Boolean = { true }): Int =
        (saved(id) { accept((it.position as ReadingPosition.Image).viewStart) }.position as ReadingPosition.Image).viewStart

    @Test
    fun aBookTurnsBackToTheSameViewAndReopensWhereItWasClosed() = runBlocking {
        val bytes = instrumentation.context.assets.open("zip/images.zip").use { it.readBytes() }
        val file = File(context.cacheDir, "reader-test.zip").apply { writeBytes(bytes) }
        val id = documentBookId(bytes.copyOf(minOf(bytes.size, BOOK_ID_PREFIX_BYTES)), bytes.size.toLong())
        val services = app.services.await()
        val intent = Intent(context, MainActivity::class.java).putExtra("book", Uri.fromFile(file).toString())

        val closedAt: Instant
        val closedOn: Int
        ActivityScenario.launch<MainActivity>(intent).use {
            services.saved(id)
            press(KeyEvent.KEYCODE_MOVE_HOME)
            assertEquals(0, services.viewStart(id) { it == 0 })

            repeat(3) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
            val ahead = services.viewStart(id) { it > 0 }
            repeat(3) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertEquals("three turns back return to the first view after reaching $ahead", 0, services.viewStart(id) { it < ahead })

            repeat(4) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
            closedOn = services.viewStart(id) { it > ahead }
            closedAt = Instant.now()
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            val reopened = services.saved(id) { it.updatedAt.isAfter(closedAt) }
            assertEquals("the reader opens on the view it was closed on", closedOn, (reopened.position as ReadingPosition.Image).viewStart)
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            val next = services.viewStart(id) { it != closedOn }
            assertTrue("one turn from the restored view $closedOn reaches $next", next > closedOn)
        }
    }
}
