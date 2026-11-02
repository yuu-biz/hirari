package io.github.yuubiz.hirari.core.data

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupRulesTest {
    private val backedUp = setOf(
        "file" to "datastore/$SETTINGS_FILE",
        "database" to MAIN_DATABASE,
        "database" to "$MAIN_DATABASE-wal",
    )

    /** The include rules under each section, or under the root for `full-backup-content`. */
    private fun includes(name: String): Map<String, Set<Pair<String, String>>> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$name")).documentElement
        val sections = root.childElements().filter { it.tagName != "include" && it.tagName != "exclude" }.ifEmpty { listOf(root) }
        return sections.associate { section ->
            assertEquals(emptyList(), section.childElements().filter { it.tagName == "exclude" }, "$name uses only include rules")
            section.tagName to section.childElements().map { it.getAttribute("domain") to it.getAttribute("path") }.toSet()
        }
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    @Test
    fun `every backup backs up the settings and the main database and nothing else`() {
        assertEquals(mapOf("cloud-backup" to backedUp, "device-transfer" to backedUp), includes("data_extraction_rules.xml"))
        assertEquals(mapOf("full-backup-content" to backedUp), includes("backup_rules.xml"))
    }
}
