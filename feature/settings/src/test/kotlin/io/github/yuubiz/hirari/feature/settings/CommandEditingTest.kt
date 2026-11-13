package io.github.yuubiz.hirari.feature.settings

import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReaderCommand.AddBookmark
import io.github.yuubiz.hirari.core.model.ReaderCommand.ScaleMode
import io.github.yuubiz.hirari.core.model.ReaderCommand.ShiftPairing
import io.github.yuubiz.hirari.core.model.ReaderCommand.Trim
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandEditingTest {
    private val slots = listOf(ShiftPairing, ScaleMode, AddBookmark)

    @Test
    fun `a slot takes a command or nothing and leaves the other slots`() {
        assertEquals(listOf(ShiftPairing, Trim, AddBookmark), slots.withSlot(1, Trim))
        assertEquals(listOf(null, ScaleMode, AddBookmark), slots.withSlot(0, null))
        assertEquals(listOf(ShiftPairing, ScaleMode, ShiftPairing), slots.withSlot(2, ShiftPairing))
    }

    @Test
    fun `the slots offer every command but the fixed view, once each`() {
        val row = SettingsTable.filterIsInstance<SlotsSetting>().single()
        val commands = row.choices.map { it.value }
        assertEquals(commands.size, commands.toSet().size)
        assertEquals(ReaderCommand.entries.toSet() - ReaderCommand.BookSettings, commands.toSet())
    }
}
