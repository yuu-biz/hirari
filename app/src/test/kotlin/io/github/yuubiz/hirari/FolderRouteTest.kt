package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.feature.library.FolderKey
import io.github.yuubiz.hirari.feature.library.PseudoFolder
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderRouteTest {
    @Test
    fun pseudoFolderConditionsSurviveNavigationSerializationWithoutMembershipAtBothLevels() {
        val parent = PseudoFolder("/group:Author:b", "Z (B)", LibraryGrouping.Author, true, "",
            memberKeys = listOf("series/[Z (B)] one.zip", "series/[B] two.zip", "series/[B] other.zip"))
        val child = PseudoFolder("/group-child:author:series", "Story", LibraryGrouping.Series, true, "Story", parent,
            parent.memberKeys.take(2))
        for (pseudo in listOf(parent, child)) {
            val key = FolderKey(LocationId(1), "series", pseudo = pseudo)
            val route = key.toRoute()
            val restored = Json.decodeFromString<FolderRoute>(Json.encodeToString(FolderRoute.serializer(), route))
            val metadata = pseudo.copy(memberKeys = emptyList(), parent = pseudo.parent?.copy(memberKeys = emptyList()))
            assertEquals(key.copy(pseudo = metadata), restored.toKey())
        }
    }

    @Test
    fun routeSizeDoesNotGrowWithEitherLevelsMembership() {
        fun routes(count: Int): List<String> {
            val members = List(count) { it.toString().padStart(80, 'x') }
            val parent = PseudoFolder("/group:Author:a", "A", LibraryGrouping.Author, true, "keep", memberKeys = members)
            val child = PseudoFolder("/group-child:${parent.key.length}:${parent.key}/group:Series:story", "Story",
                LibraryGrouping.Series, false, "Story", parent, members)
            return listOf(parent, child).map {
                Json { encodeDefaults = true }.encodeToString(FolderRoute.serializer(), FolderKey(LocationId(1), "series", pseudo = it).toRoute())
            }
        }
        val small = routes(1)
        val large = routes(1_000)
        assertEquals(small, large)
        large.forEach {
            assertFalse(it.contains("Members"))
            assertTrue(it.toByteArray(Charsets.UTF_8).size < 2_000)
        }
    }
}
