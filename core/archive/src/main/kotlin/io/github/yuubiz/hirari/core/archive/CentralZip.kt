package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.Job
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.coroutines.cancellation.CancellationException

/** What a ZIP entry holds, from its external attributes and its name. */
internal enum class ZipKind { File, Directory, Link }

/**
 * One entry as the central directory of a ZIP records it (APPNOTE.TXT section 4.3.12). [name] is in the form
 * docs/spec.md section 3.4 gives ZIP names.
 */
internal class ZipRecord(
    val name: ByteArray,
    val kind: ZipKind,
    val flags: Int,
    val method: Int,
    val crc: Int,
    val compressedSize: Long,
    val size: Long,
    val headerOffset: Long,
    /** The length of the local header when it repeats the name and extra field of this record, as it usually does. */
    val expectedHeaderLength: Int,
) {
    val encrypted: Boolean get() = flags and (FLAG_ENCRYPTED or FLAG_STRONG_ENCRYPTION) != 0

    fun entry(index: Int) = ArchiveEntry(
        entryIndex = index,
        rawName = name,
        declaredSize = if (kind == ZipKind.Link) 0 else size,
        isDirectory = kind == ZipKind.Directory,
        encrypted = encrypted,
        compression = methodName(method),
    )
}

/**
 * Lists a ZIP from its end record and central directory alone, in local header order as libarchive lists it, and
 * reads a stored or Deflate entry from its own local header on (docs/spec.md section 3.3).
 */
internal object CentralZip {
    /**
     * Whether [prefix], the first bytes of a file, start a ZIP that [list] may read: a local header, or the end
     * record of an empty archive. Data before the first local header, as in a self-extracting archive, is left to
     * libarchive.
     */
    fun startsZip(prefix: ByteArray): Boolean = prefix.size >= 4 && prefix.u32(0).let { it == LOCAL_SIGNATURE || it == END_SIGNATURE }

    /**
     * The entries of the ZIP in [source], or null when its end record and central directory are not as a whole,
     * well-formed archive has them, which libarchive then reads its own way.
     *
     * @throws SourceFailure when [source] fails.
     * @throws CancellationException when [job] is cancelled.
     */
    fun list(source: ByteSource, job: Job): List<ZipRecord>? {
        val size = source.size
        if (size < END_SIZE) return null
        val tailStart = maxOf(0L, size - TAIL_PROBE)
        val tail = readUpTo(source, tailStart, (size - tailStart).toInt(), job)
        if (tail.size.toLong() != size - tailStart) return null
        val end = tail.lastIndexOfSignature(END_SIGNATURE, tail.size - END_SIZE) ?: return null
        val endOffset = tailStart + end
        if (tail.u16(end + 4) != 0 || tail.u16(end + 6) != 0 || tail.u16(end + 8) != tail.u16(end + 10)) return null
        if (endOffset + END_SIZE + tail.u16(end + 20) != size) return null
        var count = tail.u16(end + 10).toLong()
        var directorySize = tail.u32(end + 12)
        var directoryOffset = tail.u32(end + 16)
        var directoryEnd = endOffset
        if (end >= LOCATOR_SIZE && tail.u32(end - LOCATOR_SIZE) == LOCATOR_SIGNATURE) {
            val locator = end - LOCATOR_SIZE
            if (tail.u32(locator + 4) != 0L || tail.u32(locator + 16) != 1L) return null
            val recordOffset = tail.u64(locator + 8)
            if (recordOffset !in 0..endOffset - LOCATOR_SIZE - END64_SIZE) return null
            val record = bytesAt(source, recordOffset, END64_SIZE, tailStart, tail, job) ?: return null
            if (record.u32(0) != END64_SIGNATURE || record.u32(16) != 0L || record.u32(20) != 0L) return null
            if (record.u64(24) != record.u64(32)) return null
            count = record.u64(32)
            directorySize = record.u64(40)
            directoryOffset = record.u64(48)
            directoryEnd = recordOffset
        }
        if (directoryOffset !in 0..directoryEnd || directorySize != directoryEnd - directoryOffset) return null
        if (directorySize > MAX_DIRECTORY) return null
        val directory = bytesAt(source, directoryOffset, directorySize.toInt(), tailStart, tail, job) ?: return null
        val records = ArrayList<ZipRecord>()
        var at = 0
        while (at < directory.size) {
            if (at + CENTRAL_SIZE > directory.size || directory.u32(at) != CENTRAL_SIGNATURE) return null
            val next = at + CENTRAL_SIZE + directory.u16(at + 28) + directory.u16(at + 30) + directory.u16(at + 32)
            if (next > directory.size) return null
            records += record(directory, at) ?: return null
            at = next
        }
        if (records.size.toLong() != count) return null
        if (records.any { it.headerOffset > directoryOffset - LOCAL_SIZE }) return null
        // libarchive keeps one entry per local header offset, the first the central directory gives.
        return records.distinctBy { it.headerOffset }.sortedBy { it.headerOffset }
    }

