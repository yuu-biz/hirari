package io.github.yuubiz.hirari.core.data

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "fonts") }
    private val store by lazy { FontStore(dir) }

    private fun font(magic: String, size: Int = 200_000): ByteArray =
        (if (magic == "ttf") byteArrayOf(0, 1, 0, 0) else magic.toByteArray()) + ByteArray(size) { it.toByte() }

    @Test
    fun `TrueType, OpenType and collections are imported byte for byte under their name`() {
        for ((magic, name) in listOf("ttf" to "Mincho.ttf", "true" to "Old.ttf", "OTTO" to "Serif.otf", "ttcf" to "Set.ttc")) {
            val bytes = font(magic)
            assertEquals(FontImport.Imported(name), store.import(name, bytes.inputStream()))
            assertTrue(bytes.contentEquals(store.file(name)!!.readBytes()), name)
        }
        assertEquals(listOf("Mincho.ttf", "Old.ttf", "Serif.otf", "Set.ttc"), store.list())
    }

    @Test
    fun `data that is not a font is refused and leaves nothing behind`() {
        assertEquals(FontImport.NotAFont, store.import("notes.ttf", "hello world".byteInputStream()))
        assertEquals(FontImport.NotAFont, store.import("empty.ttf", ByteArray(0).inputStream()))
        assertEquals(emptyList(), store.list())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a name already taken gets a number before its extension`() {
        repeat(3) { store.import("Mincho.ttf", font("ttf").inputStream()) }
        assertEquals(setOf("Mincho.ttf", "Mincho (2).ttf", "Mincho (3).ttf"), store.list().toSet())
    }

    @Test
    fun `a provider name keeps only its last segment and never names a hidden or outside file`() {
        assertEquals(FontImport.Imported("evil.ttf"), store.import("../../evil.ttf", font("ttf").inputStream()))
        assertEquals(FontImport.Imported("hidden.otf"), store.import(".hidden.otf", font("OTTO").inputStream()))
        assertEquals(FontImport.Imported("font"), store.import("dir/", font("OTTO").inputStream()))
        assertNull(store.file("../fonts/evil.ttf"))
        assertNull(store.file(".partial-x"))
        assertFalse(File(folder.root, "evil.ttf").exists())
    }

    @Test
    fun `a deleted font is gone from the list and the files`() {
        store.import("Gothic.otf", font("OTTO").inputStream())
        assertTrue(store.delete("Gothic.otf"))
        assertFalse(store.delete("Gothic.otf"))
        assertNull(store.file("Gothic.otf"))
        assertEquals(emptyList(), store.list())
    }
}
