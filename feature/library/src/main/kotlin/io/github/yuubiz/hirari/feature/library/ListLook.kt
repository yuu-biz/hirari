package io.github.yuubiz.hirari.feature.library

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.LibrarySection
import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.CoverAspect
import io.github.yuubiz.hirari.core.model.LibraryTextSize
import io.github.yuubiz.hirari.core.model.ThumbSize

/**
 * How the file list lays out its items (docs/spec.md sections 4.3 and 4.10, ライブラリ). [portraitColumns] and
 * [landscapeColumns] are the grid's columns in a window of that orientation, or `null` to fit cells of [thumbSize].
 */
data class ListLook(
    val thumbSize: ThumbSize = ThumbSize.Medium,
    val portraitColumns: Int? = null,
    val landscapeColumns: Int? = null,
    val nameLines: Int = 2,
    val noteLine: Boolean = true,
    val aspect: CoverAspect = CoverAspect.Book,
    val textSize: LibraryTextSize = LibraryTextSize.Medium,
    val grouping: LibraryGrouping = LibraryGrouping.None,
    val mergeCircleAuthors: Boolean = true,
    val section: LibrarySection = LibrarySection.None,
) {
    private fun fixedColumns(landscape: Boolean): Int? = if (landscape) landscapeColumns else portraitColumns

    /** The columns of a grid [width] wide in a window of that orientation (グリッドの列数). */
    fun columns(width: Dp, landscape: Boolean): Int = fixedColumns(landscape) ?: gridColumns(width, thumbSize)

    /**
     * The size whose thumbnails a grid with cells [cellWidth] wide shows (docs/spec.md section 5.4, 大きさ): that of
     * [thumbSize] when the columns fit its cells, or else the smallest whose long side covers the cover box, and
     * the largest when none does.
     */
    fun gridThumbSize(cellWidth: Dp, landscape: Boolean): ThumbSize {
        if (fixedColumns(landscape) == null) return thumbSize
        val box = (cellWidth - CELL_PADDING * 2) / aspect.ratio
        return ThumbSize.entries.firstOrNull { it.coverLongSide >= box } ?: ThumbSize.Large
    }
}

/** The padding around a grid cell's cover and name. */
internal val CELL_PADDING = 6.dp

fun GlobalSettings.listLook() = ListLook(
    thumbSize, gridColumnsPortrait, gridColumnsLandscape, gridNameLines, gridNoteLine, coverAspect, libraryTextSize, libraryGrouping, mergeCircleAuthors, librarySection,
)
