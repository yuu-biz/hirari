package io.github.yuubiz.hirari.core.image

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
        return book.use {
            val open = listOf("opens", book.indexError?.name?.lowercase() ?: "-")
            val problems = if (open == expected.open) emptyList() else listOf("open: expected ${expected.open}, got $open")
            problems + pageMismatches(book, expected, target)
        }
    }

    @Test
    fun everyCorpusBookOpensAndEveryPageGivesTheExpectedResultInTime() = runBlocking {
        val books = parsePagesManifest(String(asset("pages.tsv"), Charsets.UTF_8))
        assertTrue(books.any { it.path == "zip/images.zip" })
        val problems = books.flatMap { book -> mismatches(book).map { "${book.path}: $it" } }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
