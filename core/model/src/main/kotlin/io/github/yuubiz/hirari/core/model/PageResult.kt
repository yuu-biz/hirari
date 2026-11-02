package io.github.yuubiz.hirari.core.model

/** [T] is the prepared image. Its type lives in `:core:image` because it holds a `Bitmap`. */
sealed interface PageResult<out T> {
    data class Ready<out T>(val image: T) : PageResult<T>
    data class Failed(val reason: FailureReason, val retryable: Boolean) : PageResult<Nothing>
    data class Unsupported(val kind: UnsupportedKind) : PageResult<Nothing>
}

enum class FailureReason { Corrupt, Truncated, ReadError, ConnectionLost, Timeout }

sealed interface UnsupportedKind {
    data object Encrypted : UnsupportedKind
    data class Compression(val method: String) : UnsupportedKind

    /** For example "12-bit JPEG". */
    data class ImageFeature(val detail: String) : UnsupportedKind
    data class TooLarge(val width: Int, val height: Int, val bytes: Long) : UnsupportedKind
    data object NotAnImage : UnsupportedKind
}
