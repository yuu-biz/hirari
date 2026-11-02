package io.github.yuubiz.hirari

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingTest {
    private val cbz = "content://com.android.externalstorage.documents/document/primary%3ABooks%2Fvol1.cbz"

    @Test
    fun aViewOrASendOfAContentUriOpensThatDocument() {
        assertEquals(IncomingRoute(cbz), incoming("android.intent.action.VIEW", cbz, null, null, null, false)?.toRoute())
        assertEquals(IncomingRoute(cbz), incoming("android.intent.action.SEND", null, Stream.Uri(cbz), null, null, false)?.toRoute())
    }

    @Test
    fun aLauncherStartAFileUriOrASendWithoutAStreamAsksForNothing() {
        assertNull(incoming("android.intent.action.MAIN", null, null, null, null, false))
        assertNull(incoming("android.intent.action.VIEW", "file:///sdcard/vol1.cbz", null, null, null, false))
        assertNull(incoming("android.intent.action.SEND", null, null, null, null, false))
    }

    @Test
    fun aSendWhoseStreamIsNoUriOpensHomeWithANotice() {
        assertEquals(Incoming.Unreadable, incoming("android.intent.action.SEND", null, Stream.NotAUri, null, null, false))
        assertEquals(HomeRoute, Incoming.Unreadable.toRoute())
    }

    @Test
    fun aShortcutOpensItsPlaceAndPathWithoutAnythingElse() {
        assertEquals(ShortcutRoute(3, "漫画/作品", folder = true), incoming(ACTION_OPEN_PLACE, null, null, 3, "漫画/作品", true)?.toRoute())
        assertEquals(ShortcutRoute(3, "漫画/1巻.zip", folder = false), incoming(ACTION_OPEN_PLACE, null, null, 3, "漫画/1巻.zip", false)?.toRoute())
        assertNull(incoming(ACTION_OPEN_PLACE, null, null, null, "漫画", true))
    }

    @Test
    fun theContentDecidesBetweenTheImageReaderAndTheTextReader() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        assertEquals(IncomingKind.Images, incomingKind(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
        assertEquals(IncomingKind.Images, incomingKind(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)))
        assertEquals(IncomingKind.Images, incomingKind(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)))
        assertEquals(IncomingKind.Images, incomingKind("%PDF-1.7\n%âãÏÓ\n1 0 obj".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(IncomingKind.Images, incomingKind(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(IncomingKind.Images, incomingKind(ByteArray(300).also { "ustar".toByteArray().copyInto(it, 257) }))
        assertEquals(IncomingKind.Text, incomingKind("吾輩は猫である。".toByteArray(charset("windows-31j"))))
        assertEquals(IncomingKind.Text, incomingKind("吾輩は猫である。".toByteArray(Charsets.UTF_8)))
        assertEquals(IncomingKind.Text, incomingKind(bytes(0xFF, 0xFE) + "猫".toByteArray(Charsets.UTF_16LE)))
        assertEquals(IncomingKind.Images, incomingKind(bytes(0x01, 0x00, 0x02)))
    }
}
