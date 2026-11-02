package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

/** One `book` line of the corpus `pages.tsv` and its `page` lines, without the line kinds. */
class ExpectedBook(val path: String, val open: List<String>, val pages: MutableList<List<String>> = ArrayList())

fun parsePagesManifest(text: String): List<ExpectedBook> {
    val books = ArrayList<ExpectedBook>()
    for (line in text.lines().filter { it.isNotEmpty() }.map { it.split('\t') }) {
        when (line[0]) {
            "book" -> books += ExpectedBook(line[1], line.drop(2))
            "page" -> books.last().pages += line.drop(1)
        }
    }
    return books
}

/** [result] in the tokens of `pages.tsv`. */
fun describe(result: PageResult<*>): List<String> = when (result) {
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

/** docs/spec.md section 6.2: every page gives its result within 10 s. */
const val PAGE_LIMIT_MILLIS = 10_000L

/**
 * Reads every page of [book] at [target] and its shape, and lists each way it differs from [expected]: the page
 * names, each result, the bitmap size, the shape, and the time limit.
 */
suspend fun pageMismatches(book: OpenBook, expected: ExpectedBook, target: PixelSize): List<String> {
    val problems = ArrayList<String>()
    val names = book.pages.map { it.name }
    val expectedNames = expected.pages.map { it[0] }
    if (names != expectedNames) problems += "pages: expected $expectedNames, got $names"
    book.pages.zip(expected.pages).forEach { (page, wanted) ->
        val started = System.nanoTime()
        val result = withTimeout(PAGE_LIMIT_MILLIS * 2) { book.page(page.index, target) }
        val millis = (System.nanoTime() - started) / 1_000_000
        if (millis > PAGE_LIMIT_MILLIS) problems += "${page.name}: took $millis ms"
        val actual = describe(result)
        val options = alternatives(wanted.drop(1))
        if (actual !in options) problems += "${page.name}: expected ${options.joinToString(" or ")}, got $actual"
        if (result is PageResult.Ready) {
            sizeProblem(result.image, target)?.let { problems += "${page.name}: $it" }
            val shape = describe(book.shape(page.index))
            if (shape != actual) problems += "${page.name}: shape gave $shape"
        }
    }
    return problems
}

/** The results [fields] allow, which are more than one when they are split by `|` fields. */
private fun alternatives(fields: List<String>): List<List<String>> =
    fields.fold(listOf(emptyList<String>())) { acc, field -> if (field == "|") acc + listOf(emptyList()) else acc.dropLast(1) + listOf(acc.last() + field) }

/** Why the bitmap of [image] is not the size that fits [target], or null when it is. */
private fun sizeProblem(image: PreparedImage, target: PixelSize): String? {
    val bitmap = (image as PreparedImage.Whole).bitmap
    val shape = image.shape
    val scale = minOf(1.0, target.width / shape.width.toDouble(), target.height / shape.height.toDouble())
    val width = (shape.width * scale).roundToInt().coerceAtLeast(1)
    val height = (shape.height * scale).roundToInt().coerceAtLeast(1)
    return if (bitmap.width == width && bitmap.height == height) null else "bitmap ${bitmap.width}x${bitmap.height}, expected ${width}x$height"
}
