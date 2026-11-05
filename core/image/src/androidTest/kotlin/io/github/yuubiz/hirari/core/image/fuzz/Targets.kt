package io.github.yuubiz.hirari.core.image.fuzz

import io.github.yuubiz.hirari.core.archive.ArchiveSession
import io.github.yuubiz.hirari.core.archive.OpenResult
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.Books
import io.github.yuubiz.hirari.core.image.FolderFile
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.JxlDecodeResult
import io.github.yuubiz.hirari.core.image.JxlDecoder
import io.github.yuubiz.hirari.core.image.JxlHeaderResult
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.PreparedImage
import io.github.yuubiz.hirari.core.image.Tiling
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.util.SplittableRandom

/** What the fuzzer feeds to the code under test. */
internal enum class Target(val id: String, val inDefaultSet: Boolean = true) {
    /** `ArchiveSession` over the bytes: the JNI, libarchive, the name charset detection and the solid extraction. */
    Archive("archive"),

    /** `Books.openBook`: a PDF or an archive, then the first pages decoded. */
    Book("book"),

    /** `OpenBook.page` of a one-file folder: every image decoder, the tile planner and libjxl behind them. */
    Image("image"),

    /** `JxlDecoder` directly, at the sample sizes and memory limits the app does not choose in normal use. */
    Jxl("jxl"),

    /**
     * Writes to address 0 when the input holds `BOOM` and hangs when it holds `HANG`, so that the crash
     * and timeout paths of `tools/fuzz` can be tried end to end. It is not in the default set.
     */
    SelfTest("selftest", inDefaultSet = false),
    ;

    companion object {
        val defaults: String get() = entries.filter { it.inDefaultSet }.joinToString(",") { it.id }

        fun parse(ids: String): List<Target> = ids.split(',').filter { it.isNotBlank() }.map { id ->
            entries.firstOrNull { it.id == id.trim() } ?: throw IllegalArgumentException("unknown fuzz target '$id'")
        }
    }
}

/** A [ByteSource] over a byte array. */
internal class MemorySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long = bytes.size.toLong()

    override fun read(offset: Long, dst: ByteBuffer): Int {
        if (offset >= size) return -1
        val count = minOf(dst.remaining().toLong(), size - offset).toInt()
        dst.put(bytes, offset.toInt(), count)
        return count
    }

    override fun close() = Unit
}

/** Runs one input through a [Target]. Whatever a run throws is the caller's finding. */
internal class Runner(private val decoding: ImageDecoding, private val scratch: File) {
    fun run(target: Target, bytes: ByteArray, random: SplittableRandom) {
        when (target) {
            Target.Archive -> archive(bytes)
            Target.Book -> book(bytes, random)
            Target.Image -> image(bytes, random)
            Target.Jxl -> jxl(bytes, random)
            Target.SelfTest -> selfTest(bytes)
        }
    }

    private fun selfTest(bytes: ByteArray) {
        val text = String(bytes, Charsets.ISO_8859_1)
        if ("BOOM" in text) {
            val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
            unsafe.putByte(0L, 1)
        }
        if ("HANG" in text) Thread.sleep(Long.MAX_VALUE)
    }

    private fun archive(bytes: ByteArray) = runBlocking {
        val extractDir = File(scratch, "extract").apply { deleteRecursively() }
        try {
            val session = (ArchiveSession.open(MemorySource(bytes), extractDir) as? OpenResult.Opened)?.session ?: return@runBlocking
            session.use {
                it.nameCharset()
                for (i in 0 until minOf(it.entries.size, MAX_ENTRIES)) it.read(i, ENTRY_LIMIT)
                if (it.entries.size > 1) it.read(0, ENTRY_LIMIT)
            }
        } finally {
            extractDir.deleteRecursively()
        }
    }

    private fun book(bytes: ByteArray, random: SplittableRandom) = runBlocking {
        val book = (Books.openBook(MemorySource(bytes), decoding) as? BookResult.Opened)?.book ?: return@runBlocking
        book.use { readPages(it, random) }
    }

    private suspend fun readPages(book: OpenBook, random: SplittableRandom) {
        for (i in 0 until minOf(book.pages.size, MAX_PAGES)) {
            book.shape(i)
            release(book.page(i, targetSize(random)))
        }
    }

    private fun image(bytes: ByteArray, random: SplittableRandom) = runBlocking {
        val file = FolderFile("page.jpg", bytes.size.toLong()) { MemorySource(bytes) }
        val book = (Books.openFolder(listOf(file), decoding) as BookResult.Opened).book
        book.use {
            if (it.pages.isEmpty()) return@use
            it.shape(0)
            val size = targetSize(random)
            release(it.page(0, size))
            release(it.page(0, size, tiling = Tiling.AsPlanned))
        }
    }

    private fun jxl(bytes: ByteArray, random: SplittableRandom) {
        val header = (JxlDecoder.header(bytes) as? JxlHeaderResult.Read)?.header ?: return
        if (header.width !in 1..MAX_JXL_SIDE || header.height !in 1..MAX_JXL_SIDE || header.width.toLong() * header.height > MAX_JXL_PIXELS) return
        val sample = 1 shl random.nextInt(0, 6)
        val limit = 1L shl random.nextInt(20, 28)
        val result = JxlDecoder.decode(bytes, header, sample, limit) { true }
        if (result is JxlDecodeResult.Decoded) result.bitmap.recycle()
    }

    private fun release(result: PageResult<PreparedImage>) {
        val ready = (result as? PageResult.Ready)?.image ?: return
        when (ready) {
            is PreparedImage.Whole -> ready.bitmap.recycle()
            is PreparedImage.Tiled -> ready.source.close()
            is PreparedImage.Untiled -> Unit
        }
    }

    private fun targetSize(random: SplittableRandom): PixelSize = TARGETS[random.nextInt(TARGETS.size)]

    private companion object {
        const val MAX_ENTRIES = 32
        const val ENTRY_LIMIT = 8 shl 20
        const val MAX_PAGES = 6
        const val MAX_JXL_SIDE = 1 shl 20
        const val MAX_JXL_PIXELS = 1L shl 24
        val TARGETS = listOf(PixelSize(1080, 1920), PixelSize(720, 1280), PixelSize(2048, 2048))
    }
}
