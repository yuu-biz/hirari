package io.github.yuubiz.hirari.core.image.fuzz

import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A seed input and where it came from. */
internal class Seed(val name: String, val bytes: ByteArray)

/**
 * The seeds of each target, from the corpus the test APK carries as assets (`./gradlew :tools:corpus:generateCorpus`).
 * `zip/images.zip` is 11 MB and holds the corpus images, so it is opened and its entries are the seeds of the image
 * targets, and the small ones are packed again into the book seeds. A seed larger than [maxSeedBytes] is left out,
 * because the speed of a mutation fuzzer falls with the size of its input.
 */
internal class Seeds(assets: AssetManager, maxSeedBytes: Int) {
    private val archives = ArrayList<Seed>()
    private val pdfs = ArrayList<Seed>()
    private val images = ArrayList<Seed>()
    private val jxls = ArrayList<Seed>()

    init {
        val imageZip = assets.open("zip/images.zip").use { it.readBytes() }
        for ((name, bytes) in entriesOf(imageZip)) {
            if (bytes.isNotEmpty() && bytes.size <= maxSeedBytes && !name.endsWith(".txt")) images += Seed("images/$name", bytes)
        }
        for (dir in ARCHIVE_DIRS) {
            for (file in assets.list(dir).orEmpty().sorted()) {
                val path = "$dir/$file"
                if (path == "zip/images.zip") continue
                val bytes = assets.open(path).use { it.readBytes() }
                if (bytes.size <= maxSeedBytes) archives += Seed(path, bytes)
            }
        }
        for (file in assets.list("pdf").orEmpty().sorted()) {
            val bytes = assets.open("pdf/$file").use { it.readBytes() }
            if (bytes.size <= maxSeedBytes) pdfs += Seed("pdf/$file", bytes)
        }
        for (file in assets.list("jxl").orEmpty().sorted()) {
            val bytes = assets.open("jxl/$file").use { it.readBytes() }
            if (bytes.size <= maxSeedBytes) jxls += Seed("jxl/$file", bytes)
        }
        jxls += images.filter { it.name.endsWith(".jxl") }
        val small = images.filter { it.bytes.size <= SMALL_IMAGE_BYTES }
        archives += Seed("repacked/small-images-stored.zip", pack(small, Deflater.NO_COMPRESSION))
        archives += Seed("repacked/small-images-deflated.zip", pack(small, Deflater.DEFAULT_COMPRESSION))
    }

    fun of(target: Target): List<Seed> = when (target) {
        Target.Archive -> archives
        Target.Book -> archives + pdfs
        Target.Image -> images + jxls
        Target.Jxl -> jxls
        Target.SelfTest -> selfTest
    }

    private val selfTest = listOf(
        Seed("selftest/boom", ByteArray(3000) { (it % 251).toByte() } + "BOOM".toByteArray() + ByteArray(2000) { (it % 13).toByte() }),
        Seed("selftest/hang", ByteArray(1000) { (it % 7).toByte() } + "HANG".toByteArray() + ByteArray(500) { 1 }),
    )

    private fun entriesOf(zip: ByteArray): List<Pair<String, ByteArray>> {
        val result = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (!entry.isDirectory) result += entry.name to input.readBytes()
            }
        }
        return result
    }

    private fun pack(files: List<Seed>, level: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(level)
            for (file in files) {
                val entry = ZipEntry(file.name.removePrefix("images/"))
                if (level == Deflater.NO_COMPRESSION) {
                    entry.method = ZipEntry.STORED
                    entry.size = file.bytes.size.toLong()
                    entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(file.bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(file.bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        val ARCHIVE_DIRS = listOf("zip", "rar4", "rar5", "7z", "tar")
        const val SMALL_IMAGE_BYTES = 32 * 1024
    }
}
