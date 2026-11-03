package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.image.thumbnail.ThumbRequest
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbResult
import io.github.yuubiz.hirari.core.image.thumbnail.Thumbnails
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * The part of [Thumbnails] the library uses (docs/spec.md section 4.4). `Thumbnails` is a final class made with
 * the disk cache and the decoder of the device, so the screens work against this and their tests fake it.
 */
interface ThumbnailSource {
    /** The result for [request], then the thumbnail held against eviction until the collector is cancelled. */
    fun observe(request: ThumbRequest): Flow<ThumbResult>

    /** Holds the thumbnail against eviction until the result is closed, without looking at the store or the files. */
    fun hold(request: ThumbRequest): Closeable

    /** サムネイルを作り直す: forgets and makes again. */
    suspend fun regenerate(request: ThumbRequest): ThumbResult
}

class ThumbnailsSource(private val thumbnails: Thumbnails) : ThumbnailSource {
    override fun observe(request: ThumbRequest): Flow<ThumbResult> = thumbnails.observe(request)

    override fun hold(request: ThumbRequest): Closeable = thumbnails.hold(request)

    override suspend fun regenerate(request: ThumbRequest): ThumbResult = thumbnails.regenerate(request)
}
