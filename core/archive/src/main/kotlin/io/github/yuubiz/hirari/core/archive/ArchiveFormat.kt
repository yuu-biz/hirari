package io.github.yuubiz.hirari.core.archive

/** The archive formats the session reads (docs/spec.md section 3.3), by libarchive's `archive_format` code. */
enum class ArchiveFormat(internal val code: Int) {
    Zip(0x50000),
    Rar(0xD0000),
    Rar5(0x100000),
    SevenZip(0xE0000),
    Tar(0x30000),
    ;

    internal companion object {
        fun of(code: Int): ArchiveFormat? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Whether reaching an entry means decoding every entry before it, which decides how the session reads
 * (docs/spec.md section 3.3). libarchive does not say, so it comes from the format, the outer filter, and for
 * RAR 5 the solid flag of the main header, read from [prefix], the first bytes of the archive.
 */
internal fun isSolid(format: ArchiveFormat?, filter: Int, prefix: ByteArray): Boolean = when (format) {
    // libarchive stops at the second file header of a solid RAR 4, so only its first entry is ever listed.
    null, ArchiveFormat.Zip, ArchiveFormat.Rar -> false
    // The folders of a 7z share one stream when solid, and telling needs the header, which is usually compressed.
    ArchiveFormat.SevenZip -> true
    ArchiveFormat.Tar -> filter != FILTER_NONE
    ArchiveFormat.Rar5 -> rar5Solid(prefix)
}

private const val FILTER_NONE = 0

private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)
private const val RAR5_MAIN_HEADER = 1
private const val RAR5_HEADER_EXTRA = 0x0001
private const val RAR5_HEADER_DATA = 0x0002
private const val RAR5_SOLID = 0x0004

private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xff

/** RAR 5 technote: the signature, then the main archive header, whose fields are variable-length integers. */
private fun rar5Solid(prefix: ByteArray): Boolean {
    if (!prefix.startsWith(RAR5_SIGNATURE)) return false
    val reader = VintReader(prefix, RAR5_SIGNATURE.size + 4)
    reader.next() ?: return false // header size
    if (reader.next() != RAR5_MAIN_HEADER.toLong()) return false
    val headerFlags = reader.next() ?: return false
    if (headerFlags and RAR5_HEADER_EXTRA.toLong() != 0L) reader.next() ?: return false
    if (headerFlags and RAR5_HEADER_DATA.toLong() != 0L) reader.next() ?: return false
    val archiveFlags = reader.next() ?: return false
    return archiveFlags and RAR5_SOLID.toLong() != 0L
}

/** RAR 5's variable-length integers: seven bits per byte, low bits first, the top bit set on all but the last. */
private class VintReader(private val bytes: ByteArray, private var at: Int) {
    fun next(): Long? {
        var value = 0L
        var shift = 0
        while (at < bytes.size && shift < 63) {
            val byte = bytes.u8(at++)
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
        return null
    }
}
