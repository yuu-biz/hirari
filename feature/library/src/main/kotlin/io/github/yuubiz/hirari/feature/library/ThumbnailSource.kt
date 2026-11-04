package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.image.thumbnail.PagePreview
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbRequest
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbSubject
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbResult
import io.github.yuubiz.hirari.core.image.thumbnail.Thumbnails
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * The part of [Thumbnails] the library uses (docs/spec.md section 4.4). `Thumbnails` is a final class made with
 * the disk cache and the decoder of the device, so the screens work against this and their tests fake it.
 */
interface ThumbnailSource {
    /**
     * The result for [request], held against eviction until cancellation. Observes the held state so resume also
     * retries unavailable rows that collect now or start collecting later.
     */
    fun observe(request: ThumbRequest): Flow<ThumbResult>

    /** Looks only in the disk store; never makes a cover or lists a folder. */
    suspend fun cached(request: ThumbRequest): ThumbResult?

    /** [observe] after the reason it could not be made passed: what failed for a passing reason is tried at once. */
    fun retry(request: ThumbRequest): Flow<ThumbResult>

    /** Holds the thumbnail against eviction until the result is closed, without looking at the store or the files. */
    fun hold(request: ThumbRequest): Closeable

    /** サムネイルを作り直す: forgets and makes again. */
    suspend fun regenerate(request: ThumbRequest): ThumbResult

    /** Whether no thumbnail is made, since the last process died making one (docs/spec.md section 4.4). */
    val held: Flow<Boolean>

    /** Retains that [resume] let thumbnails be made again, including for later collectors. */
    val resumptions: Flow<Unit>

    /** 表紙を作る: makes thumbnails again after [held]. */
    fun resume()

    /** One page of [book] shown whole and fitted into [maxSidePx], for choosing a range of it as the cover. Not stored. */
    suspend fun preview(book: ThumbSubject.Book, entryIndex: Int, maxSidePx: Int): PagePreview
}

class ThumbnailsSource(private val thumbnails: Thumbnails) : ThumbnailSource {
    override fun observe(request: ThumbRequest): Flow<ThumbResult> = thumbnails.observe(request)

    override suspend fun cached(request: ThumbRequest): ThumbResult? = thumbnails.cached(request)

    override fun retry(request: ThumbRequest): Flow<ThumbResult> = thumbnails.retry(request)

    override fun hold(request: ThumbRequest): Closeable = thumbnails.hold(request)

    override suspend fun regenerate(request: ThumbRequest): ThumbResult = thumbnails.regenerate(request)

    override val held: Flow<Boolean> get() = thumbnails.held

    override val resumptions: Flow<Unit> get() = thumbnails.resumptions

    override fun resume() = thumbnails.resume()

    override suspend fun preview(book: ThumbSubject.Book, entryIndex: Int, maxSidePx: Int): PagePreview =
        thumbnails.preview(book, entryIndex, maxSidePx)
}