    private fun record(directory: ByteArray, at: Int): ZipRecord? {
        val system = directory.u8(at + 5)
        val neededHost = directory.u8(at + 7)
        val flags = directory.u16(at + 8)
        if (flags and FLAG_ENCRYPTED_DIRECTORY != 0) return null
        val method = directory.u16(at + 10)
        val nameLength = directory.u16(at + 28)
        val extraLength = directory.u16(at + 30)
        val external = directory.u32(at + 38)
        val nameStart = at + CENTRAL_SIZE
        val extra = Extra.parse(directory, nameStart + nameLength, extraLength) ?: return null
        var size = directory.u32(at + 24)
        var compressedSize = directory.u32(at + 20)
        var headerOffset = directory.u32(at + 42)
        extra.zip64?.let { zip64 ->
            val fields = Zip64Fields(directory, zip64)
            if (size == U32_MAX) size = fields.next() ?: return null
            if (compressedSize == U32_MAX) compressedSize = fields.next() ?: return null
            if (headerOffset == U32_MAX) headerOffset = fields.next() ?: return null
        }
        val stored = directory.copyOfRange(nameStart, nameStart + nameLength)
        val main = if (flags and FLAG_UTF8 != 0) utf8Nfc(stored) else cString(stored)
        // libarchive takes the Info-ZIP Unicode Path when it was written for the name as it stands.
        val unicode = extra.unicodePath?.takeIf { (crc, _) -> main == null || crc32(main) == crc }?.let { utf8Nfc(it.second) }
        var name = (unicode ?: main)?.let { if (neededHost == HOST_MSDOS) slashesForBackslashes(it) else it }
        val mode = when (system) {
            HOST_UNIX -> (external ushr 16).toInt() and S_IFMT
            HOST_MSDOS -> if (external and MSDOS_DIRECTORY != 0L) S_IFDIR else S_IFREG
            else -> 0
        }
        val kind = when {
            mode == S_IFDIR || name?.lastOrNull() == SLASH -> ZipKind.Directory
            mode == S_IFLNK -> ZipKind.Link
            else -> ZipKind.File
        }
        if (kind == ZipKind.Link && (compressedSize > MAX_LINK || method !in LINK_METHODS)) return null
        if (kind == ZipKind.Directory) name = name?.let { if (it.isNotEmpty() && it.last() != SLASH) it + SLASH else it }
        val crc = directory.u32(at + 16).toInt()
        return ZipRecord(name ?: ByteArray(0), kind, flags, method, crc, compressedSize, size, headerOffset, LOCAL_SIZE + nameLength + extraLength)
    }

    /** Whether [read] reads [record]. libarchive reads the other methods, PPMd among them. */
    fun reads(record: ZipRecord): Boolean = record.kind == ZipKind.Link || record.method == STORED || record.method == DEFLATED

