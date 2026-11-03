package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.ThumbKey
import io.github.yuubiz.hirari.core.data.ThumbLookup
import io.github.yuubiz.hirari.core.data.ThumbnailCache
import io.github.yuubiz.hirari.core.image.thumbnail.StoredThumb
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbnailKey
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbnailStore
import java.io.Closeable
import java.io.File

/**
 * The thumbnail disk cache of `:core:data` as the port `:core:image` makes thumbnails against (docs/spec.md
 * section 8.2). The keys have the same fields, so each is mapped one to one; a folder's `coverEntry` of -2 passes
 * through to [ThumbKey.digest] as it is.
 */
internal class ThumbnailStoreAdapter(private val cache: ThumbnailCache) : ThumbnailStore {
    override suspend fun get(key: ThumbnailKey): StoredThumb = when (val found = cache.get(key.toCacheKey())) {
        is ThumbLookup.Hit -> StoredThumb.Hit(found.file)
        ThumbLookup.Failed -> StoredThumb.Failed
        ThumbLookup.Miss -> StoredThumb.Miss
    }

    override suspend fun put(key: ThumbnailKey, webp: ByteArray): File = cache.put(key.toCacheKey(), webp)

    override suspend fun markFailed(key: ThumbnailKey) = cache.markFailed(key.toCacheKey())

    override suspend fun remove(key: ThumbnailKey) = cache.remove(key.toCacheKey())

    override fun hold(key: ThumbnailKey): Closeable = cache.hold(key.toCacheKey())
}

internal fun ThumbnailKey.toCacheKey() = ThumbKey(book, modified, byteSize, coverEntry, sizePx, direction)
