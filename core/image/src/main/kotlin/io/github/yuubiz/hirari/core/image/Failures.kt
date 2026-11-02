package io.github.yuubiz.hirari.core.image

import android.graphics.ImageDecoder
import io.github.yuubiz.hirari.core.archive.ArchiveFailure
import io.github.yuubiz.hirari.core.archive.EntryResult
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.UnsupportedKind

/** The page result for an entry that gave no bytes (docs/spec.md section 2.4), or null for [EntryResult.Data]. */
internal fun EntryResult.pageFailure(): PageResult<Nothing>? = when (this) {
    is EntryResult.Data -> null
    is EntryResult.Failed -> reason.pageFailure()
    EntryResult.Encrypted -> PageResult.Unsupported(UnsupportedKind.Encrypted)
    is EntryResult.UnsupportedCompression -> PageResult.Unsupported(UnsupportedKind.Compression(method))
    is EntryResult.TooLarge -> tooLargeEntry(bytes)
}

internal fun ArchiveFailure.pageFailure(): PageResult.Failed = when (this) {
    ArchiveFailure.Corrupt -> PageResult.Failed(FailureReason.Corrupt, retryable = false)
    ArchiveFailure.Truncated -> PageResult.Failed(FailureReason.Truncated, retryable = false)
    ArchiveFailure.ReadError -> readError
}

/** An entry or file over the size limit. Its dimensions are unknown, so they are 0. */
internal fun tooLargeEntry(bytes: Long) = PageResult.Unsupported(UnsupportedKind.TooLarge(0, 0, bytes))

/** The page result for `ImageDecoder.DecodeException.getError`. */
internal fun decodeFailure(error: Int): PageResult.Failed = when (error) {
    ImageDecoder.DecodeException.SOURCE_INCOMPLETE -> PageResult.Failed(FailureReason.Truncated, retryable = false)
    ImageDecoder.DecodeException.SOURCE_EXCEPTION -> readError
    else -> corrupt
}

/** The type was sniffed, but M1 does not decode it. */
internal fun unsupportedType(type: ImageType) =
    PageResult.Unsupported(UnsupportedKind.ImageFeature(type.displayName))

internal val notAnImage = PageResult.Unsupported(UnsupportedKind.NotAnImage)
internal val corrupt = PageResult.Failed(FailureReason.Corrupt, retryable = false)
internal val readError = PageResult.Failed(FailureReason.ReadError, retryable = true)
internal val timedOut = PageResult.Failed(FailureReason.Timeout, retryable = true)
