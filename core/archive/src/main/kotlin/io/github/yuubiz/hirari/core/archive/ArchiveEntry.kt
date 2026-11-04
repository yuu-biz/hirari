package io.github.yuubiz.hirari.core.archive

/**
 * One entry of an archive as the session lists it (docs/spec.md sections 2.3, 3.3 and 3.4).
 *
 * @property entryIndex the position in archive order, which [ArchiveSession.read] takes.
 * @property rawName the name before decoding. Empty when the archive gives no name, which happens for a name with
 *   the ZIP UTF-8 flag that is not UTF-8.
 * @property declaredSize the size the archive declares, or null when it declares none. Only for the size limit.
 * @property compression libarchive's name of the ZIP method, such as `deflation`, or null when there is none. The
 *   other formats name no method before the data is read.
 */
class ArchiveEntry(
    val entryIndex: Int,
    val rawName: ByteArray,
    val declaredSize: Long?,
    val isDirectory: Boolean,
    val encrypted: Boolean,
    val compression: String?,
)

/**
 * Why reading failed, in the terms of docs/spec.md section 2.4. [ReadError] and [ConnectionLost] come from the
 * source, so reading again may succeed (section 6.5).
 */
enum class ArchiveFailure { Corrupt, Truncated, ReadError, ConnectionLost }

sealed interface OpenResult {
    class Opened(val session: ArchiveSession) : OpenResult

    data class Failed(val reason: ArchiveFailure) : OpenResult

    /** The headers are encrypted, so not even the names can be read (docs/spec.md section 3.8). */
    data object Encrypted : OpenResult
}

/** The outcome of reading one entry. The loading layer maps it to `PageResult` (docs/spec.md section 2.4). */
sealed interface EntryResult {
    class Data(val bytes: ByteArray) : EntryResult

    data class Failed(val reason: ArchiveFailure) : EntryResult

    data object Encrypted : EntryResult

    data class UnsupportedCompression(val method: String) : EntryResult

    /** The entry declares or reaches more than the limit. [bytes] is the declared size or the bytes reached. */
    data class TooLarge(val bytes: Long) : EntryResult
}
