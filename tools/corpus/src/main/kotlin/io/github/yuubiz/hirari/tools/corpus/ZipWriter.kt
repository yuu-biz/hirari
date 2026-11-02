package io.github.yuubiz.hirari.tools.corpus

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** How an entry's data is stored. [code] is the compression method of PKWARE's APPNOTE.TXT section 4.4.5. */
sealed class Method(val code: Int) {
    data object Stored : Method(0)
    data object Deflated : Method(8)

    /** Writes the data unchanged under [code], for methods the generator does not encode or for broken streams. */
    class Raw(code: Int) : Method(code)
}

/**
 * One ZIP entry. [name] is written byte for byte, so it can be in any encoding. [declaredSize] and [crc] go
 * into the headers as given, so they can disagree with [data].
 */
class ZipEntry(
    val name: ByteArray,
    val data: ByteArray,
    val method: Method = Method.Stored,
    val utf8Flag: Boolean = false,
    val dataDescriptor: Boolean = false,
    val zip64: Boolean = false,
    val encrypted: Boolean = false,
    val declaredSize: Long = data.size.toLong(),
    val crc: Int = crc32(data),
)

/** Where an entry landed, as offsets into [ZipFile.bytes]. */
class EntryLayout(val headerStart: Int, val dataStart: Int, val dataEnd: Int)

class ZipFile(val bytes: ByteArray, val entries: List<EntryLayout>, val centralDirectoryStart: Int)

private const val FLAG_ENCRYPTED = 1
private const val FLAG_DATA_DESCRIPTOR = 1 shl 3
private const val FLAG_UTF8 = 1 shl 11
private const val VERSION_MADE_BY = 20 // 2.0 on MS-DOS
private const val VERSION_ZIP64 = 45
private const val U32_MAX = 0xffffffffL
private const val ZIP64_EXTRA = 0x0001
private const val ENCRYPTION_HEADER_SIZE = 12

// 1980-01-01 00:00, the MS-DOS epoch.
private const val DOS_TIME = 0
private const val DOS_DATE = (1 shl 5) or 1

fun crc32(data: ByteArray): Int = CRC32().apply { update(data) }.value.toInt()

fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

/**
 * A ZIP of [entries] in the given order, laid out as in APPNOTE.TXT section 4.3. [prefix] goes before the
 * first local header without shifting the recorded offsets, as when a stub is concatenated to an archive.
 */
