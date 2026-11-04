package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.EntryOrder
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.StartAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageOrderTest {
    private fun pages(vararg entries: Int) = entries.mapIndexed { i, entry -> PageEntry(i, entry, byteArrayOf(), "$entry.jpg", declaredSize = null, sniffed = null) }

    @Test
    fun reverseOrderIsACommandThatTheReaderStoresAndOpensTheBookAgainFor() {
        assertEquals(CommandEffect.ReverseOrder, ReaderConfig().effectOf(ReaderCommand.ReverseOrder))
    }

    @Test
    fun aChangeOfTheEntryOrderOpensAnArchiveAgainButNotAFolderOrAnyOtherChange() {
        val natural = GlobalSettings()
        val stored = natural.copy(entryOrder = EntryOrder.Stored)
        assertTrue(reorders(natural, stored, Container.Zip))
        assertTrue(reorders(stored, natural, Container.Pdf))
        assertFalse(reorders(natural, stored, Container.Folder))
        assertFalse(reorders(natural, natural.copy(layoutMode = LayoutMode.Single), Container.Zip))
    }

    @Test
    fun reversingTurnsTheOrderOnAndOffAndOffIsNoOverride() {
        val on = BookOverrides().reversedTo(true)
        assertEquals(true, on.reverseOrder)
        assertEquals(BookOverrides(), on.reversedTo(false))
    }

    @Test
    fun theBookOpensAgainOnThePageOnScreenWhereverTheNewOrderPutsIt() {
        val natural = pages(0, 1, 2, 3)
        val reversed = pages(3, 2, 1, 0)
        assertEquals(1, startPage(StartAt.Entry(1), natural, saved = null))
        assertEquals(2, startPage(StartAt.Entry(1), reversed, saved = null))
        assertEquals(0, startPage(StartAt.Entry(9), reversed, saved = null))
    }

    @Test
    fun theStripOpensAgainAsFarDownThePageAsItWasUnlessThePageIsGone() {
        val reversed = pages(3, 2, 1, 0)
        assertEquals(0.4f, startOffset(StartAt.Entry(1, 0.4f), startPage(StartAt.Entry(1, 0.4f), reversed, saved = null), reversed, saved = null))
        assertEquals(0f, startOffset(StartAt.Entry(9, 0.4f), startPage(StartAt.Entry(9, 0.4f), reversed, saved = null), reversed, saved = null))
        assertEquals(0.3f, startOffset(StartAt.Saved, 2, reversed, ReadingPosition.Image(2, 4, 0.3f)))
        assertEquals(0f, startOffset(StartAt.Page(2), 2, reversed, ReadingPosition.Image(2, 4, 0.3f)))
    }

    @Test
    fun theOtherStartsKeepTheirMeaning() {
        val book = pages(0, 1, 2)
        assertEquals(2, startPage(StartAt.Saved, book, ReadingPosition.Image(2, 3)))
        assertEquals(2, startPage(StartAt.Page(Int.MAX_VALUE), book, saved = null))
        assertEquals(1, startPage(StartAt.Image("1.jpg"), book, saved = null))
        assertEquals(0, startPage(StartAt.Saved, book, saved = null))
    }
}
