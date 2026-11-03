package io.github.yuubiz.hirari

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.feature.library.CoverOrigin
import io.github.yuubiz.hirari.feature.library.CoverRef
import io.github.yuubiz.hirari.feature.library.CoverSpec
import io.github.yuubiz.hirari.feature.library.CoverState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * The covers of the lists on a device, through the real app (docs/spec.md section 4.4): a corpus book becomes a
 * thumbnail in the store and a decoded image in memory, the collection runs on the main thread as a list's does
 * (under the debug StrictMode, which kills the process on disk access there), and a book that is gone gives the
 * placeholder.
 */
class CoversDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as HirariApp
    private val dir = File(context.cacheDir, "covers-test").apply { deleteRecursively(); mkdirs() }
    private val unique = UUID.randomUUID().toString()

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun spec(name: String, file: File) = CoverSpec.Book(
        BookId("covers-test-$unique-$name"),
        Instant.ofEpochMilli(file.lastModified()),
        file.length(),
        coverEntry = null,
        ReadingDirection.RightToLeft,
        CoverOrigin.Document(UriString(Uri.fromFile(file).toString())),
    )

    private fun onMain(block: suspend () -> CoverState<*>): CoverState<*> = runBlocking {
        withTimeout(60_000) { CoroutineScope(Dispatchers.Main).async { block() }.await() }
    }

    @Test
    fun aCorpusBookBecomesAThumbnailThatIsDecodedOnceAndKeptInMemory() {
        val file = File(dir, "images.zip").apply { writeBytes(instrumentation.context.assets.open("zip/images.zip").use { it.readBytes() }) }
        val covers = runBlocking { app.library.await().covers }
        val ref = CoverRef(spec("images", file), covers.sizePx(io.github.yuubiz.hirari.core.model.ThumbSize.Medium))
        assertNull(covers.peek(ref))

        val state = onMain { covers.show(ref).first { it !is CoverState.Pending } }

        val image = (state as CoverState.Loaded<*>).image as androidx.compose.ui.graphics.ImageBitmap
        // A corpus page smaller than the size is not enlarged.
        assertTrue("${image.width}x${image.height}", maxOf(image.width, image.height) in 1..ref.sizePx)
        assertNotNull(covers.peek(ref))
        val again = onMain { covers.show(ref).first() }
        assertTrue(again is CoverState.Loaded<*>)
    }

    @Test
    fun aBookThatIsGoneGivesThePlaceholder() {
        val gone = File(dir, "gone.zip")
        val covers = runBlocking { app.library.await().covers }
        val ref = CoverRef(spec("gone", gone), covers.sizePx(io.github.yuubiz.hirari.core.model.ThumbSize.Small))

        val state = onMain { covers.show(ref).first { it !is CoverState.Pending } }

        assertTrue(state.toString(), state is CoverState.Unavailable || state is CoverState.Failed)
        assertNull(covers.peek(ref))
    }
}
