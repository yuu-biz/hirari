package io.github.yuubiz.hirari.feature.library

import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.CoverAspect
import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.ThumbSize
import org.junit.Test
import kotlin.test.assertEquals

/** docs/spec.md section 4.3, グリッドの列数, and section 5.4, 大きさ. */
class ListLookTest {
    @Test
    fun `grouping defaults to none and the look follows the saved author choice`() {
        assertEquals(LibraryGrouping.None, GlobalSettings().listLook().grouping)
        assertEquals(io.github.yuubiz.hirari.core.model.LibrarySection.None, GlobalSettings().listLook().section)
        assertEquals(io.github.yuubiz.hirari.core.model.LibrarySection.AddedTime,
            GlobalSettings(librarySection = io.github.yuubiz.hirari.core.model.LibrarySection.AddedTime).listLook().section)
        assertEquals(LibraryGrouping.Author, GlobalSettings(libraryGrouping = LibraryGrouping.Author).listLook().grouping)
        assertEquals(true, GlobalSettings().listLook().mergeCircleAuthors)
        assertEquals(false, GlobalSettings(mergeCircleAuthors = false).listLook().mergeCircleAuthors)
    }

    @Test
    fun `the default settings keep 4 columns on portrait phones from 360 to 449 dp`() {
        val look = GlobalSettings().listLook()
        for (width in listOf(360, 393, 411, 449)) assertEquals(4, look.columns(width.dp, landscape = false), "$width dp")
    }

    @Test
    fun `a chosen count holds at any width of its orientation, and the other orientation stays automatic`() {
        val look = ListLook(portraitColumns = 3)
        for (width in listOf(320, 412, 600)) assertEquals(3, look.columns(width.dp, landscape = false), "$width dp")
        assertEquals(8, look.columns(800.dp, landscape = true))
        assertEquals(12, ListLook(landscapeColumns = 12).columns(800.dp, landscape = true))
    }

    @Test
    fun `automatic columns show the thumbnails of the thumbnail size whatever the width`() {
        val look = ListLook(thumbSize = ThumbSize.Large)
        for (width in listOf(60, 130, 400)) assertEquals(ThumbSize.Large, look.gridThumbSize(width.dp, landscape = false))
    }

    @Test
    fun `chosen columns show the smallest thumbnails that cover the cell`() {
        val look = ListLook(portraitColumns = 4, landscapeColumns = 4)
        val table = listOf(
            60 to ThumbSize.Small,
            103 to ThumbSize.Small,
            137 to ThumbSize.Medium,
            150 to ThumbSize.Large,
            206 to ThumbSize.Large,
            400 to ThumbSize.Large,
        )
        for ((cell, size) in table) assertEquals(size, look.gridThumbSize(cell.dp, landscape = false), "$cell dp")
    }

    @Test
    fun `a square box needs smaller thumbnails than a book box of the same width`() {
        assertEquals(ThumbSize.Large, ListLook(portraitColumns = 3).gridThumbSize(150.dp, landscape = false))
        assertEquals(ThumbSize.Small, ListLook(portraitColumns = 3, aspect = CoverAspect.Square).gridThumbSize(150.dp, landscape = false))
    }

    @Test
    fun `out of range counts and lines are pulled into range when the settings are saved`() {
        val settings = GlobalSettings(gridColumnsPortrait = 9, gridColumnsLandscape = 1, gridNameLines = 7).normalized()
        assertEquals(6, settings.gridColumnsPortrait)
        assertEquals(2, settings.gridColumnsLandscape)
        assertEquals(3, settings.gridNameLines)
    }
}
