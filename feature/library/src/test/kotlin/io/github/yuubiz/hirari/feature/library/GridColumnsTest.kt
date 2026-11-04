package io.github.yuubiz.hirari.feature.library

import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.ThumbSize
import org.junit.Test
import kotlin.test.assertEquals

/** docs/spec.md section 4.3, グリッドの列数. */
class GridColumnsTest {
    @Test
    fun `portrait phones from 360 to 449 dp wide get 4 columns at the medium size`() {
        for (width in listOf(360, 393, 411, 443, 449)) {
            assertEquals(4, gridColumns(width.dp, ThumbSize.Medium), "$width dp")
        }
    }

    @Test
    fun `each size divides the width by its narrowest cell`() {
        val table = listOf(
            Triple(360, ThumbSize.Small, 5),
            Triple(412, ThumbSize.Small, 5),
            Triple(432, ThumbSize.Small, 6),
            Triple(450, ThumbSize.Medium, 5),
            Triple(800, ThumbSize.Medium, 8),
            Triple(360, ThumbSize.Large, 3),
            Triple(480, ThumbSize.Large, 4),
        )
        for ((width, size, columns) in table) assertEquals(columns, gridColumns(width.dp, size), "$width dp at $size")
    }

    @Test
    fun `a narrow window still gets 2 columns`() {
        assertEquals(2, gridColumns(200.dp, ThumbSize.Large))
        assertEquals(2, gridColumns(100.dp, ThumbSize.Medium))
        assertEquals(2, gridColumns(0.dp, ThumbSize.Small))
    }
}
