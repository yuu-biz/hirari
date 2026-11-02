package io.github.yuubiz.hirari.tools.corpus

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** One uncompressed ZIP entry. [name] is written byte for byte, so it can be in any encoding. */
class StoredEntry(val name: ByteArray, val data: ByteArray, val utf8Flag: Boolean)

private const val VERSION_NEEDED = 10
private const val FLAG_UTF8 = 1 shl 11

// 1980-01-01 00:00, the MS-DOS epoch.
private const val DOS_TIME = 0
private const val DOS_DATE = (1 shl 5) or 1

/** A ZIP of [entries] in the given order, laid out as in PKWARE's APPNOTE.TXT section 4.3. */
fun storedZip(entries: List<StoredEntry>): ByteArray {
    val out = LittleEndianBytes()
    val offsets = entries.map { entry ->
        val offset = out.size()
        out.u32(0x04034b50)
        out.common(entry)
        out.write(entry.name)
        out.write(entry.data)
        offset
    }
    val centralStart = out.size()
    entries.zip(offsets) { entry, offset ->
        out.u32(0x02014b50)
        out.u16(VERSION_NEEDED) // version made by, on MS-DOS
        out.common(entry)
        out.u16(0) // comment length
        out.u16(0) // disk number
        out.u16(0) // internal attributes
        out.u32(0) // external attributes
        out.u32(offset)
        out.write(entry.name)
    }
    val centralSize = out.size() - centralStart
    out.u32(0x06054b50)
    out.u16(0) // this disk
    out.u16(0) // disk with the central directory
    out.u16(entries.size)
    out.u16(entries.size)
    out.u32(centralSize)
    out.u32(centralStart)
    out.u16(0) // comment length
    return out.toByteArray()
}

/** The fields the local header and the central directory header share, from "version needed" to "extra field length". */
private fun LittleEndianBytes.common(entry: StoredEntry) {
    u16(VERSION_NEEDED)
    u16(if (entry.utf8Flag) FLAG_UTF8 else 0)
    u16(0) // stored
    u16(DOS_TIME)
    u16(DOS_DATE)
    u32(CRC32().apply { update(entry.data) }.value.toInt())
    u32(entry.data.size)
    u32(entry.data.size)
    u16(entry.name.size)
    u16(0) // extra field length
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
}
