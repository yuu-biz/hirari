package io.github.yuubiz.hirari.tools.corpus

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.min

/** One tar member. [name] is written byte for byte and must fit the 100 bytes of the ustar name field. */
class TarEntry(val name: ByteArray, val data: ByteArray, val isDirectory: Boolean = false)

private const val BLOCK = 512

/** A POSIX ustar archive (POSIX.1-2008, the `pax` utility's ustar interchange format). */
fun writeTar(entries: List<TarEntry>): ByteArray {
    val out = ByteArrayOutputStream()
    for (entry in entries) {
        require(entry.name.size <= 100)
        val header = ByteArray(BLOCK)
        entry.name.copyInto(header, 0)
        octal(header, 100, 8, if (entry.isDirectory) 0x1ed else 0x1a4) // mode 0755 or 0644
        octal(header, 108, 8, 0) // uid
        octal(header, 116, 8, 0) // gid
        octal(header, 124, 12, entry.data.size.toLong())
        octal(header, 136, 12, 0) // mtime
        header[156] = (if (entry.isDirectory) '5' else '0').code.toByte()
        "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        " ".repeat(8).toByteArray(Charsets.US_ASCII).copyInto(header, 148)
        val checksum = header.sumOf { it.toInt() and 0xff }
        "%06o\u0000 ".format(checksum).toByteArray(Charsets.US_ASCII).copyInto(header, 148)
        out.write(header)
        out.write(entry.data)
        out.write(ByteArray((BLOCK - entry.data.size % BLOCK) % BLOCK))
    }
    out.write(ByteArray(2 * BLOCK))
    return out.toByteArray()
}

/** [value] as zero-padded octal digits and a NUL in the [length] bytes at [offset]. */
private fun octal(header: ByteArray, offset: Int, length: Int, value: Long) {
    "%0${length - 1}o".format(value).toByteArray(Charsets.US_ASCII).copyInto(header, offset)
}

/** The outer compressions of a tar that docs/spec.md section 3.3 requires, and bzip2. */
enum class TarFilter(val extension: String) {
    Gzip("tgz"),
    Xz("txz"),
    Zstd("tzst"),
    BZip2("tbz2"),
}

fun compressTar(tar: ByteArray, filter: TarFilter): ByteArray = when (filter) {
    TarFilter.Gzip -> through(tar) { GZIPOutputStream(it) }
    TarFilter.Xz -> through(tar) { XZOutputStream(it, LZMA2Options()) }
    TarFilter.Zstd -> zstdRaw(tar)
    TarFilter.BZip2 -> through(tar) { BZip2CompressorOutputStream(it) }
}

private fun through(data: ByteArray, open: (OutputStream) -> OutputStream): ByteArray {
    val out = ByteArrayOutputStream()
    open(out).use { it.write(data) }
    return out.toByteArray()
}

private const val ZSTD_MAX_BLOCK = 128 * 1024

/**
 * A Zstandard frame (RFC 8878) of raw blocks, which store the data as is. A single-segment frame with a 4-byte
 * content size, no dictionary and no checksum, so that the generator needs no zstd encoder.
 */
private fun zstdRaw(data: ByteArray): ByteArray = bytes {
    u32(0xfd2fb528.toInt())
    u8(0xa0) // Frame_Content_Size_Flag 2, Single_Segment_Flag
    u32(data.size)
    var at = 0
    do {
        val size = min(ZSTD_MAX_BLOCK, data.size - at)
        val last = if (at + size == data.size) 1 else 0
        val blockHeader = last or (size shl 3) // Block_Type 0, raw
        u8(blockHeader and 0xff)
        u8((blockHeader ushr 8) and 0xff)
        u8((blockHeader ushr 16) and 0xff)
        write(data, at, size)
        at += size
    } while (at < data.size)
}
