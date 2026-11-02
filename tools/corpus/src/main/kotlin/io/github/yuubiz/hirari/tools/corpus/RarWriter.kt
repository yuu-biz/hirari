package io.github.yuubiz.hirari.tools.corpus

/**
 * Stored RAR archives written from the published format documentation: the RAR 4 technote (`TechNote.txt` of
 * RAR 4) and the RAR 5 archive format description on rarlab.com. Only the store method is written, since
 * compressing needs the proprietary encoder. An encrypted entry or header carries the flags and records of
 * encryption but no real cipher text, which is enough for the reader to refuse it.
 *
 * [name] is written byte for byte. RAR 4 keeps it in the OEM or ANSI code page unless the Unicode flag is set,
 * which this writer never sets; RAR 5 keeps it in UTF-8.
 */
class RarEntry(val name: ByteArray, val data: ByteArray, val isDirectory: Boolean = false, val encrypted: Boolean = false)

private val RAR4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)
private const val RAR4_MAIN = 0x73
private const val RAR4_FILE = 0x74
private const val RAR4_END = 0x7b
private const val MHD_SOLID = 0x0008
private const val MHD_PASSWORD = 0x0080
private const val FHD_PASSWORD = 0x0004
private const val FHD_SOLID = 0x0010
private const val FHD_DIRECTORY = 0x00e0
private const val LONG_BLOCK = 0x8000
private const val END_FLAGS = 0x4000
private const val RAR4_STORE = 0x30
private const val RAR4_UNPACK_VERSION = 29
private const val HOST_WIN32 = 2
private const val ATTR_ARCHIVE = 0x20
private const val ATTR_DIRECTORY = 0x10

// 1980-01-01 00:00 as an MS-DOS date and time.
private const val DOS_TIME = ((1 shl 5) or 1) shl 16

/**
 * A RAR 4 archive. [solid] sets the solid flag of the main header and of every file after the first, as a solid
 * archive has them. [encryptedHeaders] sets the flag that the headers after the main header are encrypted, and
 * writes nothing after it, since nothing after it can be read without the password.
 */
fun writeRar4(entries: List<RarEntry>, solid: Boolean = false, encryptedHeaders: Boolean = false): ByteArray = bytes {
    write(RAR4_SIGNATURE)
    val mainFlags = (if (solid) MHD_SOLID else 0) or (if (encryptedHeaders) MHD_PASSWORD else 0)
    rar4Block(RAR4_MAIN, mainFlags, bytes { u16(0); u32(0) })
    if (encryptedHeaders) return@bytes
    entries.forEachIndexed { i, entry ->
        val flags = LONG_BLOCK or
            (if (entry.encrypted) FHD_PASSWORD else 0) or
            (if (solid && i > 0) FHD_SOLID else 0) or
            (if (entry.isDirectory) FHD_DIRECTORY else 0)
        val fields = bytes {
            u32(entry.data.size) // PACK_SIZE
            u32(entry.data.size) // UNP_SIZE
            u8(HOST_WIN32)
            u32(crc32(entry.data))
            u32(DOS_TIME)
            u8(RAR4_UNPACK_VERSION)
            u8(RAR4_STORE)
            u16(entry.name.size)
            u32(if (entry.isDirectory) ATTR_DIRECTORY else ATTR_ARCHIVE)
            write(entry.name)
        }
        rar4Block(RAR4_FILE, flags, fields)
        write(entry.data)
    }
    rar4Block(RAR4_END, END_FLAGS, ByteArray(0))
}

/** HEAD_CRC, HEAD_TYPE, HEAD_FLAGS and HEAD_SIZE, then [fields]. HEAD_CRC is the low half of the CRC-32 of the rest. */
private fun LittleEndianBytes.rar4Block(type: Int, flags: Int, fields: ByteArray) {
    val rest = bytes {
        u8(type)
        u16(flags)
        u16(7 + fields.size)
        write(fields)
    }
    u16(crc32(rest) and 0xffff)
    write(rest)
}

private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)
private const val HEAD_MAIN = 1L
private const val HEAD_FILE = 2L
private const val HEAD_CRYPT = 4L
private const val HEAD_END = 5L
private const val HFL_EXTRA = 0x0001L
private const val HFL_DATA = 0x0002L
private const val ARCHIVE_SOLID = 0x0004L
private const val FILE_DIRECTORY = 0x0001L
private const val FILE_CRC32 = 0x0004L
private const val EXTRA_CRYPT = 0x01L
private const val HOST_WINDOWS = 0L
private const val KDF_COUNT = 15

/**
 * A RAR 5 archive of stored entries. [solid] sets the solid flag of the main header only: the solid flag of a
 * file tells the decoder to keep its window, which stored files never fill. [encryptedHeaders] writes the
 * archive encryption header in place of the main header and nothing after it.
 */
fun writeRar5(entries: List<RarEntry>, solid: Boolean = false, encryptedHeaders: Boolean = false): ByteArray = bytes {
    write(RAR5_SIGNATURE)
    if (encryptedHeaders) {
        rar5Block(HEAD_CRYPT, 0) {
            vint(0) // AES-256
            vint(0) // no password check value
            u8(KDF_COUNT)
            write(ByteArray(16) { it.toByte() }) // salt
        }
        return@bytes
    }
    rar5Block(HEAD_MAIN, 0) { vint(if (solid) ARCHIVE_SOLID else 0) }
    for (entry in entries) {
        val extra = if (entry.encrypted) {
            bytes {
                val record = bytes {
                    vint(EXTRA_CRYPT)
                    vint(0) // AES-256
                    vint(0) // no password check value, no MAC
                    u8(KDF_COUNT)
                    write(ByteArray(16) { it.toByte() }) // salt
                    write(ByteArray(16) { (it + 16).toByte() }) // IV
                }
                vint(record.size.toLong())
                write(record)
            }
        } else {
            ByteArray(0)
        }
        val headerFlags = HFL_DATA or (if (extra.isNotEmpty()) HFL_EXTRA else 0)
        rar5Block(HEAD_FILE, headerFlags) {
            if (extra.isNotEmpty()) vint(extra.size.toLong())
            vint(entry.data.size.toLong())
            vint(if (entry.isDirectory) FILE_DIRECTORY else FILE_CRC32)
            vint(entry.data.size.toLong()) // unpacked size
            vint(if (entry.isDirectory) ATTR_DIRECTORY.toLong() else ATTR_ARCHIVE.toLong())
            if (!entry.isDirectory) u32(crc32(entry.data))
            vint(0) // compression: version 0, store, the smallest dictionary
            vint(HOST_WINDOWS)
            vint(entry.name.size.toLong())
            write(entry.name)
            write(extra)
        }
        write(entry.data)
    }
    rar5Block(HEAD_END, 0) { vint(0) }
}

/** The CRC-32 of the header size and the header, the header size, then the header: type, flags and [fields]. */
private fun LittleEndianBytes.rar5Block(type: Long, flags: Long, fields: LittleEndianBytes.() -> Unit) {
    val header = bytes {
        vint(type)
        vint(flags)
        fields()
    }
    val sized = bytes {
        vint(header.size.toLong())
        write(header)
    }
    u32(crc32(sized))
    write(sized)
}