    /**
     * The data of [record], which [reads] and which is not encrypted, read from its local header on in as few reads
     * as the size allows (docs/spec.md section 3.3). The checks and failures are libarchive's: a local header
     * without its signature, a stream that does not end where the record says, a size or CRC-32 that differs from
     * the record are [ArchiveFailure.Corrupt], and the file ending first is [ArchiveFailure.Truncated].
     *
     * @throws CancellationException when [job] is cancelled.
     */
    fun read(source: ByteSource, record: ZipRecord, maxBytes: Int, job: Job): EntryResult {
        if (record.kind == ZipKind.Link) return EntryResult.Data(ByteArray(0))
        val unpacker = Unpacker(record.method, EntryBytes(maxBytes))
        try {
            val first = readUpTo(source, record.headerOffset, chunk(record.expectedHeaderLength + record.compressedSize), job)
            if (first.size < LOCAL_SIZE) return EntryResult.Failed(ArchiveFailure.Truncated)
            if (first.u32(0) != LOCAL_SIGNATURE) return EntryResult.Failed(ArchiveFailure.Corrupt)
            val dataStart = LOCAL_SIZE + first.u16(26) + first.u16(28)
            var remaining = record.compressedSize
            val inFirst = minOf(maxOf(first.size - dataStart, 0).toLong(), remaining).toInt()
            if (!unpacker.take(first, dataStart, inFirst)) return unpacker.tooLarge()
            remaining -= inFirst
            var position = record.headerOffset + dataStart + inFirst
            while (remaining > 0) {
                val length = chunk(remaining)
                val bytes = readUpTo(source, position, length, job)
                if (bytes.isNotEmpty() && !unpacker.take(bytes, 0, bytes.size)) return unpacker.tooLarge()
                if (bytes.size < length) return EntryResult.Failed(ArchiveFailure.Truncated)
                remaining -= length
                position += length
            }
            return unpacker.finish(record)
        } catch (_: DataFormatException) {
            return EntryResult.Failed(ArchiveFailure.Corrupt)
        } catch (e: SourceFailure) {
            return EntryResult.Failed(failureOf(e.cause))
        } finally {
            unpacker.end()
        }
    }

    /** One read's worth of [remaining] bytes. The last read takes the rest, so that no read asks for a small tail. */
    private fun chunk(remaining: Long): Int = if (remaining <= CHUNK + CHUNK / 4) remaining.toInt() else CHUNK

    /** Turns the compressed bytes of one entry into its data, counting its size and CRC-32. */
    private class Unpacker(method: Int, private val bytes: EntryBytes) {
        private val inflater = if (method == DEFLATED) Inflater(true) else null
        private val output = if (inflater != null) ByteArray(INFLATE_BUFFER) else null
        private val crc = CRC32()
        private var size = 0L
        private var endedEarly = false

        /** Takes [length] compressed bytes of [chunk] from [from]. False once the data passes the limit. */
        fun take(chunk: ByteArray, from: Int, length: Int): Boolean {
            if (length == 0) return true
            if (inflater == null) return emit(chunk, from, length)
            if (inflater.finished()) {
                endedEarly = true
                return true
            }
            inflater.setInput(chunk, from, length)
            while (!inflater.finished() && !inflater.needsInput()) {
                val before = inflater.remaining
                val n = inflater.inflate(output!!)
                if (n == 0 && !inflater.finished() && (inflater.needsDictionary() || inflater.remaining == before)) throw DataFormatException("no progress")
                if (n > 0 && !emit(output, 0, n)) return false
            }
            if (inflater.finished() && inflater.remaining > 0) endedEarly = true
            return true
        }

        private fun emit(data: ByteArray, from: Int, length: Int): Boolean {
            if (!bytes.write(ByteBuffer.wrap(data, from, length).slice(), size)) return false
            crc.update(data, from, length)
            size += length
            return true
        }

        fun tooLarge(): EntryResult = EntryResult.TooLarge(bytes.exceeded!!)

        fun finish(record: ZipRecord): EntryResult {
            val whole = inflater == null || (inflater.finished() && !endedEarly)
            if (!whole || size != record.size || crc.value.toInt() != record.crc) return EntryResult.Failed(ArchiveFailure.Corrupt)
            return EntryResult.Data(bytes.toByteArray())
        }

