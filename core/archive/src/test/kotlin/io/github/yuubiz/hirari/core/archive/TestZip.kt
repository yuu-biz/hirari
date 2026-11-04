package io.github.yuubiz.hirari.core.archive

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * One entry of a ZIP laid out by [testZip] (APPNOTE.TXT section 4.3). [stored] is what goes into the file;
 * [method] says how, and [size] and [crc] go into the headers as given.
 */
internal class TestEntry(
    val name: ByteArray,
    val data: ByteArray = ByteArray(0),
    val method: Int = 0,
    val flags: Int = 0,
    val system: Int = 0,
    val external: Long = 0,
    val centralExtra: ByteArray = ByteArray(0),
    val localExtra: ByteArray = ByteArray(0),
    val stored: ByteArray = if (method == 8) deflated(data) else data,
    val size: Long = data.size.toLong(),
    val crc: Int = crcOf(data),
) {
    constructor(name: String, data: ByteArray = ByteArray(0), method: Int = 0) : this(name.toByteArray(), data, method)
}

internal fun crcOf(bytes: ByteArray): Int = CRC32().apply { update(bytes) }.value.toInt()

internal fun deflated(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    deflater.setInput(data)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
    deflater.end()
    return out.toByteArray()
}

/**
 * The bytes of a ZIP of [entries], with the local headers in their order and the central directory in
 * [centralOrder]. [offsetOf] can point a record at another entry's local header.
 */
internal fun testZip(
    entries: List<TestEntry>,
    centralOrder: List<Int> = entries.indices.toList(),
    offsetOf: (Int) -> Int = { it },
    comment: ByteArray = ByteArray(0),
): ByteArray {
    val out = Le()
    val offsets = entries.map { entry ->
        val start = out.size
        out.u32(0x04034b50).u16(20).u16(entry.flags).u16(entry.method).u16(0).u16(0x21).u32(entry.crc)
            .u32(entry.stored.size.toLong()).u32(entry.size).u16(entry.name.size).u16(entry.localExtra.size)
            .bytes(entry.name).bytes(entry.localExtra).bytes(entry.stored)
        start
    }
    val centralStart = out.size
    for (i in centralOrder) {
        val entry = entries[i]
        out.u32(0x02014b50).u16(entry.system shl 8 or 20).u16(20).u16(entry.flags).u16(entry.method).u16(0).u16(0x21)
            .u32(entry.crc).u32(entry.stored.size.toLong()).u32(entry.size).u16(entry.name.size).u16(entry.centralExtra.size)
            .u16(0).u16(0).u16(0).u32(entry.external).u32(offsets[offsetOf(i)].toLong()).bytes(entry.name).bytes(entry.centralExtra)
    }
    val centralSize = out.size - centralStart
    out.u32(0x06054b50).u16(0).u16(0).u16(centralOrder.size).u16(centralOrder.size).u32(centralSize.toLong())
        .u32(centralStart.toLong()).u16(comment.size).bytes(comment)
    return out.toByteArray()
}

/** An extra field (APPNOTE.TXT section 4.5) with [id] and [data]. */
internal fun extraField(id: Int, data: ByteArray): ByteArray = Le().u16(id).u16(data.size).bytes(data).toByteArray()

/** Info-ZIP's Unicode Path field: version 1, the CRC-32 of the name it stands for, and the UTF-8 name. */
internal fun unicodePath(forName: ByteArray, path: String): ByteArray =
    extraField(0x7075, Le().bytes(byteArrayOf(1)).u32(crcOf(forName).toLong() and 0xffffffffL).bytes(path.toByteArray()).toByteArray())

internal class Le {
    private val out = ByteArrayOutputStream()
    val size: Int get() = out.size()

    fun u16(value: Int) = apply {
        out.write(value and 0xff)
        out.write(value ushr 8 and 0xff)
    }

    fun u32(value: Long) = apply {
        u16((value and 0xffff).toInt())
        u16((value ushr 16 and 0xffff).toInt())
    }

    fun u32(value: Int) = u32(value.toLong() and 0xffffffffL)

    fun bytes(bytes: ByteArray) = apply { out.write(bytes) }

    fun toByteArray(): ByteArray = out.toByteArray()
}
