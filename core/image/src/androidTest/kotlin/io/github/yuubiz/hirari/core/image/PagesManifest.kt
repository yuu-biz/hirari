package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import io.github.yuubiz.hirari.core.model.ImageType
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
            sizeProblem(result.image, target, enlarges = page.sniffed == ImageType.PdfPage)?.let { problems += "${page.name}: $it" }
            val shape = describe(book.shape(page.index))
            if (shape != actual) problems += "${page.name}: shape gave $shape"
            problems += tileProblems(book, page.index, target, result.image).map { "${page.name}: $it" }
            problems += zoomTileProblems(book, page.index, target, result.image, page.sniffed).map { "${page.name}: $it" }
        }
        when (val raw = book.raw(page.index)) {
            is PageResult.Ready -> {
                val fromRaw = describe(book.page(page.index, target, raw.image))
                if (fromRaw != actual) problems += "${page.name}: from its raw bytes gave $fromRaw"
            }
            else -> if (describe(raw) != actual) problems += "${page.name}: raw gave ${describe(raw)}"
        }
    }
    return problems
}

/**
 * How the page at [index] read with tiles allowed differs from [whole]: only an image narrower than 1:3 comes as
 * tiles, of the same shape, and the visible tiles at the top and the bottom of a fit-width view each have their
 * planned size and about the colors of the same part of [whole], and stay drawable after the source closes.
 */
private suspend fun tileProblems(book: OpenBook, index: Int, target: PixelSize, whole: PreparedImage): List<String> {
    val shape = whole.shape
    val result = book.page(index, target, tiling = Tiling.AsPlanned)
    val image = (result as? PageResult.Ready)?.image ?: return listOf("with tiles gave ${describe(result)}")
    if (image !is PreparedImage.Tiled) {
        return when {
            image.shape != shape -> listOf("with tiles gave the shape ${image.shape}")
            isElongated(shape.width, shape.height) -> listOf("not tiled")
            else -> emptyList()
        }
    }
    val tiles = ArrayList<Bitmap>()
    val problems = image.source.use { source ->
        when {
            image.shape != shape -> listOf("tiled with the shape ${image.shape}")
            !isElongated(shape.width, shape.height) -> listOf("tiled")
            else -> {
                val size = source.size
                val scale = target.width / size.width.toDouble()
                val viewHeight = minOf(size.height, (target.height / scale).toInt())
                listOf(0, size.height - viewHeight).flatMap { top ->
                    source.plan(PixelRect(0, top, size.width, top + viewHeight), scale).visible.mapNotNull { key ->
                        tileProblem(source, key, (whole as PreparedImage.Whole).bitmap, tiles)?.let { "tile $key: $it" }
                    }
                }
            }
        }
    }
    return problems + if (tiles.any { it.isRecycled }) listOf("closing the source recycled a tile") else emptyList()
}

/**
 * How the page at [index] read with tiles always, as a zoom reads it (docs/spec.md section 4.8), differs from
 * [whole]: every page but a JPEG XL one comes as tiles of the same shape, a PDF page on its finer grid, and the
 * visible tiles at the top of a fit-width view have their planned size and about the colors of the same part.
 */
private suspend fun zoomTileProblems(book: OpenBook, index: Int, target: PixelSize, whole: PreparedImage, type: ImageType?): List<String> {
    val result = book.page(index, target, tiling = Tiling.Always)
    val image = (result as? PageResult.Ready)?.image ?: return listOf("with tiles always gave ${describe(result)}")
    if (image !is PreparedImage.Tiled) {
        (image as PreparedImage.Whole).bitmap.recycle()
        return if (type == ImageType.JpegXl) emptyList() else listOf("not tiled always")
    }
    val tiles = ArrayList<Bitmap>()
    return image.source.use { source ->
        if (image.shape != whole.shape) return@use listOf("tiled always with the shape ${image.shape}")
        val size = source.size
        val scale = target.width / size.width.toDouble()
        val viewHeight = minOf(size.height, (target.height / scale).toInt())
        source.plan(PixelRect(0, 0, size.width, viewHeight), scale).visible.mapNotNull { key ->
            tileProblem(source, key, (whole as PreparedImage.Whole).bitmap, tiles)?.let { "tile always $key: $it" }
        }
    }
}

private suspend fun tileProblem(source: TileSource, key: TileKey, whole: Bitmap, tiles: MutableList<Bitmap>): String? {
    val tile = when (val result = source.tile(key)) {
        is PageResult.Ready -> result.image.also { tiles += it }
        else -> return describe(result).joinToString(" ")
    }
    val rect = tileRect(key, source.size)
    val width = (rect.width + key.sample - 1) / key.sample
    val height = (rect.height + key.sample - 1) / key.sample
    if (tile.width != width || tile.height != height) return "${tile.width}x${tile.height}, expected ${width}x$height"
    val across = whole.width / source.size.width.toDouble()
    val down = whole.height / source.size.height.toDouble()
    val part = PixelRect(
        (rect.left * across).toInt(),
        (rect.top * down).toInt(),
        maxOf((rect.right * across).toInt(), (rect.left * across).toInt() + 1),
        maxOf((rect.bottom * down).toInt(), (rect.top * down).toInt() + 1),
    )
    val expected = meanColor(whole, part)
    val actual = meanColor(tile, PixelRect(0, 0, tile.width, tile.height))
    val off = expected.indices.maxOf { kotlin.math.abs(expected[it] - actual[it]) }
    return if (off <= COLOR_TOLERANCE) null else "mean color ${actual.toList()}, the whole image has ${expected.toList()}"
}

/** The tiles and the scaled-down whole image filter differently, so their means agree only roughly. */
private const val COLOR_TOLERANCE = 40

private fun meanColor(bitmap: Bitmap, rect: PixelRect): IntArray {
    val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val pixels = IntArray(rect.width * rect.height)
    software.getPixels(pixels, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
    val sums = LongArray(3)
    for (pixel in pixels) {
        sums[0] += (pixel shr 16) and 0xff
        sums[1] += (pixel shr 8) and 0xff
        sums[2] += pixel and 0xff
    }
    return IntArray(3) { (sums[it] / pixels.size).toInt() }
}

/** The results [fields] allow, which are more than one when they are split by `|` fields. */
private fun alternatives(fields: List<String>): List<List<String>> =
    fields.fold(listOf(emptyList<String>())) { acc, field -> if (field == "|") acc + listOf(emptyList()) else acc.dropLast(1) + listOf(acc.last() + field) }

/** Why the bitmap of [image] is not the size that fits [target], or null when it is. Only PDF pages [enlarges]. */
private fun sizeProblem(image: PreparedImage, target: PixelSize, enlarges: Boolean): String? {
    val bitmap = (image as PreparedImage.Whole).bitmap
    val shape = image.shape
    val fit = minOf(target.width / shape.width.toDouble(), target.height / shape.height.toDouble())
    val scale = if (enlarges) fit else minOf(1.0, fit)
    val width = (shape.width * scale).roundToInt().coerceAtLeast(1)
    val height = (shape.height * scale).roundToInt().coerceAtLeast(1)
    return if (bitmap.width == width && bitmap.height == height) null else "bitmap ${bitmap.width}x${bitmap.height}, expected ${width}x$height"
}
