package io.github.yuubiz.hirari.core.image

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The corpus test of docs/spec.md section 6.2 for the book opener, against `pages.tsv`. */
class CorpusBookTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val decoding = ImageDecoding.forDevice(context)
    private val target = PixelSize(1080, 1920)

    private fun asset(path: String): ByteArray = instrumentation.context.assets.open(path).use { it.readBytes() }

    /** One `book` line of the pages manifest and its `page` lines, without the line kinds. */
    private class ExpectedBook(val path: String, val open: List<String>, val pages: MutableList<List<String>> = ArrayList())

    private fun manifest(): List<ExpectedBook> {
        val books = ArrayList<ExpectedBook>()
        for (line in String(asset("pages.tsv"), Charsets.UTF_8).lines().filter { it.isNotEmpty() }.map { it.split('\t') }) {
            when (line[0]) {
                "book" -> books += ExpectedBook(line[1], line.drop(2))
                "page" -> books.last().pages += line.drop(1)
            }
        }
        return books
    }

    private fun describe(result: PageResult<*>): List<String> = when (result) {
        is PageResult.Ready -> when (val image = result.image) {
            is PreparedImage -> listOf("ready", image.shape.width.toString(), image.shape.height.toString())
            is PageShape.Known -> listOf("ready", image.width.toString(), image.height.toString())
            else -> listOf("ready", "$image")
        }
        is PageResult.Failed -> listOf("fails", result.reason.name.lowercase())
        is PageResult.Unsupported -> listOf("unsupported") + when (val kind = result.kind) {
            UnsupportedKind.NotAnImage -> listOf("not-an-image")
            UnsupportedKind.Encrypted -> listOf("encrypted")
            is UnsupportedKind.Compression -> listOf("compression", kind.method)
            is UnsupportedKind.TooLarge -> listOf("too-large", kind.width.toString(), kind.height.toString(), kind.bytes.toString())
            is UnsupportedKind.ImageFeature -> listOf("image-feature", kind.detail)
        }
    }

    /** Why the bitmap of [image] is not the size [target] asks for, or null when it is. */
    private fun sizeProblem(image: PreparedImage): String? {
        val bitmap = (image as PreparedImage.Whole).bitmap
        val shape = image.shape
        val scale = minOf(1.0, target.width / shape.width.toDouble(), target.height / shape.height.toDouble())
        val width = Math.round(shape.width * scale).toInt().coerceAtLeast(1)
        val height = Math.round(shape.height * scale).toInt().coerceAtLeast(1)
        return if (bitmap.width == width && bitmap.height == height) null else "bitmap ${bitmap.width}x${bitmap.height}, expected ${width}x$height"
    }

    private suspend fun mismatches(expected: ExpectedBook): List<String> {
        val file = File(context.cacheDir, "corpus-book.zip").apply { writeBytes(asset(expected.path)) }
        val opened = Books.openDocument(context.contentResolver, Uri.fromFile(file), File(context.cacheDir, "copy"), decoding)
        val book = when (opened) {
            is BookResult.Failed -> {
                val actual = listOf("fails", opened.reason.name.lowercase())
                return if (actual == expected.open) emptyList() else listOf("open: expected ${expected.open}, got $actual")
            }
            is BookResult.Opened -> opened.book
        }
        val problems = ArrayList<String>()
        book.use {
            val open = listOf("opens", book.indexError?.name?.lowercase() ?: "-")
            if (open != expected.open) problems += "open: expected ${expected.open}, got $open"
            val names = book.pages.map { it.name }
            val expectedNames = expected.pages.map { it[0] }
            if (names != expectedNames) problems += "pages: expected $expectedNames, got $names"
            book.pages.zip(expected.pages).forEach { (page, wanted) ->
                val started = System.nanoTime()
                val result = withTimeout(PAGE_LIMIT_MILLIS * 2) { book.page(page.index, target) }
                val millis = (System.nanoTime() - started) / 1_000_000
                if (millis > PAGE_LIMIT_MILLIS) problems += "${page.name}: took $millis ms"
                val actual = describe(result)
                if (actual != wanted.drop(1)) problems += "${page.name}: expected ${wanted.drop(1)}, got $actual"
                if (result is PageResult.Ready) {
                    sizeProblem(result.image)?.let { problems += "${page.name}: $it" }
                    val shape = describe(book.shape(page.index))
                    if (shape != actual) problems += "${page.name}: shape gave $shape"
                }
            }
        }
        return problems
    }

    @Test
    fun everyCorpusBookOpensAndEveryPageGivesTheExpectedResultInTime() = runBlocking {
        val books = manifest()
        assertTrue(books.any { it.path == "zip/images.zip" })
        val problems = books.flatMap { book -> mismatches(book).map { "${book.path}: $it" } }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private companion object {
        const val PAGE_LIMIT_MILLIS = 10_000L
    }
}
