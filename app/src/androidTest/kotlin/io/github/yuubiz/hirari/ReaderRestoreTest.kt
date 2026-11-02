package io.github.yuubiz.hirari

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.model.BOOK_ID_PREFIX_BYTES
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.documentBookId
import io.github.yuubiz.hirari.feature.reader.ReaderServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /** The system's first-time notice about hidden system bars would take the keys meant for the reader. */
    @Before
    fun skipFullScreenNotice() {
        val output = instrumentation.uiAutomation.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

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

    /** Presses [code] until the saved view start moves off [from]. A press before the reader has laid out the book does nothing. */
    private suspend fun ReaderServices.pressUntilMoved(id: BookId, code: Int, from: Int): Int = withTimeout(15_000) {
        while (true) {
            press(code)
            progress.flush().join()
            val now = (storage.progress.get(id)?.position as? ReadingPosition.Image)?.viewStart
            if (now != null && now != from) return@withTimeout now
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    @Test
    fun aBookTurnsBackToTheSameViewAndReopensWhereItWasClosed() = runBlocking {
        val bytes = instrumentation.context.assets.open("zip/images.zip").use { it.readBytes() }
        val file = File(context.cacheDir, "reader-test.zip").apply { writeBytes(bytes) }
        val id = documentBookId(bytes.copyOf(minOf(bytes.size, BOOK_ID_PREFIX_BYTES)), bytes.size.toLong())
        val services = app.services.await()
        services.storage.progress.save(listOf(BookProgress(id, ReadState.Unread, null, Instant.now())))
        val intent = Intent(context, MainActivity::class.java).putExtra("book", Uri.fromFile(file).toString())

        val closedOn: Int
        ActivityScenario.launch<MainActivity>(intent).use {
            services.saved(id) { it.state == ReadState.Reading }
            assertEquals("a book opened for the first time starts at its first view", 0, services.viewStart(id))

            repeat(3) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
            val ahead = services.viewStart(id) { it > 0 }
            repeat(3) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertEquals("three turns back return to the first view after reaching $ahead", 0, services.viewStart(id) { it < ahead })

            repeat(4) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
            closedOn = services.viewStart(id) { it > ahead }
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            assertEquals("closing stored the last view", closedOn, services.viewStart(id))
            val next = services.pressUntilMoved(id, KeyEvent.KEYCODE_DPAD_LEFT, from = closedOn)
            assertTrue("one turn from the restored view $closedOn reaches $next", next > closedOn)
        }
    }
}