        fun end() {
            inflater?.end()
        }
    }

    /** The extra fields of one record that the listing uses (APPNOTE.TXT section 4.5). */
    private class Extra(val zip64: IntRange?, val unicodePath: Pair<Int, ByteArray>?) {
        companion object {
            /** null when the fields overrun the extra data, which libarchive refuses. */
            fun parse(bytes: ByteArray, start: Int, length: Int): Extra? {
                if (length < 4) return if ((start until start + length).all { bytes[it] == 0.toByte() }) Extra(null, null) else null
                var zip64: IntRange? = null
                var unicodePath: Pair<Int, ByteArray>? = null
                var at = 0
                while (at <= length - 4) {
                    val id = bytes.u16(start + at)
                    val dataSize = bytes.u16(start + at + 2)
                    val data = start + at + 4
                    at += 4 + dataSize
                    if (at > length) return null
                    when (id) {
                        EXTRA_ZIP64 -> zip64 = data until data + dataSize
                        EXTRA_UNICODE_PATH -> if (dataSize >= 5) {
                            unicodePath = bytes.u32(data + 1).toInt() to bytes.copyOfRange(data + 5, data + dataSize)
                        }
                    }
                }
                return Extra(zip64, unicodePath)
            }
        }
    }

    /** The 64-bit values of a ZIP64 extra field, taken in order for each 32-bit field that is all ones. */
    private class Zip64Fields(private val bytes: ByteArray, private val range: IntRange) {
        private var at = range.first

        fun next(): Long? {
            if (at + 8 > range.last + 1) return null
            return bytes.u64(at).takeIf { it >= 0 }.also { at += 8 }
        }
    }
}

/** libarchive's names of the ZIP methods (APPNOTE.TXT section 4.4.5), which `Compression` reports. */
internal fun methodName(method: Int): String = METHOD_NAMES[method] ?: "??"

private val METHOD_NAMES = mapOf(
    0 to "uncompressed", 1 to "shrinking", 2 to "reduced-1", 3 to "reduced-2", 4 to "reduced-3", 5 to "reduced-4",
    6 to "imploded", 7 to "reserved", 8 to "deflation", 9 to "deflation-64-bit", 10 to "ibm-terse", 11 to "reserved",
    12 to "bzip", 13 to "reserved", 14 to "lzma", 15 to "reserved", 16 to "reserved", 17 to "reserved",
    18 to "ibm-terse-new", 19 to "ibm-lz777", 93 to "zstd", 95 to "xz", 96 to "jpeg", 97 to "wav-pack", 98 to "ppmd-1",
    99 to "aes",
)

/**
 * Reads up to [length] bytes at [offset], fewer only at the end of [source]. Reads as few times as [source]
 * allows, so that an SMB source sees one request for the whole range (docs/spec.md section 3.1).
 */
internal fun readUpTo(source: ByteSource, offset: Long, length: Int, job: Job): ByteArray {
    val buffer = ByteBuffer.allocate(length)
    while (buffer.hasRemaining()) {
        if (!job.isActive) throw CancellationException("archive read cancelled")
        val n = try {
            source.read(offset + buffer.position(), buffer)
        } catch (e: Exception) {
            throw SourceFailure(e)
        }
        if (n <= 0) break
    }
    return if (buffer.hasRemaining()) buffer.array().copyOf(buffer.position()) else buffer.array()
}

/** The exception a [ByteSource] read threw, set apart from the failures of the parsing. */
internal class SourceFailure(override val cause: Exception) : Exception(cause)

/** [length] bytes at [offset], taking the part at or after [tailStart] from [tail]. null when the file ends first. */
private fun bytesAt(source: ByteSource, offset: Long, length: Int, tailStart: Long, tail: ByteArray, job: Job): ByteArray? {
    val end = offset + length
    if (end - tailStart > tail.size) return null
    if (offset >= tailStart) return tail.copyOfRange((offset - tailStart).toInt(), (end - tailStart).toInt())
    val headLength = (minOf(end, tailStart) - offset).toInt()
    val head = readUpTo(source, offset, headLength, job)
    if (head.size != headLength) return null
    return if (end <= tailStart) head else head + tail.copyOf((end - tailStart).toInt())
}

