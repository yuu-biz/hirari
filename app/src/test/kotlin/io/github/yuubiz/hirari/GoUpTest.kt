package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.feature.library.FolderKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/spec.md section 4.3: 上へ, the parent item and the title's menu go up to the folder's real parent. */
class GoUpTest {
    @Test
    fun pseudoFolderRouteRoundTripsAndUpReusesItsParentsBackStackEntry() {
        val parent = FolderRoute(1, "manga", documentId = "doc:manga")
        val group = io.github.yuubiz.hirari.feature.library.PseudoFolder("/group:Series:title", "Title",
            io.github.yuubiz.hirari.core.model.LibraryGrouping.Series, true, "keep")
        val key = parent.toKey().copy(pseudo = group)
        assertEquals(key, key.toRoute().toKey())
        stack += listOf(null, parent, key.toRoute())
        up(to = "manga", from = "manga")
        assertEquals(listOf(null, parent), stack)
        org.junit.Assert.assertSame(parent, stack.last())
    }

    /** The back stack, bottom first; `null` stands for a screen that is not a file list, such as home. */
    private val stack = mutableListOf<FolderRoute?>()

    private fun up(to: String, from: String, location: Long = 1) =
        goUp(FolderKey(LocationId(location), to), from, top = { stack.lastOrNull() }, pop = { stack.removeAt(stack.lastIndex) }, open = { stack += it })

    @Test
    fun upFromAPinnedDeepFolderOpensItsRealParentAtTheFolderLeft() {
        stack += listOf(null, FolderRoute(1, "manga/normal/series"))
        up(to = "manga/normal", from = "manga/normal/series")
        assertEquals(listOf(null, FolderRoute(1, "manga/normal", reveal = "manga/normal/series")), stack)
        up(to = "manga", from = "manga/normal")
        assertEquals(listOf(null, FolderRoute(1, "manga", reveal = "manga/normal")), stack)
    }

    @Test
    fun upToAFolderPassedOnTheWayDownGoesBackToItWithItsScroll() {
        stack += listOf(null, FolderRoute(1, ""), FolderRoute(1, "manga"), FolderRoute(1, "manga/normal"), FolderRoute(1, "manga/normal/series"))
        up(to = "manga", from = "manga/normal/series")
        assertEquals(listOf(null, FolderRoute(1, ""), FolderRoute(1, "manga")), stack)
    }

    @Test
    fun upFromAFolderOfAnotherPlaceOnTopOpensTheParentAboveIt() {
        stack += listOf(null, FolderRoute(2, "a"), FolderRoute(1, "x/y"))
        up(to = "x", from = "x/y")
        assertEquals(listOf(null, FolderRoute(2, "a"), FolderRoute(1, "x", reveal = "x/y")), stack)
    }

    @Test
    fun upToTheTopOfThePlaceOpensItAtTheFolderLeft() {
        stack += listOf(null, FolderRoute(1, "series"))
        up(to = "", from = "series")
        assertEquals(listOf(null, FolderRoute(1, "", reveal = "series")), stack)
    }
}
