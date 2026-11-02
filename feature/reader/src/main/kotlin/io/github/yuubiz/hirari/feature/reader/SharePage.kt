package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.PageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

/** The folder under the cache directory that holds the files of ページを共有 (docs/spec.md section 4.8). */
const val SHARE_DIR = "share"

/** What ページを共有 does on [view]: a pair asks which page, any other view shares its one page. */
fun shareChoice(view: PageView): CommandEffect = when (view) {
    is PageView.Two -> CommandEffect.Show(ReaderSheet.SharePick(view))
    is PageView.One -> CommandEffect.Share(view.page)
    is PageView.Half -> CommandEffect.Share(view.page)
}

/** The MIME type a page of [type] is shared as. A PDF page is drawn and shared as PNG; an unknown type as any image. */
fun shareMimeType(type: ImageType?): String = when (type) {
    ImageType.Jpeg -> "image/jpeg"
    ImageType.Png, ImageType.PdfPage -> "image/png"
    ImageType.WebP -> "image/webp"
    ImageType.Gif -> "image/gif"
    ImageType.Bmp -> "image/bmp"
    ImageType.Heif -> "image/heif"
    ImageType.Avif -> "image/avif"
    ImageType.JpegXl -> "image/jxl"
    null -> "image/*"
}

private fun ImageType.extensions(): List<String> = when (this) {
    ImageType.Jpeg -> listOf("jpg", "jpeg", "jpe", "jfif")
    ImageType.Png, ImageType.PdfPage -> listOf("png")
    ImageType.WebP -> listOf("webp")
    ImageType.Gif -> listOf("gif")
    ImageType.Bmp -> listOf("bmp")
    ImageType.Heif -> listOf("heic", "heif", "hif")
    ImageType.Avif -> listOf("avif")
    ImageType.JpegXl -> listOf("jxl")
}

private val UNSAFE = Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]")
private const val MAX_NAME = 120

/**
 * The file name the receiving app sees for page [page] (from 0) of entry [entryName]: the last part of the entry's
 * path without the characters file systems refuse, with the extension of [type] added when the name has none of
 * its own. A name left empty becomes `page-12`.
 */
fun shareFileName(entryName: String, type: ImageType?, page: Int): String {
    val base = entryName.substringAfterLast('/').substringAfterLast('\\').replace(UNSAFE, "_").trim().trimStart('.')
    val named = base.ifEmpty { "page-${page + 1}" }
    val extensions = type?.extensions().orEmpty()
    val own = named.substringAfterLast('.', "").lowercase()
    val full = if (extensions.isEmpty() || own in extensions) named else "$named.${extensions.first()}"
    if (full.length <= MAX_NAME) return full
    val extension = full.substringAfterLast('.', "")
    return full.take(MAX_NAME - extension.length - 1) + "." + extension
}

/**
 * The files of ページを共有 in [dir]. Each share gets a folder of its own, so a page of the same name shared later
 * never changes what an app given an earlier one still reads. A write and a clear never overlap, so a reader
 * closing while another shares does not remove the file half written. Both run on `Dispatchers.IO`.
 */
object SharedPageFiles {
    private val lock = Mutex()

    /** Writes [name] in a new folder in [dir] with [write] and returns it, or `null` when the file could not be written. */
    suspend fun write(dir: File, name: String, write: (OutputStream) -> Unit): File? = lock.withLock {
        withContext(Dispatchers.IO) {
            var folder: File? = null
            try {
                dir.mkdirs()
                val own = Files.createTempDirectory(dir.toPath(), "").toFile()
                folder = own
                File(own, name).also { file -> file.outputStream().use(write) }
            } catch (_: Exception) {
                folder?.deleteRecursively()
                null
            }
        }
    }

    /** Removes [dir] and everything in it. */
    suspend fun clear(dir: File) {
        lock.withLock { withContext(Dispatchers.IO) { dir.deleteRecursively() } }
    }
}