fun writeZip(entries: List<ZipEntry>, comment: ByteArray = ByteArray(0), prefix: ByteArray = ByteArray(0)): ZipFile {
    require(comment.size <= 0xffff)
    val out = LittleEndianBytes()
    out.write(prefix)
    val records = entries.map { entry ->
        val payload = payload(entry)
        val headerStart = out.size()
        out.u32(0x04034b50)
        out.u16(versionNeeded(entry))
        out.u16(flags(entry))
        out.u16(entry.method.code)
        out.u16(DOS_TIME)
        out.u16(DOS_DATE)
        if (entry.dataDescriptor) {
            out.u32(0)
            out.u32(0)
            out.u32(0)
        } else {
            out.u32(entry.crc)
            out.sizes(entry, payload.size)
        }
        out.u16(entry.name.size)
        out.u16(if (entry.zip64) 4 + 16 else 0)
        out.write(entry.name)
        if (entry.zip64) {
            out.u16(ZIP64_EXTRA)
            out.u16(16)
            out.u64(entry.declaredSize)
            out.u64(payload.size.toLong())
        }
        val dataStart = out.size()
        out.write(payload)
        val dataEnd = out.size()
        if (entry.dataDescriptor) {
            out.u32(0x08074b50)
            out.u32(entry.crc)
            out.u32(payload.size)
            out.u32(entry.declaredSize.toInt())
        }
        Record(entry, payload.size, EntryLayout(headerStart, dataStart, dataEnd))
    }
    val centralStart = out.size()
    for (record in records) {
        val entry = record.entry
        val offset = record.layout.headerStart - prefix.size
        out.u32(0x02014b50)
        out.u16(VERSION_MADE_BY)
        out.u16(versionNeeded(entry))
        out.u16(flags(entry))
        out.u16(entry.method.code)
        out.u16(DOS_TIME)
        out.u16(DOS_DATE)
        out.u32(entry.crc)
        out.sizes(entry, record.payloadSize)
        out.u16(entry.name.size)
        out.u16(if (entry.zip64) 4 + 24 else 0)
        out.u16(0) // comment length
        out.u16(0) // disk number
        out.u16(0) // internal attributes
        out.u32(if (entry.name.lastOrNull() == '/'.code.toByte()) 0x10 else 0) // MS-DOS directory attribute
        out.u32(if (entry.zip64) U32_MAX.toInt() else offset)
        out.write(entry.name)
        if (entry.zip64) {
            out.u16(ZIP64_EXTRA)
            out.u16(24)
            out.u64(entry.declaredSize)
            out.u64(record.payloadSize.toLong())
            out.u64(offset.toLong())
        }
    }
    val centralSize = out.size() - centralStart
    val centralOffset = centralStart - prefix.size
    if (entries.any { it.zip64 }) {
        val recordOffset = out.size() - prefix.size
        out.u32(0x06064b50)
        out.u64(44) // size of the rest of this record
        out.u16(VERSION_ZIP64)
        out.u16(VERSION_ZIP64)
        out.u32(0) // this disk
        out.u32(0) // disk with the central directory
        out.u64(entries.size.toLong())
        out.u64(entries.size.toLong())
        out.u64(centralSize.toLong())
        out.u64(centralOffset.toLong())
        out.u32(0x07064b50)
        out.u32(0) // disk with the ZIP64 end record
        out.u64(recordOffset.toLong())
        out.u32(1) // total disks
        out.endRecord(0xffff, U32_MAX.toInt(), U32_MAX.toInt(), comment)
    } else {
        out.endRecord(entries.size, centralSize, centralOffset, comment)
    }
    return ZipFile(out.toByteArray(), records.map { it.layout }, centralStart)
}

private class Record(val entry: ZipEntry, val payloadSize: Int, val layout: EntryLayout)

private fun payload(entry: ZipEntry): ByteArray {
    val data = when (entry.method) {
        Method.Deflated -> deflate(entry.data)
        Method.Stored, is Method.Raw -> entry.data
    }
    // The bytes after the traditional encryption header are never decrypted, so they need not be real ciphertext.
    return if (entry.encrypted) ByteArray(ENCRYPTION_HEADER_SIZE) { (0xa5 xor it).toByte() } + data else data
}

private fun versionNeeded(entry: ZipEntry): Int = when {
    entry.zip64 -> VERSION_ZIP64
    entry.method == Method.Stored -> 10
    else -> 20
}

private fun flags(entry: ZipEntry): Int =
    (if (entry.encrypted) FLAG_ENCRYPTED else 0) or
        (if (entry.dataDescriptor) FLAG_DATA_DESCRIPTOR else 0) or
        (if (entry.utf8Flag) FLAG_UTF8 else 0)

private fun LittleEndianBytes.sizes(entry: ZipEntry, payloadSize: Int) {
    if (entry.zip64) {
        u32(U32_MAX.toInt())
        u32(U32_MAX.toInt())
    } else {
        u32(payloadSize)
        u32(entry.declaredSize.toInt())
    }
}

private fun LittleEndianBytes.endRecord(count: Int, centralSize: Int, centralOffset: Int, comment: ByteArray) {
    u32(0x06054b50)
    u16(0) // this disk
    u16(0) // disk with the central directory
    u16(count)
    u16(count)
    u32(centralSize)
    u32(centralOffset)
    u16(comment.size)
    write(comment)
}

private class LittleEndianBytes : ByteArrayOutputStream() {
    fun u16(value: Int) {
        write(value)
        write(value ushr 8)
    }

    fun u32(value: Int) {
        u16(value and 0xffff)
        u16(value ushr 16)
    }

    fun u64(value: Long) {
        u32(value.toInt())
        u32((value ushr 32).toInt())
    }
}
