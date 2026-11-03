package io.github.yuubiz.hirari.benchmark

import android.app.Instrumentation
import android.graphics.Bitmap
import android.util.Log
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The places the benchmarks read. Each is a folder at the top of the shared storage, where the system's folder
 * picker lists it, and each is staged once and kept. The books that docs/spec.md section 5.1 names are too big to
 * carry in the APK, so the reader book is the owner's [STANDARD_BOOK] when it is there.
 */
internal const val READER_PLACE = "hirari-perf"
internal const val LIST_PLACE = "hirari-perf-list"
internal const val THUMBNAIL_PLACE = "hirari-perf-thumbs"
internal const val TEXT_PLACE = "hirari-perf-text"

/** The standard corpus of section 5.1, which `adb push` puts into [READER_PLACE] (AGENTS.md). */
internal const val STANDARD_BOOK = "standard.zip"
internal const val STAND_IN_BOOK = "standin.zip"
internal const val TEXT_BOOK = "novel.txt"
internal const val LIST_FIRST = "b0001.cbz"

private const val LIST_ITEMS = 1000
private const val STAND_IN_PAGES = 200
private const val THUMBNAIL_BOOKS = 20
private const val TEXT_BYTES = 1_041_000
private const val TAG = "HirariBench"

internal class Staging(private val instrumentation: Instrumentation, private val device: UiDevice) {
    private val work = File(checkNotNull(instrumentation.targetContext.externalCacheDir), "staging").apply { mkdirs() }

    /**
     * The book P1 to P4 and P10 read: the standard corpus when it was pushed, else a 200-page stand-in made from
     * a page of the test corpus, which shows that a run works but is not what section 5.1 asks for.
     */
    fun readerBook(): String {
        val folder = folder(READER_PLACE)
        if (exists("$folder/$STANDARD_BOOK")) return STANDARD_BOOK.also { Log.i(TAG, "reader book: the standard corpus") }
        if (!exists("$folder/$STAND_IN_BOOK")) {
            val page = instrumentation.context.assets.open("zip/images.zip").use { input ->
                ZipInputStream(input).use { zip ->
                    generateSequence { zip.nextEntry }.first { it.name == "baseline.jpg" }.let { zip.readBytes() }
                }
            }
            putZip(folder, STAND_IN_BOOK, (1..STAND_IN_PAGES).map { "%03d.jpg".format(it) }, page)
        }
        Log.i(TAG, "reader book: the stand-in of $STAND_IN_PAGES small pages, not the standard corpus")
        return STAND_IN_BOOK
    }

    /** [LIST_ITEMS] empty `.cbz` files in one folder (P7). */
    fun bigFolder() {
        val folder = folder(LIST_PLACE)
        if (exists("$folder/$LIST_FIRST")) return
        val files = File(work, "list").apply {
            deleteRecursively()
            mkdirs()
        }
        for (i in 1..LIST_ITEMS) File(files, "b%04d.cbz".format(i)).createNewFile()
        device.executeShellCommand("cp -r ${files.absolutePath}/. $folder/")
    }

    /** Books with one page of the standard size each, for the covers of P8, and the name of the first. */
    fun thumbnailBooks(): String {
        val folder = folder(THUMBNAIL_PLACE)
        val names = (1..THUMBNAIL_BOOKS).map { "t%02d.zip".format(it) }
        if (!exists("$folder/${names.last()}")) {
            val page = standardPageJpeg()
            for (name in names) putZip(folder, name, listOf("001.jpg"), page)
        }
        return names.first()
    }

    /** A Japanese text of about 1 MB with ruby, for P9. */
    fun textBook(): String {
        val folder = folder(TEXT_PLACE)
        if (!exists("$folder/$TEXT_BOOK")) {
            val paragraph = "吾輩《わがはい》は猫である。名前はまだ無い。どこで生れたかとんと見当《けんとう》がつかぬ。" +
                "何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。\n"
            val text = StringBuilder("a\n猫の話\n\n")
            while (text.toString().toByteArray().size < TEXT_BYTES) text.append(paragraph)
            put(text.toString().toByteArray(), folder, TEXT_BOOK)
        }
        return TEXT_BOOK
    }

    private fun folder(place: String): String = "/sdcard/$place".also { device.executeShellCommand("mkdir -p $it") }

    private fun exists(path: String): Boolean = device.executeShellCommand("ls $path").let { it.isNotBlank() && "No such" !in it }

    private fun put(bytes: ByteArray, folder: String, name: String) {
        val copy = File(work, name).apply { writeBytes(bytes) }
        copy.copyTo(folder, name)
    }

    /** A ZIP of entries [names] that all hold [page], written to a file as it goes. */
    private fun putZip(folder: String, name: String, names: List<String>, page: ByteArray) {
        val copy = File(work, name)
        ZipOutputStream(copy.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            for (entry in names) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(page)
                zip.closeEntry()
            }
        }
        copy.copyTo(folder, name)
    }

    private fun File.copyTo(folder: String, name: String) {
        device.executeShellCommand("cp $absolutePath $folder/$name")
        delete()
    }

    /** A 1600 x 2400 page of noise, which comes to a few MB as JPEG, like a scanned page. */
    private fun standardPageJpeg(): ByteArray {
        val width = 1600
        val height = 2400
        val random = Random(1)
        val pixels = IntArray(width * height) {
            val level = 100 + random.nextInt(60)
            0xFF000000.toInt() or (level shl 16) or (level shl 8) or level
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray().also { bitmap.recycle() }
    }
}
