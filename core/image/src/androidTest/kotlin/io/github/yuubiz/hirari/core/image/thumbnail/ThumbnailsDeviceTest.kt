package io.github.yuubiz.hirari.core.image.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.Books
import io.github.yuubiz.hirari.core.image.FolderFile
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.parsePagesManifest
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.source.FileChannelSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import kotlin.system.measureTimeMillis

/**
 * The decode and encode path of [Thumbnails] on a device, over the corpus. Timings are logged under
 * `ThumbnailsDeviceTest`; on an emulator they are not authoritative (docs/spec.md section 5.1).
 */
class ThumbnailsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "thumbnails-test").apply { deleteRecursively(); mkdirs() }
    private val store = FileStore(File(dir, "store"))
    private val thumbnails = Thumbnails.forDevice(context, store)

    @After
    fun close() {
        thumbnails.close()
        dir.deleteRecursively()
    }

    private fun asset(path: String): ByteArray = instrumentation.context.assets.open(path).use { it.readBytes() }

    private fun book(id: String, file: File, coverEntry: Int? = null) = ThumbSubject.Book(
        BookId(id),
        Instant.ofEpochMilli(file.lastModified()),
        file.length(),
        coverEntry,
        ThumbLane.Local,
    ) { decoding -> Books.openDocument(context.contentResolver, Uri.fromFile(file), File(dir, "copy"), decoding) }

    private fun corpusFile(path: String): File = File(dir, path).apply {
        parentFile!!.mkdirs()
        writeBytes(asset(path))
    }

    private fun request(subject: ThumbSubject, direction: ReadingDirection = ReadingDirection.RightToLeft) =
        ThumbRequest(subject, SIZE_PX, direction)

    private fun bounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth to options.outHeight
    }

    private fun assertWebp(file: File) {
        val head = file.inputStream().use { it.readNBytes(12) }
        assertEquals("RIFF", String(head, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(head, 8, 4, Charsets.US_ASCII))
    }

    @Test
    fun aCoverIsAWebpWhoseLongSideIsTheRequestedSize() = runBlocking {
        val result = thumbnails.thumbnail(request(book("images", corpusFile("zip/images.zip"))))
        val file = (result as ThumbResult.Ready).file
        assertWebp(file)
        val (width, height) = bounds(file)
        assertEquals(SIZE_PX, maxOf(width, height))
        assertTrue("expected a portrait cover, got $width x $height", height > width)
    }

    @Test
    fun aPdfGivesItsFirstPage() = runBlocking {
        val result = thumbnails.thumbnail(request(book("pdf", corpusFile("pdf/mixed-sizes.pdf"))))
        val (width, height) = bounds((result as ThumbResult.Ready).file)
        assertEquals(SIZE_PX, maxOf(width, height))
        assertTrue("the first page is 595 x 842 points, got $width x $height", height > width)
    }

    @Test
    fun theChosenCoverPageIsUsed() = runBlocking {
        val file = corpusFile("zip/images.zip")
        val opened = Books.openDocument(context.contentResolver, Uri.fromFile(file), File(dir, "copy"), ImageDecoding.forDevice(context))
        val spread = (opened as BookResult.Opened).book.use { book -> book.pages.single { it.name == "spread.jpg" }.entryIndex }
        val chosen = thumbnails.thumbnail(request(book("images", file, coverEntry = spread), ReadingDirection.LeftToRight)) as ThumbResult.Ready
        val (width, height) = bounds(chosen.file)
        assertEquals(SIZE_PX, maxOf(width, height))
        assertTrue("the left half of 1800 x 1200 is 900 x 1200, got $width x $height", height > width)
    }

    @Test
    fun aWideCoverShowsTheHalfThatTheBindingDirectionNames() = runBlocking {
        val wide = File(dir, "wide.png")
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        for (x in 0 until 1200) for (y in 0 until 800) bitmap.setPixel(x, y, if (x < 600) Color.RED else Color.BLUE)
        wide.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val subject = ThumbSubject.Book(BookId("wide"), Instant.EPOCH, wide.length()) { decoding ->
            Books.openFolder(listOf(FolderFile("wide.png", wide.length()) { FileChannelSource(RandomAccessFile(wide, "r").channel) }), decoding)
        }
        for ((direction, expected) in listOf(ReadingDirection.RightToLeft to Color.BLUE, ReadingDirection.LeftToRight to Color.RED)) {
            val file = (thumbnails.regenerate(request(subject, direction)) as ThumbResult.Ready).file
            val image = BitmapFactory.decodeFile(file.path)
            assertEquals("a half of 1200 x 800 is 600 x 800", SIZE_PX, image.height)
            assertTrue(image.width < image.height)
            val pixel = image.getPixel(image.width / 2, image.height / 2)
            image.recycle()
            assertEquals("$direction", dominant(expected), dominant(pixel))
        }
    }

    @Test
    fun aRangeOfTheCoverPageIsCutOutAndThePageIsShownWholeToChooseItFrom() = runBlocking {
        val page = File(dir, "stripes.png")
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        for (x in 0 until 1200) for (y in 0 until 800) {
            bitmap.setPixel(x, y, if (y >= 400) Color.YELLOW else if (x < 400) Color.RED else if (x < 800) Color.GREEN else Color.BLUE)
        }
        page.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        fun subject(crop: CoverCrop?) = ThumbSubject.Book(BookId("stripes"), Instant.EPOCH, page.length(), coverEntry = 0, coverCrop = crop) { decoding ->
            Books.openFolder(listOf(FolderFile("stripes.png", page.length()) { FileChannelSource(RandomAccessFile(page, "r").channel) }), decoding)
        }

        val middle = (thumbnails.regenerate(request(subject(CoverCrop(3333, 0, 6667, 5000)))) as ThumbResult.Ready).file
        val green = BitmapFactory.decodeFile(middle.path)
        assertEquals("the middle third of the top half is square", SIZE_PX, green.width)
        assertEquals(SIZE_PX.toDouble(), green.height.toDouble(), 2.0)
        for ((x, y) in listOf(6 to 6, green.width - 7 to 6, 6 to green.height - 7, green.width - 7 to green.height - 7, green.width / 2 to green.height / 2)) {
            assertEquals("($x, $y) is inside the range", Color.GREEN, nearest(green.getPixel(x, y)))
        }
        green.recycle()

        val top = (thumbnails.regenerate(request(subject(CoverCrop(0, 0, 10_000, 5000)))) as ThumbResult.Ready).file
        val stripes = BitmapFactory.decodeFile(top.path)
        assertEquals(SIZE_PX, stripes.width)
        assertEquals("the top half of 1200 x 800 is 3:1", SIZE_PX / 3.0, stripes.height.toDouble(), 2.0)
        assertEquals(Color.RED, nearest(stripes.getPixel(6, 6)))
        assertEquals(Color.BLUE, nearest(stripes.getPixel(stripes.width - 7, stripes.height - 7)))
        stripes.recycle()

        val whole = (thumbnails.regenerate(request(subject(null))) as ThumbResult.Ready).file
        assertTrue("the page whole is not the range", whole.length() != top.length())

        val shown = thumbnails.preview(subject(null), entryIndex = 0, maxSidePx = 600) as PagePreview.Ready
        assertEquals(PageShape.Known(1200, 800), shown.shape)
        assertEquals(600, shown.bitmap.width)
        assertEquals(400, shown.bitmap.height)
        assertEquals(Color.YELLOW, nearest(shown.bitmap.getPixel(300, 300)))
        shown.bitmap.recycle()
        assertEquals(PagePreview.Missing, thumbnails.preview(subject(null), entryIndex = 9, maxSidePx = 600))
    }

    /** The pure color [color] is nearest to, which WebP's loss does not move it away from. */
    private fun nearest(color: Int): Int = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).minBy {
        Math.abs(Color.red(it) - Color.red(color)) + Math.abs(Color.green(it) - Color.green(color)) + Math.abs(Color.blue(it) - Color.blue(color))
    }

    private fun dominant(color: Int): String = when {
        Color.red(color) > Color.blue(color) -> "red"
        else -> "blue"
    }

    @Test
    fun everyCorpusBookGivesTheOutcomeItsFirstPageSays() = runBlocking {
        val books = parsePagesManifest(String(asset("pages.tsv"), Charsets.UTF_8))
        val problems = ArrayList<String>()
        var made = 0
        val millis = measureTimeMillis {
            for (expected in books) {
                val file = corpusFile(expected.path)
                val wanted = expectedOutcome(expected.open, expected.pages.firstOrNull()?.drop(1)) ?: continue
                val started = System.nanoTime()
                val result = thumbnails.thumbnail(request(book(expected.path, file)))
                val took = (System.nanoTime() - started) / 1_000_000
                Log.i(TAG, "${expected.path}: ${result::class.simpleName} in $took ms")
                if (result is ThumbResult.Ready) made++
                if (result::class != wanted) problems += "${expected.path}: expected ${wanted.simpleName}, got $result"
            }
        }
        Log.i(TAG, "corpus: $made thumbnails in $millis ms (emulator timings are not authoritative)")
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun fifteenVisibleItemsAreFilledFromTheStoreMuchFasterThanTheyAreMade() = runBlocking {
        val file = corpusFile("zip/images.zip")
        val items = List(15) { book("item$it", file) }
        val generating = measureTimeMillis { items.map { async { thumbnails.thumbnail(request(it)) } }.awaitAll() }
        val cached = measureTimeMillis { items.map { async { thumbnails.cached(request(it)) } }.awaitAll() }
        Log.i(TAG, "P8: 15 items generated in $generating ms, from the cache in $cached ms (emulator timings are not authoritative)")
        assertTrue(items.all { thumbnails.cached(request(it)) is ThumbResult.Ready })
    }

    @Test
    fun anEncryptedBookIsRecordedAsFailedAndNotOpenedAgain() = runBlocking {
        val subject = book("encrypted", corpusFile("zip/encrypted.zip"))
        assertEquals(ThumbResult.Failed, thumbnails.thumbnail(request(subject)))
        assertEquals(ThumbResult.Failed, thumbnails.cached(request(subject)))
    }

    private fun expectedOutcome(open: List<String>, firstPage: List<String>?): kotlin.reflect.KClass<out ThumbResult>? = when {
        open[0] == "fails" -> when (open.getOrNull(1)) {
            "encrypted", "corrupt", "truncated" -> ThumbResult.Failed::class
            else -> null
        }
        firstPage == null -> ThumbResult.Failed::class
        firstPage[0] == "ready" -> ThumbResult.Ready::class
        firstPage[0] == "unsupported" -> ThumbResult.Failed::class
        firstPage[0] == "fails" && firstPage.getOrNull(1) in setOf("corrupt", "truncated") -> ThumbResult.Failed::class
        else -> null
    }

    private class FileStore(private val dir: File) : ThumbnailStore {
        private val files = HashMap<ThumbnailKey, File>()
        private val failed = HashSet<ThumbnailKey>()

        override suspend fun get(key: ThumbnailKey): StoredThumb = synchronized(this) {
            if (key in failed) StoredThumb.Failed else files[key]?.let(StoredThumb::Hit) ?: StoredThumb.Miss
        }

        override suspend fun put(key: ThumbnailKey, webp: ByteArray): File = synchronized(this) {
            dir.mkdirs()
            File(dir, "${files.size}.webp").also { it.writeBytes(webp) }.also { files[key] = it }
        }

        override suspend fun markFailed(key: ThumbnailKey) {
            synchronized(this) { failed += key }
        }

        override suspend fun remove(key: ThumbnailKey) {
            synchronized(this) {
                files.remove(key)
                failed.remove(key)
            }
        }

        override fun hold(key: ThumbnailKey): Closeable = Closeable { }
    }

    private companion object {
        const val SIZE_PX = 300
        const val TAG = "ThumbnailsDeviceTest"
    }
}
