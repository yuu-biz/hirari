package io.github.yuubiz.hirari.tools.corpus

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.tukaani.xz.FinishableWrapperOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream
import org.tukaani.xz.X86Options
import java.io.ByteArrayOutputStream

/**
 * 7z archives written from `7zFormat.txt` of the LZMA SDK, which describes the format. The compressors come from
 * XZ for Java (LZMA, LZMA2 and the x86 branch filter), Apache Commons Compress (BZip2) and the JDK (Deflate). The
 * PPMd and AES coders are declared with data that is not real, which is enough for the reader to refuse them.
 */
class SevenZipFile(val name: String, val data: ByteArray)

/** One folder: the files whose data [method] packs into one stream, solid when there is more than one file. */
class SevenZipFolder(val method: SevenZipMethod, val files: List<SevenZipFile>)

private class Coder(val id: ByteArray, val properties: ByteArray = ByteArray(0))

/** How a folder's data is packed, as its coders and the packed stream. */
enum class SevenZipMethod {
    Copy,
    Lzma,
    Lzma2,
    Deflate,
    BZip2,

    /** The x86 branch filter, then LZMA2. */
    Lzma2X86,

    /** Declared only: the packed bytes are not cipher text. */
    Aes,
}

private val COPY = byteArrayOf(0x00)
private val LZMA = byteArrayOf(0x03, 0x01, 0x01)
private val LZMA2 = byteArrayOf(0x21)
private val DEFLATE = byteArrayOf(0x04, 0x01, 0x08)
private val BZIP2 = byteArrayOf(0x04, 0x02, 0x02)
private val X86 = byteArrayOf(0x03, 0x03, 0x01, 0x03)
private val AES = byteArrayOf(0x06, 0xf1.toByte(), 0x07, 0x01)

private const val DICTIONARY = 1 shl 16

// 7zFormat.txt: the dictionary of LZMA2 is (2 | (p & 1)) << (p / 2 + 11), so 8 gives 64 KiB.
private const val LZMA2_DICTIONARY_PROPERTY: Byte = 8

private const val K_END = 0x00
private const val K_HEADER = 0x01
private const val K_MAIN_STREAMS_INFO = 0x04
private const val K_FILES_INFO = 0x05
private const val K_PACK_INFO = 0x06
private const val K_UNPACK_INFO = 0x07
private const val K_SUBSTREAMS_INFO = 0x08
private const val K_SIZE = 0x09
private const val K_CRC = 0x0a
private const val K_FOLDER = 0x0b
private const val K_CODERS_UNPACK_SIZE = 0x0c
private const val K_NUM_UNPACK_STREAM = 0x0d
private const val K_NAME = 0x11
private const val K_ENCODED_HEADER = 0x17

private val SIGNATURE = byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c, 0x00, 0x04)

private fun lzmaOptions() = LZMA2Options().apply { dictSize = DICTIONARY }

/** The coders of [method] in the order 7z lists them, the first reading the packed stream, and the packed bytes. */
private fun pack(method: SevenZipMethod, data: ByteArray): Pair<List<Coder>, ByteArray> = when (method) {
    SevenZipMethod.Copy -> listOf(Coder(COPY)) to data
    SevenZipMethod.Lzma -> {
        val options = lzmaOptions()
        val properties = bytes {
            u8((options.pb * 5 + options.lp) * 9 + options.lc)
            u32(options.dictSize)
        }
        listOf(Coder(LZMA, properties)) to compress { LZMAOutputStream(it, options, false) }(data)
    }
    SevenZipMethod.Lzma2 -> listOf(Coder(LZMA2, byteArrayOf(LZMA2_DICTIONARY_PROPERTY))) to
        compress { lzmaOptions().getOutputStream(FinishableWrapperOutputStream(it)) }(data)
    SevenZipMethod.Deflate -> listOf(Coder(DEFLATE)) to deflate(data)
    SevenZipMethod.BZip2 -> listOf(Coder(BZIP2)) to compress { BZip2CompressorOutputStream(it) }(data)
    SevenZipMethod.Lzma2X86 -> listOf(Coder(LZMA2, byteArrayOf(LZMA2_DICTIONARY_PROPERTY)), Coder(X86)) to
        compress { X86Options().getOutputStream(lzmaOptions().getOutputStream(FinishableWrapperOutputStream(it))) }(data)
    SevenZipMethod.Aes -> listOf(Coder(AES, byteArrayOf(0x13))) to letters(64, 2)
}

private fun compress(open: (ByteArrayOutputStream) -> java.io.OutputStream): (ByteArray) -> ByteArray = { data ->
    val out = ByteArrayOutputStream()
    open(out).use { it.write(data) }
    out.toByteArray()
}

