package io.github.yuubiz.hirari.core.image

import android.graphics.ImageDecoder
import io.github.yuubiz.hirari.core.archive.ArchiveFailure
import io.github.yuubiz.hirari.core.archive.EntryResult
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailuresTest {
    @Test
    fun archiveResultsMapToTheErrorTable() {
        val table = mapOf(
            EntryResult.Failed(ArchiveFailure.Corrupt) to PageResult.Failed(FailureReason.Corrupt, retryable = false),
            EntryResult.Failed(ArchiveFailure.Truncated) to PageResult.Failed(FailureReason.Truncated, retryable = false),
            EntryResult.Failed(ArchiveFailure.ReadError) to PageResult.Failed(FailureReason.ReadError, retryable = true),
            EntryResult.Encrypted to PageResult.Unsupported(UnsupportedKind.Encrypted),
            EntryResult.UnsupportedCompression("lzma") to PageResult.Unsupported(UnsupportedKind.Compression("lzma")),
            EntryResult.TooLarge(300L shl 20) to PageResult.Unsupported(UnsupportedKind.TooLarge(0, 0, 300L shl 20)),
        )
        table.forEach { (entry, page) -> assertEquals("$entry", page, entry.pageFailure()) }
        assertNull(EntryResult.Data(ByteArray(1)).pageFailure())
    }

    @Test
    fun decoderErrorsMapToTheErrorTable() {
        assertEquals(PageResult.Failed(FailureReason.Truncated, false), decodeFailure(ImageDecoder.DecodeException.SOURCE_INCOMPLETE))
        assertEquals(PageResult.Failed(FailureReason.Corrupt, false), decodeFailure(ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA))
        assertEquals(PageResult.Failed(FailureReason.ReadError, true), decodeFailure(ImageDecoder.DecodeException.SOURCE_EXCEPTION))
        assertEquals(PageResult.Failed(FailureReason.Corrupt, false), decodeFailure(-1))
    }
}
