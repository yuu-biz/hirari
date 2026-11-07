package io.github.yuubiz.hirari.core.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileEditsTest {
    @Test fun `names keep file extensions and treat directory dots and dotfiles as names`() {
        assertEquals(EditableName("book", ".CBZ"), EditableName.of("book.CBZ", false))
        assertEquals("next.CBZ", EditableName.of("book.CBZ", false).fullName("next"))
        assertEquals(EditableName("folder.v2", ""), EditableName.of("folder.v2", true))
        assertEquals(EditableName(".hidden", ""), EditableName.of(".hidden", false))
        assertEquals(EditableName("a.tar", ".gz"), EditableName.of("a.tar.gz", false))
    }

    @Test fun `empty names forbidden separators and every control character are refused`() {
        for (name in listOf("", " ", ".", "..") + "/\\:*?\"<>|".map { "a${it}b" } +
            (0..159).filter { it.toChar().isISOControl() }.map { "a${it.toChar()}b" }) assertFalse(validFileStem(name), name)
        assertTrue(validFileStem("第 1 巻.改訂"))
    }

    @Test fun `descendant counting includes folders and stops at one thousand without further listings`() = runTest {
        val listed = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        val total = countDescendants("a", { path ->
            listed += path
            if (path == "a") listOf("a/folder" to true, "a/file" to false)
            else (1..1200).map { "a/folder/$it" to false }
        }, counts::add)
        assertEquals(1000, total)
        assertEquals(listOf("a", "a/folder"), listed)
        assertEquals((1..1000).toList(), counts)
        assertEquals(0, countDescendants("empty", { emptyList() }, {}))
    }

    @Test fun `cancelling counting propagates and stops further traversal`() = runTest {
        var listings = 0
        assertFailsWith<CancellationException> {
            countDescendants("root", { listings++; throw CancellationException() }, {})
        }
        assertEquals(1, listings)
    }
}
