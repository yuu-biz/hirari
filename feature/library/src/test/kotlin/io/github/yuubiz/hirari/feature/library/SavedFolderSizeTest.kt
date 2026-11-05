package io.github.yuubiz.hirari.feature.library

import android.os.Bundle
import android.os.Parcel
import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.LibrarySection
import io.github.yuubiz.hirari.core.model.LibrarySort
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SavedFolderSizeTest {
    @Test
    fun `measure legacy folder and two level route payloads`() {
        for (count in listOf(1_000, 5_000)) {
            val keys = keys(count)
            val legacy = arrayListOf<Any>(keys[count / 2], 12).apply { addAll(keys) }
            println("Legacy folder $count: ${bytes(Bundle().apply { putSerializable("anchor", legacy) })} bytes")
        }
        // The old route stored both sets of members as navigation StringList arguments.
        val route = routeArguments(twoLevels()).apply {
            putStringArrayList("groupMembers", ArrayList(keys(1_000)))
            putStringArrayList("parentGroupMembers", ArrayList(keys(1_000)))
        }
        println("Legacy two-level route, 1000 members per level: ${bytes(route)} bytes")
    }

    @Test
    fun `saved anchor and two route argument bundles stay below 50 KB`() {
        for (count in listOf(1_000, 5_000)) {
            val rows = keys(count).map { FolderEntry(it, null, false, 1, null) }
                .toRows("", Ordering(LibrarySort.Name, false, false))
            val cells = prepareFileCells(rows, LibrarySection.None, false, emptySet())
            val saved = ArrayList(cells.saveAnchor(listOf(count / 2 to -12)))
            assertEquals(2 + 101, saved.size, "the anchor and 50 keys on each side")
            val size = bytes(Bundle().apply { putSerializable("anchor", saved) })
            println("Saved folder $count: $size bytes")
            assertTrue(size < 50_000, "$count entries saved $size bytes")
            val child = twoLevels()
            val route = routeArguments(child)
            println("Metadata-only two-level route: ${bytes(route)} bytes")
            val combined = bytes(Bundle().apply {
                putSerializable("anchor", saved)
                putBundle("firstRoute", routeArguments(child.parent!!))
                putBundle("secondRoute", route)
            })
            println("Saved folder $count and two routes: $combined bytes")
            assertTrue(combined < 50_000, "$count entries and two routes saved $combined bytes")
        }
    }

    private fun twoLevels(): PseudoFolder {
        val members = keys(1_000)
        val parent = PseudoFolder("/group:Author:a", "A", LibraryGrouping.Author, true, "keep", memberKeys = members)
        return PseudoFolder("/group-child:${parent.key.length}:${parent.key}/group:Series:story", "Story",
            LibraryGrouping.Series, false, "Story", parent, members)
    }

    // FolderRoute's flattened navigation arguments. FolderRouteTest independently verifies that the actual
    // serialized route contains only this metadata and is identical for 1 and 1,000 members at both levels.
    // This measures the payload, not the framework's entire activity/back-stack saved state.
    private fun routeArguments(folder: PseudoFolder) = Bundle().apply {
        putLong("location", 1)
        putString("path", "series")
        putString("documentId", null)
        putString("reveal", null)
        fun group(prefix: String, value: PseudoFolder?) {
            putString("${prefix}Key", value?.key)
            putString("${prefix}Name", value?.name.orEmpty())
            putString("${prefix}Kind", (value?.kind ?: LibraryGrouping.None).name)
            putBoolean("${prefix}Merge", value?.merge ?: true)
            putString("${prefix}Filter", value?.filter.orEmpty())
        }
        group("group", folder)
        group("parentGroup", folder.parent)
    }

    private fun keys(count: Int) = List(count) { it.toString().padStart(76, 'x') + ".zip" }

    private fun bytes(bundle: Bundle): Int {
        val parcel = Parcel.obtain()
        return try {
            bundle.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }
}
