package io.github.yuubiz.hirari.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 並べ替えの帯 of docs/spec.md section 4.3 and its setting in section 4.10. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w240dp-h891dp")
class SortStripTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))

    private fun look(grouping: LibraryGrouping = LibraryGrouping.None, subgrouping: LibraryGrouping = LibraryGrouping.None) =
        GlobalSettings(libraryGrouping = grouping, librarySubgrouping = subgrouping).listLook()

    private fun group(kind: LibraryGrouping, parent: PseudoFolder? = null) = PseudoFolder("k", "n", kind, merge = true, filter = "", parent = parent)

    @Test
    fun `a real folder offers all five group values and the first level setting`() {
        val strip = sortStrip(look(LibraryGrouping.Series), LibrarySort.Newest, null)
        val group = assertNotNull(strip.group)
        assertEquals(LibrarySort.Newest, strip.sort)
        assertEquals(StripLevel.First, group.level)
        assertEquals(LibraryGrouping.entries, group.choices)
        assertEquals(LibraryGrouping.Series, group.chosen)
    }

    @Test
    fun `inside a first level group the second level setting is offered without the first level value`() {
        val look = look(LibraryGrouping.Author, LibraryGrouping.Event)
        val group = assertNotNull(sortStrip(look, LibrarySort.Name, group(LibraryGrouping.Author)).group)
        assertEquals(StripLevel.Second, group.level)
        assertEquals(listOf(LibraryGrouping.None, LibraryGrouping.Series, LibraryGrouping.Original, LibraryGrouping.Event), group.choices)
        assertEquals(LibraryGrouping.Event, group.chosen)
    }

    @Test
    fun `inside a first level group with no second level the none chip is chosen`() {
        val group = assertNotNull(sortStrip(look(LibraryGrouping.Author), LibrarySort.Name, group(LibraryGrouping.Author)).group)
        assertEquals(LibraryGrouping.None, group.chosen)
    }

    @Test
    fun `inside a second level group there are no group chips`() {
        val inner = group(LibraryGrouping.Series, parent = group(LibraryGrouping.Author))
        assertNull(sortStrip(look(LibraryGrouping.Author, LibraryGrouping.Series), LibrarySort.Name, inner).group)
    }

    @Test
    fun `choosing a group value changes the setting of its level and nothing else`() {
        val first = StripGroup(StripLevel.First, LibraryGrouping.entries, LibraryGrouping.None).choose(LibraryGrouping.Event)(GlobalSettings())
        assertEquals(GlobalSettings(libraryGrouping = LibraryGrouping.Event), first)
        val second = StripGroup(StripLevel.Second, LibraryGrouping.entries, LibraryGrouping.None).choose(LibraryGrouping.Series)(GlobalSettings())
        assertEquals(GlobalSettings(librarySubgrouping = LibraryGrouping.Series), second)
    }

    private fun show(settings: GlobalSettings) {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["a"] = listOf(FolderEntry("1巻.zip", "doc:1", directory = false, size = 1, modified = null))
        fixture.settings.value.value = settings
        val model = FolderViewModel(CompletableDeferred(fixture.services), FolderKey(place.id, "a"), Dispatchers.Unconfined)
        val actions = FolderActions(openBook = {}, openText = {}, openArchive = {}, openFolder = {}, pinShortcut = { true }, openAncestor = {}, close = {})
        rule.setContent { MaterialTheme { FolderScreen(model, actions) } }
        rule.waitUntil { model.state.value.content is FolderContent.Shown }
    }

    private fun stored() = fixture.settings.value.value

    private fun stateOf(description: String) = rule.onNodeWithContentDescription(description).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun `a sort chip saves the sort and reads its kind, value and selection`() {
        show(GlobalSettings())
        rule.onNodeWithContentDescription("並べ替え、名前").assertIsDisplayed()
        assertEquals("選択中", stateOf("並べ替え、名前"))
        assertNull(stateOf("並べ替え、新しい順"))
        rule.onNodeWithContentDescription("並べ替え、新しい順").performClick()
        rule.waitUntil { stored().librarySort == LibrarySort.Newest }
        rule.waitUntil { stateOf("並べ替え、新しい順") == "選択中" }
        assertNull(stateOf("並べ替え、名前"))
    }

    @Test
    fun `a group chip saves the group`() {
        show(GlobalSettings())
        rule.onNodeWithContentDescription("グループ、作者").performScrollTo().performClick()
        rule.waitUntil { stored().libraryGrouping == LibraryGrouping.Author }
        assertEquals(GlobalSettings(libraryGrouping = LibraryGrouping.Author), stored())
    }

    @Test
    fun `the chosen group chip is in view when the strip opens narrow`() {
        show(GlobalSettings(libraryGrouping = LibraryGrouping.Event))
        rule.onNodeWithContentDescription("グループ、イベント").assertIsDisplayed()
    }

    @Test
    fun `the setting hides the strip`() {
        show(GlobalSettings(showSortStrip = false))
        assertTrue(rule.onAllNodes(hasContentDescription("並べ替え、名前")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasContentDescription("グループ、作者")).fetchSemanticsNodes().isEmpty())
    }
}
