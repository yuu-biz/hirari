package io.github.yuubiz.hirari.tools.corpus

import java.io.File
import java.nio.charset.Charset

/** A corpus file and what the archive layer and the book opener must report for it. */
class CorpusCase(val bytes: ByteArray, val expected: Expected)

private val CP932: Charset = Charset.forName("windows-31j")

private fun ascii(name: String): ByteArray = name.toByteArray(Charsets.US_ASCII)

private fun utf8(name: String): ByteArray = name.toByteArray(Charsets.UTF_8)

/** [size] bytes of lowercase letters from a fixed seed. Letters never form a ZIP signature, which starts with `P`. */
fun letters(size: Int, seed: Int): ByteArray {
    var state = seed.toLong() * 2654435761L + 1
    return ByteArray(size) {
        state = (state * 6364136223846793005L + 1442695040888963407L)
        ('a'.code + ((state ushr 33) % 26).toInt()).toByte()
    }
}

private fun file(name: String, size: Int, seed: Int, method: Method = Method.Deflated) =
    ZipEntry(ascii(name), letters(size, seed), method)

private fun directory(name: String) = ZipEntry(ascii(name), ByteArray(0))

/** An entry expectation that follows from the entry itself: a directory, or data that reads back unchanged. */
private fun readable(entry: ZipEntry, name: String = String(entry.name, Charsets.UTF_8)) =
    ExpectedEntry(name, if (name.endsWith('/')) ExpectedRead.Directory else ExpectedRead.Data(entry.data))

private fun readableZip(
    entries: List<ZipEntry>,
    comment: ByteArray = ByteArray(0),
    prefix: ByteArray = ByteArray(0),
): CorpusCase = CorpusCase(writeZip(entries, comment, prefix).bytes, Expected.Opens(entries.map { readable(it) }))

private fun namesZip(names: List<ByteArray>, utf8Flag: Boolean, expected: List<String>): CorpusCase {
    val entries = names.mapIndexed { i, name -> ZipEntry(name, letters(100, i), utf8Flag = utf8Flag) }
    return CorpusCase(writeZip(entries).bytes, Expected.Opens(entries.zip(expected) { e, name -> readable(e, name) }))
}

/**
 * [zip] cut to its first [length] bytes. An entry is listed when its local header is complete, and reads back
 * when its data is complete. Without the start of the central directory, listing ends in a truncation error.
 */
private fun truncated(zip: ZipFile, entries: List<ZipEntry>, length: Int): CorpusCase {
    val listed = zip.entries.indices.filter { zip.entries[it].dataStart <= length }
    val expected = listed.map { i ->
        if (zip.entries[i].dataEnd <= length) {
            readable(entries[i])
        } else {
            ExpectedEntry(String(entries[i].name, Charsets.UTF_8), ExpectedRead.Fails(Failure.Truncated))
        }
    }
    val complete = length >= zip.centralDirectoryStart + 4
    return CorpusCase(zip.bytes.copyOf(length), Expected.Opens(expected, if (complete) null else Failure.Truncated))
}

private fun truncatedCases(): Map<String, CorpusCase> {
    val entries = (1..3).map { file("%03d.jpg".format(it), 4000, it, Method.Stored) }
    val zip = writeZip(entries)
    val second = zip.entries[1]
    return mapOf(
        "zip/truncated-first-header.zip" to truncated(zip, entries, 10),
        "zip/truncated-second-header.zip" to truncated(zip, entries, second.headerStart + 10),
        "zip/truncated-second-data.zip" to truncated(zip, entries, second.dataStart + 1000),
        "zip/truncated-central-directory.zip" to truncated(zip, entries, zip.centralDirectoryStart + 20),
    )
}

private fun unsupported(entry: ZipEntry, kind: String) = ExpectedEntry(String(entry.name, Charsets.UTF_8), ExpectedRead.Unsupported(kind))

private fun fails(entry: ZipEntry, reason: Failure) = ExpectedEntry(String(entry.name, Charsets.UTF_8), ExpectedRead.Fails(reason))