/**
 * A 7z archive of [folders]. With [encryptedHeader], the header is replaced by an encoded header whose folder
 * declares the AES coder, and the archive holds nothing else.
 */
fun write7z(folders: List<SevenZipFolder>, encryptedHeader: Boolean = false): ByteArray {
    val packed = folders.map { folder -> pack(folder.method, folder.files.fold(ByteArray(0)) { all, f -> all + f.data }) }
    val packedBytes = packed.fold(ByteArray(0)) { all, (_, bytes) -> all + bytes }
    val header: ByteArray
    val body: ByteArray
    if (encryptedHeader) {
        val cipher = letters(96, 3)
        body = cipher
        header = bytes {
            u8(K_ENCODED_HEADER)
            streamsInfo(listOf(listOf(Coder(AES, byteArrayOf(0x13))) to cipher), listOf(listOf(cipher.size.toLong())), substreams = null)
        }
    } else {
        body = packedBytes
        header = bytes {
            u8(K_HEADER)
            u8(K_MAIN_STREAMS_INFO)
            streamsInfo(packed, folders.map { f -> f.files.map { it.data.size.toLong() } }, folders)
            u8(K_FILES_INFO)
            filesInfo(folders.flatMap { it.files })
            u8(K_END)
        }
    }
    val start = bytes {
        u64(body.size.toLong())
        u64(header.size.toLong())
        u32(crc32(header))
    }
    return bytes {
        write(SIGNATURE)
        u32(crc32(start))
        write(start)
        write(body)
        write(header)
    }
}

/** PackInfo, UnPackInfo and, for [substreams], SubStreamsInfo. [sizes] are the sizes of each folder's files. */
private fun LittleEndianBytes.streamsInfo(
    packed: List<Pair<List<Coder>, ByteArray>>,
    sizes: List<List<Long>>,
    substreams: List<SevenZipFolder>?,
) {
    u8(K_PACK_INFO)
    number(0)
    number(packed.size.toLong())
    u8(K_SIZE)
    packed.forEach { (_, bytes) -> number(bytes.size.toLong()) }
    u8(K_END)

    u8(K_UNPACK_INFO)
    u8(K_FOLDER)
    number(packed.size.toLong())
    u8(0) // not external
    for ((coders, _) in packed) {
        number(coders.size.toLong())
        for (coder in coders) {
            u8(coder.id.size or (if (coder.properties.isNotEmpty()) 0x20 else 0))
            write(coder.id)
            if (coder.properties.isNotEmpty()) {
                number(coder.properties.size.toLong())
                write(coder.properties)
            }
        }
        // A second coder filters the first one's output: its input stream (1) takes the first one's output (0).
        if (coders.size == 2) {
            number(1)
            number(0)
        }
    }
    u8(K_CODERS_UNPACK_SIZE)
    packed.forEachIndexed { i, (coders, _) -> repeat(coders.size) { number(sizes[i].sum()) } }
    u8(K_END)

    if (substreams != null) {
        u8(K_SUBSTREAMS_INFO)
        u8(K_NUM_UNPACK_STREAM)
        substreams.forEach { number(it.files.size.toLong()) }
        if (substreams.any { it.files.size > 1 }) {
            u8(K_SIZE)
            substreams.forEach { folder -> folder.files.dropLast(1).forEach { number(it.data.size.toLong()) } }
        }
        u8(K_CRC)
        u8(1) // all defined
        substreams.flatMap { it.files }.forEach { u32(crc32(it.data)) }
        u8(K_END)
    }
    u8(K_END)
}

private fun LittleEndianBytes.filesInfo(files: List<SevenZipFile>) {
    number(files.size.toLong())
    val names = bytes {
        u8(0) // not external
        files.forEach { write((it.name + "\u0000").toByteArray(Charsets.UTF_16LE)) }
    }
    u8(K_NAME)
    number(names.size.toLong())
    write(names)
    u8(K_END)
}

/** 7zFormat.txt's NUMBER: the count of leading one bits of the first byte is the count of bytes that follow. */
private fun LittleEndianBytes.number(value: Long) {
    var extra = 0
    while (extra < 8 && value >= 1L shl (7 * (extra + 1))) extra++
    if (extra == 8) {
        u8(0xff)
        u64(value)
        return
    }
    val mask = (0xff00 ushr extra) and 0xff
    u8(mask or (value ushr (8 * extra)).toInt())
    for (i in 0 until extra) u8((value ushr (8 * i)).toInt() and 0xff)
}
