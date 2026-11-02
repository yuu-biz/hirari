package io.github.yuubiz.hirari.tools.corpus

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Commons Compress reads 7z and tar independently of the writers here, so it checks that the cases it can read
// are well formed and hold the expected names and data in order. It has no RAR reader.
class ArchiveCasesTest {
    private val cases = corpusCases()

    private fun expectedNames(path: String) = (cases.getValue(path).expected as Expected.Opens).entries.map { it.name }

    private fun expectedData(path: String) = (cases.getValue(path).expected as Expected.Opens).entries.map {
        (it.read as? ExpectedRead.Data)?.bytes?.toList() ?: emptyList()
    }

    @Test
    fun `commons compress reads every readable 7z with the expected names and data`() {
        val readable = listOf("7z/copy.7z", "7z/copy-solid.7z", "7z/lzma2-solid.7z", "7z/methods.7z")
        for (path in readable) {
            val names = ArrayList<String>()
            val data = ArrayList<List<Byte>>()
            SevenZFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(cases.getValue(path).bytes)).get().use { file ->
                while (true) {
                    val entry = file.nextEntry ?: break
                    names += entry.name
                    data += file.getInputStream(entry).readBytes().toList()
                }
            }
            assertEquals(expectedNames(path), names, path)
            assertEquals(expectedData(path), data, path)
        }
    }

    // Commons Compress has no PPMd decoder, but it lists the names, sizes and CRC-32 of the entries.
    @Test
    fun `the PPMd fixtures hold the expected files in order`() {
        val expected = ppmdPages.map { (name, bytes) -> Triple(name, bytes.size.toLong(), CRC32().apply { update(bytes) }.value) }

        val sevenZip = SevenZFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(cases.getValue("7z/ppmd.7z").bytes)).get()
            .use { file -> file.entries.map { Triple(it.name, it.size, it.crcValue) } }
        val zipped = ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(cases.getValue("zip/ppmd.zip").bytes)).get()
            .use { file -> file.entries.toList().map { Triple(it.name, it.size, it.crc) to it.method } }

        assertEquals(expected, sevenZip)
        assertEquals(expected.map { it to 98 }, zipped)
    }

    // Commons Compress reads zstd only through a native library, so the zstd frame is taken apart here instead:
    // a 4-byte content size and raw blocks.
    @Test
    fun `the zstd tar is the plain tar in raw blocks`() {
        val frame = cases.getValue("tar/compressed.tzst").bytes
        val tar = cases.getValue("tar/plain.tar").bytes
        fun u32(at: Int) = (0..3).sumOf { (frame[at + it].toLong() and 0xff) shl (8 * it) }
        assertEquals(0xfd2fb528L, u32(0))
        assertEquals(tar.size.toLong(), u32(5))
        var at = 9
        val content = ArrayList<Byte>()
        do {
            val header = (0..2).sumOf { (frame[at + it].toInt() and 0xff) shl (8 * it) }
            assertEquals(0, (header shr 1) and 3, "raw block")
            content += frame.copyOfRange(at + 3, at + 3 + (header ushr 3)).toList()
            at += 3 + (header ushr 3)
        } while (header and 1 == 0)
        assertEquals(frame.size, at)
        assertEquals(tar.toList(), content)
    }

    @Test
    fun `commons compress reads the plain and every compressed tar alike`() {
        val tars = cases.keys.filter { it.startsWith("tar/") && !it.endsWith(".tzst") }
        assertEquals(4, tars.size)
        for (path in tars) {
            val bytes = cases.getValue(path).bytes
            val stream = if (path.endsWith(".tar")) {
                ByteArrayInputStream(bytes)
            } else {
                CompressorStreamFactory().createCompressorInputStream(ByteArrayInputStream(bytes).buffered())
            }
            val names = ArrayList<String>()
            val data = ArrayList<List<Byte>>()
            TarArchiveInputStream(stream, "UTF-8").use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    names += entry.name
                    data += tar.readBytes().toList()
                }
            }
            assertEquals(expectedNames(path), names, path)
            assertEquals(expectedData(path), data, path)
        }
    }

    @Test
    fun `the rar cases start with their format's signature`() {
        val rar4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00).toList()
        val rar5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00).toList()
        for ((path, case) in cases.filterKeys { it.startsWith("rar") }) {
            val signature = if (path.startsWith("rar4/")) rar4 else rar5
            assertTrue(case.bytes.toList().take(signature.size) == signature, path)
        }
    }
}