/** A name with the ZIP UTF-8 flag as libarchive gives it: NFC, or null when it is not UTF-8. */
private fun utf8Nfc(bytes: ByteArray): ByteArray? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(ByteBuffer.wrap(cString(bytes))).toString()
    } catch (_: CharacterCodingException) {
        return null
    }
    return Normalizer.normalize(text, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
}

private fun isUtf8(bytes: ByteArray): Boolean = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
    true
} catch (_: CharacterCodingException) {
    false
}

/** libarchive hands names over as C strings, so a name ends at its first NUL. */
private fun cString(bytes: ByteArray): ByteArray = bytes.indexOf(0).let { if (it < 0) bytes else bytes.copyOf(it) }

/**
 * Windows archivers may separate folders with `\`. A name in UTF-8 with no `/` gets `/` in their place. A name in
 * another charset keeps its bytes, since 0x5C is also the second byte of CP932 characters (docs/spec.md 3.4).
 */
private fun slashesForBackslashes(name: ByteArray): ByteArray {
    if (SLASH in name || BACKSLASH !in name || !isUtf8(name)) return name
    return ByteArray(name.size) { if (name[it] == BACKSLASH) SLASH else name[it] }
}

private fun crc32(bytes: ByteArray): Int = CRC32().apply { update(bytes) }.value.toInt()

private fun ByteArray.lastIndexOfSignature(signature: Long, from: Int): Int? {
    for (i in from downTo 0) if (u32(i) == signature) return i
    return null
}

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xff

private fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

private fun ByteArray.u32(at: Int): Long = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)

private fun ByteArray.u64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)

internal const val LOCAL_SIGNATURE = 0x04034b50L
private const val CENTRAL_SIGNATURE = 0x02014b50L
private const val END_SIGNATURE = 0x06054b50L
private const val END64_SIGNATURE = 0x06064b50L
private const val LOCATOR_SIGNATURE = 0x07064b50L
internal const val LOCAL_SIZE = 30
private const val CENTRAL_SIZE = 46
private const val END_SIZE = 22
private const val END64_SIZE = 56
private const val LOCATOR_SIZE = 20
private const val MAX_COMMENT = 0xffff

/** The end of the file read first: room for the longest comment, the end record and the ZIP64 records before it. */
internal const val TAIL_PROBE = MAX_COMMENT + END_SIZE + LOCATOR_SIZE + END64_SIZE

private const val MAX_DIRECTORY = 64L shl 20

/** The bytes of one read of an entry's data. An SMB source asks for them in one request (docs/spec.md 3.1). */
private const val CHUNK = 1 shl 20
private const val INFLATE_BUFFER = 64 * 1024
private const val STORED = 0
private const val DEFLATED = 8
private const val U32_MAX = 0xffffffffL
private const val FLAG_ENCRYPTED = 1
private const val FLAG_STRONG_ENCRYPTION = 1 shl 6
private const val FLAG_UTF8 = 1 shl 11
private const val FLAG_ENCRYPTED_DIRECTORY = 1 shl 13
private const val EXTRA_ZIP64 = 0x0001
private const val EXTRA_UNICODE_PATH = 0x7075
private const val HOST_MSDOS = 0
private const val HOST_UNIX = 3
private const val MSDOS_DIRECTORY = 0x10L
private const val S_IFMT = 0xf000
private const val S_IFDIR = 0x4000
private const val S_IFREG = 0x8000
private const val S_IFLNK = 0xa000

/** libarchive refuses a link whose target is longer, or stored in a method it cannot read for links. */
private const val MAX_LINK = 64 * 1024
private val LINK_METHODS = setOf(0, 8, 14)
private const val SLASH = '/'.code.toByte()
private const val BACKSLASH = '\\'.code.toByte()
