package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.feature.library.TextAddress
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PickedDocumentTest {
    private val uri = "content://books/document/picked"

    @Test
    fun pickerAcceptsEveryMimeTypeOfTheViewFilter() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val filters = manifest.getElementsByTagName("intent-filter")
        val viewTypes = buildSet {
            for (index in 0 until filters.length) {
                val filter = filters.item(index) as org.w3c.dom.Element
                val actions = filter.getElementsByTagName("action")
                if ((0 until actions.length).none {
                        (actions.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") == "android.intent.action.VIEW"
                    }) continue
                val data = filter.getElementsByTagName("data")
                for (item in 0 until data.length) {
                    val type = (data.item(item) as org.w3c.dom.Element).getAttributeNS(android, "mimeType")
                    if (type.isNotEmpty()) add(type)
                }
            }
        }
        assertEquals(viewTypes, DOCUMENT_TYPES.toSet())
        for (type in listOf("application/pdf", "text/plain", "application/vnd.rar")) {
            org.junit.Assert.assertTrue(type, type in DOCUMENT_TYPES)
        }
    }

    @Test
    fun pickedTextOpensTheTextRouteRegardlessOfTheProvidersMimeType() {
        assertEquals(
            TextAddress.Document(UriString(uri)).toRoute(),
            IncomingRoute(uri).readerRoute("吾輩は猫である。".toByteArray()),
        )
    }

    @Test
    fun pickedZipOpensTheImageRoute() {
        assertEquals(
            OpenBookRequest(BookAddress.Document(UriString(uri))).toReaderRoute(),
            IncomingRoute(uri).readerRoute(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)),
        )
    }

    @Test
    fun textLookingPrefixWithZipEndRecordOpensTheImageRoute() {
        val head = ByteArray(4096) { 'a'.code.toByte() }
        // EOCD followed by the maximum 65,535-byte comment: its signature is at the tail window's start.
        val tail = ByteArray(22 + 65535) { 'c'.code.toByte() }.also {
            ByteArray(22).copyInto(it)
            byteArrayOf(0x50, 0x4B, 0x05, 0x06).copyInto(it)
            it[20] = 0xFF.toByte()
            it[21] = 0xFF.toByte()
        }
        assertEquals(
            OpenBookRequest(BookAddress.Document(UriString(uri))).toReaderRoute(),
            IncomingRoute(uri).readerRoute(head, tail),
        )
    }

    @Test
    fun textWithoutZipEndRecordStillOpensTheTextRoute() {
        val head = ByteArray(4096) { 'a'.code.toByte() }
        val expected = TextAddress.Document(UriString(uri)).toRoute()
        assertEquals(expected, IncomingRoute(uri).readerRoute(head))
        assertEquals(expected, IncomingRoute(uri).readerRoute(head, "plain text tail".toByteArray()))
        assertEquals(expected, IncomingRoute(uri).readerRoute(head, byteArrayOf(0x50, 0x4B, 0x05)))
    }

    @Test
    fun unknownOrUnreadablePickedDocumentsUseTheIncomingImageReaderErrorPath() {
        val expected = OpenBookRequest(BookAddress.Document(UriString(uri))).toReaderRoute()
        assertEquals(expected, IncomingRoute(uri).readerRoute(byteArrayOf(1, 0, 2)))
        assertEquals(expected, IncomingRoute(uri).readerRoute(null))
    }
}
