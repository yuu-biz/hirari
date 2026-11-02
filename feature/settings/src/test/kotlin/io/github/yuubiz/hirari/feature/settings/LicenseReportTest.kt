package io.github.yuubiz.hirari.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LicenseReportTest {
    private val json = """
        {"libraries":[
          {"id":"androidx.activity:activity","name":"Activity","version":"1.13.0","url":"https://example.org","licenses":["Apache-2.0"],"texts":["Apache-2.0"]},
          {"id":"third_party/libarchive","name":"libarchive","version":"3.8.1","url":null,"licenses":["BSD-2-Clause"],"texts":["third_party/libarchive"]}
        ],
        "texts":{"Apache-2.0":"Apache text","third_party/libarchive":"libarchive text"}}
    """.trimIndent()

    @Test
    fun parsesLibrariesWithTheirFullTexts() {
        val report = LicenseReport.parse(json)
        assertEquals(listOf("Activity", "libarchive"), report.libraries.map { it.name })
        assertEquals(listOf("libarchive text"), report.textsOf(report.libraries[1]))
    }

    @Test
    fun rejectsALibraryWhoseTextIsMissing() {
        val broken = json.replace("\"third_party/libarchive\":\"libarchive text\"", "\"other\":\"x\"")
        assertThrows(IllegalArgumentException::class.java) { LicenseReport.parse(broken) }
    }

    @Test
    fun rejectsARepeatedId() {
        val repeated = json.replace("\"id\":\"third_party/libarchive\"", "\"id\":\"androidx.activity:activity\"")
        assertThrows(IllegalArgumentException::class.java) { LicenseReport.parse(repeated) }
    }
}
