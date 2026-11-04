package io.github.yuubiz.hirari.feature.library

import org.junit.Test
import kotlin.test.assertEquals

/** docs/spec.md section 4.3, 上部バー. */
class FolderTrailTest {
    @Test
    fun `a folder lists the place and each folder above it, the top first`() {
        assertEquals(
            FolderTrail("series", listOf(Ancestor("NAS", ""), Ancestor("manga", "manga"), Ancestor("normal", "manga/normal")), parent = "manga/normal"),
            folderTrail("NAS", "manga/normal/series"),
        )
    }

    @Test
    fun `a folder just below the top has the place as its parent`() {
        assertEquals(FolderTrail("manga", listOf(Ancestor("NAS", "")), parent = ""), folderTrail("NAS", "manga"))
    }

    @Test
    fun `the top of the place has nothing above`() {
        assertEquals(FolderTrail("NAS", emptyList(), parent = null), folderTrail("NAS", ""))
        assertEquals(FolderTrail("NAS", emptyList(), parent = null), folderTrail("NAS", "/"))
    }
}
