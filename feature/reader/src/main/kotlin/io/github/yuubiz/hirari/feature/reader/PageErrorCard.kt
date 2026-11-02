package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.UnsupportedKind

/** The one-line reason on an error card. The screen maps each to a string resource. */
sealed interface PageErrorText {
    data object Corrupt : PageErrorText
    data object ReadFailed : PageErrorText
    data object TimedOut : PageErrorText
    data object Encrypted : PageErrorText
    data class Compression(val method: String) : PageErrorText
    data class ImageFeature(val detail: String) : PageErrorText
    data class TooLarge(val width: Int, val height: Int) : PageErrorText
    data object NotAnImage : PageErrorText
}

enum class PageErrorAction { Retry, Details }

data class PageErrorCard(val text: PageErrorText, val actions: List<PageErrorAction>)

/**
 * The error card for a page that did not load (docs/spec.md section 4.8, ページのエラー表示), or
 * `null` for a page that did. Retry shows whenever the failure is retryable.
 */
fun pageErrorCard(result: PageResult<*>): PageErrorCard? = when (result) {
    is PageResult.Ready -> null
    is PageResult.Failed -> {
        val text = when (result.reason) {
            FailureReason.Corrupt, FailureReason.Truncated -> PageErrorText.Corrupt
            FailureReason.ReadError, FailureReason.ConnectionLost -> PageErrorText.ReadFailed
            FailureReason.Timeout -> PageErrorText.TimedOut
        }
        val actions = buildList {
            if (result.retryable) add(PageErrorAction.Retry)
            if (result.reason != FailureReason.Timeout) add(PageErrorAction.Details)
        }
        PageErrorCard(text, actions)
    }
    is PageResult.Unsupported -> {
        val text = when (val kind = result.kind) {
            UnsupportedKind.Encrypted -> PageErrorText.Encrypted
            is UnsupportedKind.Compression -> PageErrorText.Compression(kind.method)
            is UnsupportedKind.ImageFeature -> PageErrorText.ImageFeature(kind.detail)
            is UnsupportedKind.TooLarge -> PageErrorText.TooLarge(kind.width, kind.height)
            UnsupportedKind.NotAnImage -> PageErrorText.NotAnImage
        }
        PageErrorCard(text, listOf(PageErrorAction.Details))
    }
}
