package io.github.yuubiz.hirari.feature.settings

import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReaderCommand.AutoPlay
import io.github.yuubiz.hirari.core.model.ReaderCommand.Layout
import io.github.yuubiz.hirari.core.model.ReaderCommand.Orientation
import io.github.yuubiz.hirari.core.model.ReaderCommand.ScaleMode
import io.github.yuubiz.hirari.core.model.ReaderCommand.Trim
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandEditingTest {
    private val items = listOf(Orientation, Trim, Layout)

    @Test
    fun `an item is added at the end, once, until the list is full`() {
        assertEquals(listOf(Orientation, Trim, Layout, ScaleMode), items.withCommand(ScaleMode, max = 8))
        assertEquals(items, items.withCommand(Trim, max = 8))
        assertEquals(items, items.withCommand(ScaleMode, max = 3))
    }

    @Test
    fun `an item is removed`() {
        assertEquals(listOf(Orientation, Layout), items.withoutCommand(Trim))
        assertEquals(items, items.withoutCommand(AutoPlay))
    }

    @Test
    fun `an item moves one place and stops at the ends`() {
        assertEquals(listOf(Trim, Orientation, Layout), items.movedCommand(Orientation, 1))
        assertEquals(listOf(Orientation, Layout, Trim), items.movedCommand(Layout, -1))
        assertEquals(items, items.movedCommand(Orientation, -1))
        assertEquals(items, items.movedCommand(Layout, 1))
        assertEquals(items, items.movedCommand(AutoPlay, 1))
    }

    @Test
    fun `the command list of the row holds each command once and no more than the spec allows`() {
        val row = SettingsTable.filterIsInstance<OrderedSetSetting>().single()
        val commands = row.choices.map { it.value }
        assertEquals(commands.size, commands.toSet().size)
        assertEquals(ReaderCommand.MAX_ITEMS, row.max)
        assertEquals(ReaderCommand.entries.toSet(), commands.toSet())
    }
}
