package io.github.yuubiz.hirari.core.model

enum class ImageType { Jpeg, Png, WebP, Gif, Bmp, Heif, Avif, JpegXl, PdfPage }

data class PageEntry(
    /** The display order, after sorting. */
    val index: Int,
    /** The order in which the archive stores the entry. */
    val entryIndex: Int,
    /** The name before charset detection. */
    val rawName: ByteArray,
    val name: String,
    /** The size the container declares, or null when it declares none. Only for the size limit. It may lie. */
    val declaredSize: Long?,
    /** `null` until the first bytes are read. */
    val sniffed: ImageType?,
) {
    override fun equals(other: Any?): Boolean =
        other is PageEntry &&
            index == other.index &&
            entryIndex == other.entryIndex &&
            rawName.contentEquals(other.rawName) &&
            name == other.name &&
            declaredSize == other.declaredSize &&
            sniffed == other.sniffed

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + entryIndex
        result = 31 * result + rawName.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (declaredSize?.hashCode() ?: 0)
        result = 31 * result + (sniffed?.hashCode() ?: 0)
        return result
    }
}

sealed interface PageShape {
    data object Unknown : PageShape

    data class Known(val width: Int, val height: Int) : PageShape {
        val isWide: Boolean get() = width > height
    }
}

internal val PageShape.isWide: Boolean get() = this is PageShape.Known && isWide
