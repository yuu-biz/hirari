package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.feature.library.FolderKey
import io.github.yuubiz.hirari.feature.library.PseudoFolder
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderRouteTest {
    @Test
    fun pseudoFolderMembershipSurvivesNavigationSerializationAtBothLevels() {
        val parent = PseudoFolder("/group:Author:b", "Z (B)", LibraryGrouping.Author, true, "",
            memberKeys = listOf("series/[Z (B)] one.zip", "series/[B] two.zip", "series/[B] other.zip"))
        val child = PseudoFolder("/group-child:author:series", "Story", LibraryGrouping.Series, true, "Story", parent,
            parent.memberKeys.take(2))
        for (pseudo in listOf(parent, child)) {
            val key = FolderKey(LocationId(1), "series", pseudo = pseudo)
            val route = key.toRoute()
            val restored = Json.decodeFromString<FolderRoute>(Json.encodeToString(FolderRoute.serializer(), route))
            assertEquals(key, restored.toKey())
        }
    }
}