private fun methodsZip(): CorpusCase {
    val stored = file("stored.jpg", 3000, 1, Method.Stored)
    val deflated = file("deflated.jpg", 20000, 2)
    val bzip2 = file("bzip2.jpg", 100, 3, Method.Raw(12))
    val lzma = file("lzma.jpg", 100, 4, Method.Raw(14))
    val ppmd = file("ppmd.jpg", 100, 5, Method.Raw(98))
    val entries = listOf(stored, deflated, bzip2, lzma, ppmd)
    return CorpusCase(
        writeZip(entries).bytes,
        Expected.Opens(
            listOf(
                readable(stored),
                readable(deflated),
                unsupported(bzip2, "compression:bzip"),
                unsupported(lzma, "compression:lzma"),
                unsupported(ppmd, "compression:ppmd-1"),
            ),
        ),
    )
}

private fun encryptedZip(): CorpusCase {
    val before = file("001.jpg", 3000, 1, Method.Stored)
    val encrypted = ZipEntry(ascii("002.jpg"), letters(3000, 2), encrypted = true)
    val after = file("003.jpg", 3000, 3)
    return CorpusCase(
        writeZip(listOf(before, encrypted, after)).bytes,
        Expected.Opens(listOf(readable(before), unsupported(encrypted, "encrypted"), readable(after))),
    )
}

private fun corruptZip(): CorpusCase {
    val first = file("001.jpg", 3000, 1, Method.Stored)
    val badCrc = ZipEntry(ascii("002.jpg"), letters(3000, 2), crc = 0x12345678)
    val badStream = ZipEntry(ascii("003.jpg"), ByteArray(64) { 0xff.toByte() }, Method.Raw(8), declaredSize = 3000)
    val last = file("004.jpg", 3000, 4)
    return CorpusCase(
        writeZip(listOf(first, badCrc, badStream, last)).bytes,
        Expected.Opens(listOf(readable(first), fails(badCrc, Failure.Corrupt), fails(badStream, Failure.Corrupt), readable(last))),
    )
}

private fun declaredHugeZip(): CorpusCase {
    val huge = ZipEntry(ascii("001.jpg"), letters(3000, 1), Method.Deflated, declaredSize = 300L shl 20)
    val normal = file("002.jpg", 3000, 2)
    return CorpusCase(
        writeZip(listOf(huge, normal)).bytes,
        Expected.Opens(
            listOf(
                ExpectedEntry("001.jpg", ExpectedRead.Unsupported("too-large"), ExpectedPage.TooLarge(0, 0, huge.declaredSize)),
                readable(normal),
            ),
        ),
    )
}

/** The images of [imageCases] in one archive, stored as is, with a text file that is not a page. */
private fun imagesZip(): CorpusCase {
    val images = imageCases()
    val notes = file("notes.txt", 200, 1)
    val entries = images.map { ZipEntry(ascii(it.name), it.bytes, Method.Stored) } + notes
    val expected = images.map { ExpectedEntry(it.name, ExpectedRead.Data(it.bytes), it.page) } + readable(notes)
    return CorpusCase(writeZip(entries).bytes, Expected.Opens(expected))
}

private fun nameCases(): Map<String, CorpusCase> {
    val japanese = listOf("表紙.jpg", "第1話/ソ.jpg")
    val nfd = "が.jpg"
    val invalid = ascii("bad") + byteArrayOf(0x81.toByte()) + ascii(".jpg")
    return mapOf(
        "zip/names-utf8-flag.zip" to namesZip(japanese.map(::utf8), utf8Flag = true, japanese),
        "zip/names-utf8.zip" to namesZip(japanese.map(::utf8), utf8Flag = false, japanese),
        // 表 and ソ end in 0x5C, the byte of `\`, which a careless reader takes for a path separator.
        "zip/names-cp932.zip" to namesZip(japanese.map { it.toByteArray(CP932) }, utf8Flag = false, japanese),
        // libarchive normalizes flagged names to NFC and passes the others through byte for byte.
        "zip/names-nfd-flag.zip" to namesZip(listOf(utf8(nfd)), utf8Flag = true, listOf("が.jpg")),
        "zip/names-nfd.zip" to namesZip(listOf(utf8(nfd)), utf8Flag = false, listOf(nfd)),
        "zip/names-invalid.zip" to namesZip(listOf(invalid), utf8Flag = false, listOf("bad�.jpg")),
        // libarchive returns no name for a flagged name that is not UTF-8.
        "zip/names-invalid-flag.zip" to namesZip(listOf(invalid), utf8Flag = true, listOf("�")),
    )
}

