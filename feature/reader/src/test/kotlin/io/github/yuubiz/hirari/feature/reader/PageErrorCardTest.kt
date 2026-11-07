package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import io.github.yuubiz.hirari.feature.reader.PageErrorAction.Details
import io.github.yuubiz.hirari.feature.reader.PageErrorAction.Retry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageErrorCardTest {
    @Test
    fun passwordRequestsDoNotBecomePageErrorCards() {
        assertNull(pageErrorCard(PageResult.Failed(FailureReason.PasswordRequired, retryable = false)))
    }

    private fun failed(reason: FailureReason, retryable: Boolean) = pageErrorCard(PageResult.Failed(reason, retryable))

    private fun unsupported(kind: UnsupportedKind) = pageErrorCard(PageResult.Unsupported(kind))

    @Test
    fun aReadyPageHasNoCard() {
        assertNull(pageErrorCard(PageResult.Ready("image")))
    }

    @Test
    fun brokenDataOffersDetails() {
        assertEquals(PageErrorCard(PageErrorText.Corrupt, listOf(Details)), failed(FailureReason.Corrupt, retryable = false))
        assertEquals(PageErrorCard(PageErrorText.Corrupt, listOf(Details)), failed(FailureReason.Truncated, retryable = false))
    }

    @Test
    fun aFailedReadOffersRetryAndDetails() {
        assertEquals(PageErrorCard(PageErrorText.ReadFailed, listOf(Retry, Details)), failed(FailureReason.ReadError, retryable = true))
        assertEquals(PageErrorCard(PageErrorText.ReadFailed, listOf(Retry, Details)), failed(FailureReason.ConnectionLost, retryable = true))
    }

    @Test
    fun aTimeoutOffersRetry() {
        assertEquals(PageErrorCard(PageErrorText.TimedOut, listOf(Retry)), failed(FailureReason.Timeout, retryable = true))
    }

    @Test
    fun retryShowsOnlyWhenTheFailureIsRetryable() {
        assertEquals(listOf(Details), failed(FailureReason.ReadError, retryable = false)?.actions)
        assertEquals(listOf(Retry, Details), failed(FailureReason.Corrupt, retryable = true)?.actions)
    }

    @Test
    fun unsupportedPagesNameWhatIsMissing() {
        assertEquals(PageErrorCard(PageErrorText.Encrypted, listOf(Details)), unsupported(UnsupportedKind.Encrypted))
        assertEquals(PageErrorCard(PageErrorText.Compression("PPMd"), listOf(Details)), unsupported(UnsupportedKind.Compression("PPMd")))
        assertEquals(
            PageErrorCard(PageErrorText.ImageFeature("12-bit JPEG"), listOf(Details)),
            unsupported(UnsupportedKind.ImageFeature("12-bit JPEG")),
        )
        assertEquals(
            PageErrorCard(PageErrorText.TooLarge(30000, 20000), listOf(Details)),
            unsupported(UnsupportedKind.TooLarge(30000, 20000, 2_400_000_000)),
        )
        assertEquals(PageErrorCard(PageErrorText.NotAnImage, listOf(Details)), unsupported(UnsupportedKind.NotAnImage))
    }
}
