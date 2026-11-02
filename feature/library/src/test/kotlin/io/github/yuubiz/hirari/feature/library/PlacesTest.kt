package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Adding and removing SAF places from the shelf (docs/spec.md sections 4.2, 4.5 and 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlacesTest {
    private val fixture = Fixture()
    private val tree = UriString("content://com.android.externalstorage.documents/tree/primary%3AManga")

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun viewModel() = HomeViewModel(CompletableDeferred(fixture.services))

    @Test
    fun `a picked folder becomes a place under its own name and keeps its grant`() = runTest {
        fixture.grants.names[tree] = "漫画"
        viewModel().addTree(tree)

        assertEquals(listOf<LibraryLocation>(LibraryLocation.SafTree(LocationId(1), "漫画", tree)), fixture.locations.rows.value)
        assertEquals(setOf(tree), fixture.grants.held)
    }

    @Test
    fun `picking a registered folder again gives its place the grant back instead of adding it twice`() = runTest {
        fixture.locations.rows.value = listOf(LibraryLocation.SafTree(LocationId(7), "漫画", tree, permissionHeld = false))
        viewModel().addTree(tree)

        assertEquals(listOf<LibraryLocation>(LibraryLocation.SafTree(LocationId(7), "漫画", tree, permissionHeld = true)), fixture.locations.rows.value)
    }

    @Test
    fun `a refused grant adds nothing and says so`() = runTest {
        fixture.grants.refuse = true
        val home = viewModel()
        watch(home.state)
        home.addTree(tree)

        assertTrue(fixture.locations.rows.value.isEmpty())
        assertEquals(Notice.GrantRefused, home.state.value.notice)
    }

    @Test
    fun `removing a place releases its grant but keeps the reading state`() = runTest {
        val place = LibraryLocation.SafTree(LocationId(1), "漫画", tree)
        val read = book(place.id, "作品/1巻.zip")
        fixture.grants.held += tree
        fixture.locations.rows.value = listOf(place)
        fixture.books.put(reading(read, viewStart = 3))
        viewModel().removePlace(place.id)

        assertTrue(fixture.locations.rows.value.isEmpty())
        assertTrue(fixture.grants.held.isEmpty())
        assertEquals(3, (fixture.books.progress.value[read.id]?.position as? ReadingPosition.Image)?.viewStart)
    }

    @Test
    fun `renaming a place trims the name`() = runTest {
        fixture.locations.rows.value = listOf(LibraryLocation.SafTree(LocationId(1), "Manga", tree))
        viewModel().renamePlace(LocationId(1), "  漫画  ")

        assertEquals("漫画", fixture.locations.rows.value.single().displayName)
    }
}