/** Every corpus case by its path under the corpus directory (docs/spec.md section 6.2). */
fun corpusCases(): Map<String, CorpusCase> = buildMap {
    put(
        "zip/names.zip",
        readableZip(
            listOf(
                ZipEntry(ascii("001.txt"), ascii("1"), Method.Stored),
                ZipEntry(ascii("chapter/002.txt"), ascii("2"), Method.Stored),
                ZipEntry(utf8("日本語.txt"), ascii("3"), Method.Stored, utf8Flag = true),
            ),
        ),
    )
    put(
        "zip/deflate.zip",
        readableZip(
            listOf(
                directory("ch1/"),
                file("ch1/001.jpg", 20000, 1),
                file("ch1/002.jpg", 30000, 2),
                file("ch1/003.jpg", 5000, 3, Method.Stored),
            ),
        ),
    )
    put(
        "zip/data-descriptor.zip",
        readableZip(
            listOf(
                ZipEntry(ascii("001.jpg"), letters(3000, 1), Method.Stored, dataDescriptor = true),
                ZipEntry(ascii("002.jpg"), letters(20000, 2), Method.Deflated, dataDescriptor = true),
                ZipEntry(ascii("003.jpg"), letters(4000, 3), Method.Stored, dataDescriptor = true),
            ),
        ),
    )
    put(
        "zip/zip64.zip",
        readableZip(
            listOf(
                ZipEntry(ascii("001.jpg"), letters(5000, 1), Method.Stored, zip64 = true),
                ZipEntry(ascii("002.jpg"), letters(20000, 2), Method.Deflated, zip64 = true),
            ),
        ),
    )
    put(
        "zip/prefixed.zip",
        readableZip(listOf(file("001.jpg", 10000, 1), file("002.jpg", 3000, 2, Method.Stored)), prefix = letters(4096, 9)),
    )
    put(
        "zip/long-comment.zip",
        readableZip(listOf(file("001.jpg", 10000, 1), file("002.jpg", 3000, 2, Method.Stored)), comment = letters(0xffff, 9)),
    )
    put("zip/empty.zip", readableZip(emptyList()))
    put("zip/no-images.zip", readableZip(listOf(file("readme.txt", 500, 1), file("info.nfo", 300, 2), directory("notes/"))))
    put("zip/methods.zip", methodsZip())
    put("zip/encrypted.zip", encryptedZip())
    put("zip/corrupt.zip", corruptZip())
    put("zip/declared-huge.zip", declaredHugeZip())
    put("zip/not-a-zip.zip", CorpusCase(ascii("This is not a ZIP archive.\n"), Expected.OpenFails(Failure.Corrupt)))
    putAll(nameCases())
    putAll(truncatedCases())
    put("zip/images.zip", imagesZip())
    putAll(archiveCases())
}

const val MANIFEST = "expected.tsv"
const val PAGES_MANIFEST = "pages.tsv"

/** Every corpus file by its path, with the archive expectations in [MANIFEST] and the page ones in [PAGES_MANIFEST]. */
fun corpus(): Map<String, ByteArray> {
    val cases = corpusCases()
    val pdfs = pdfCases()
    return cases.mapValues { it.value.bytes } + pdfs.mapValues { it.value.bytes } +
        (MANIFEST to manifest(cases).toByteArray(Charsets.UTF_8)) +
        (PAGES_MANIFEST to (pagesManifest(cases) + pdfPagesManifest(pdfs)).toByteArray(Charsets.UTF_8))
}

/** Writes the corpus into the directory given as the only argument. */
fun main(args: Array<String>) {
    val dir = File(args.single())
    corpus().forEach { (path, bytes) ->
        dir.resolve(path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
}
