package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.NaturalOrder
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The outcome of [FontStore.import]. */
sealed interface FontImport {
    /** Stored as [fileName], which `TextTypeface.Imported` names. */
    data class Imported(val fileName: String) : FontImport

    /** The data is not a TrueType, OpenType or collection font. */
    data object NotAFont : FontImport

    /** The copy would leave less than [FontStore.RESERVE_BYTES] free. */
    data object NoRoom : FontImport

    /** Reading the picked file or writing the copy failed. [FontStore.import] throws instead; its callers report this. */
    data object Unreadable : FontImport
}

/**
 * The fonts the user imported for the text reader, as files in [dir] (`filesDir/fonts/`, docs/spec.md sections 4.9
 * and 7). Every call touches the disk, so none may run on the main thread.
 */
class FontStore(private val dir: File) {
    /** The stored file names in natural order. */
    fun list(): List<String> = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(PARTIAL) }
        ?.map { it.name }?.sortedWith(NaturalOrder).orEmpty()

    /** The file of [fileName], or null when it is not a stored font. */
    fun file(fileName: String): File? = fileName.takeIf(::isPlainName)?.let { File(dir, it) }?.takeIf { it.isFile }

    /**
     * Copies the font in [input] under [displayName], the name the provider gave. The name loses any path and
     * takes ` (2)`, ` (3)`, ... before its extension when a font already has it.
     *
     * @throws IOException when reading or writing fails.
     */
    fun import(displayName: String, input: InputStream): FontImport {
        val head = ByteArray(MAGIC_BYTES)
        var filled = 0
        while (filled < head.size) {
            val read = input.read(head, filled, head.size - filled)
            if (read < 0) break
            filled += read
        }
        if (!isFont(head.copyOf(filled))) return FontImport.NotAFont
        dir.mkdirs()
        val partial = File.createTempFile(PARTIAL, null, dir)
        try {
            partial.outputStream().use { out ->
                out.write(head, 0, filled)
                val buffer = ByteArray(COPY_CHUNK)
                var sinceCheck = Long.MAX_VALUE
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (sinceCheck >= SPACE_CHECK_INTERVAL) {
                        if (dir.usableSpace < RESERVE_BYTES + SPACE_CHECK_INTERVAL) return FontImport.NoRoom
                        sinceCheck = 0
                    }
                    out.write(buffer, 0, read)
                    sinceCheck += read
                }
            }
            val target = freeName(fileNameOf(displayName))
            if (!partial.renameTo(File(dir, target))) throw IOException("could not store the font")
            return FontImport.Imported(target)
        } finally {
            partial.delete()
        }
    }

    fun delete(fileName: String): Boolean = file(fileName)?.delete() == true

    private fun freeName(name: String): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) name else "$stem ($it)$extension" }
            .first { !File(dir, it).exists() }
    }

    companion object {
        /** The free space an import leaves at least, as for the copies of docs/spec.md section 3.1. */
        const val RESERVE_BYTES: Long = 64L shl 20

        private const val PARTIAL = ".partial-"
        private const val MAGIC_BYTES = 4
        private const val COPY_CHUNK = 64 * 1024
        private const val SPACE_CHECK_INTERVAL: Long = 1L shl 20

        private val MAGICS = listOf(
            byteArrayOf(0x00, 0x01, 0x00, 0x00),
            "true".toByteArray(Charsets.US_ASCII),
            "OTTO".toByteArray(Charsets.US_ASCII),
            "ttcf".toByteArray(Charsets.US_ASCII),
        )

        /** TrueType (`00 01 00 00` or `true`), OpenType with CFF (`OTTO`), or a collection (`ttcf`). */
        fun isFont(head: ByteArray): Boolean = MAGICS.any { head.size >= it.size && it.indices.all { i -> head[i] == it[i] } }

        /** The last path segment of [displayName], or `font` when nothing is left of it. */
        internal fun fileNameOf(displayName: String): String =
            displayName.split('/', '\\').last().trim().trimStart('.').takeIf { it.isNotEmpty() } ?: "font"

        private fun isPlainName(name: String): Boolean =
            name.isNotEmpty() && '/' !in name && '\\' !in name && !name.startsWith('.')
    }
}
