package io.github.yuubiz.hirari.core.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Layout snapshots per notation, with [FixedGlyphMetrics] on a 10 em square page. The image comparison of
 * docs/spec.md section 6.2 belongs to the screen; these pin what the engine hands it. Run the tests with
 * `-PupdateGoldens=true` to rewrite the `.layout` files after a deliberate change, then review the diff.
 */
class LayoutGoldenTest {
    private val update = System.getProperty("hirari.updateGoldens") == "true"

    private fun golden(name: String, params: LayoutParams = PARAMS) {
        val input = javaClass.getResource("/golden/$name.txt")!!.readText()
        val pages = VerticalLayout(parseAozora(input), params, FixedGlyphMetrics).pages().toList()
        val actual = dumpPages(pages, params.fontSize)
        val expectedFile = "golden/$name-${params.latinMode.name}.layout".let { if (params.latinMode == LatinMode.ShortTateChuYoko) "golden/$name.layout" else it }
        if (update) {
            File("src/test/resources/$expectedFile").writeText(actual)
            return
        }
        val expected = javaClass.getResource("/$expectedFile")?.readText() ?: error("$expectedFile is missing; run with -PupdateGoldens=true")
        assertEquals(expected, actual, name)
    }

    @Test fun ruby() = golden("ruby")
    @Test fun kinsoku() = golden("kinsoku")
    @Test fun indent() = golden("indent")
    @Test fun bottom() = golden("bottom")
    @Test fun heading() = golden("heading")
    @Test fun emphasis() = golden("emphasis")
    @Test fun pages() = golden("pages")
    @Test fun characters() = golden("characters")

    @Test
    fun latin() {
        for (mode in LatinMode.entries) golden("latin", PARAMS.copy(latinMode = mode))
    }

    private companion object {
        val PARAMS = LayoutParams(width = 100f, height = 100f, fontSize = 10f)
    }
}
