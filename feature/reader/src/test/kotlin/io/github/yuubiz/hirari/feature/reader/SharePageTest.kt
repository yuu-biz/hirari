package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.HalfPart
import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderCommand
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharePageTest {
    @Test
    fun aPairAsksWhichPageAndAnyOtherViewSharesItsPage() {
        assertEquals(CommandEffect.SharePage, ReaderConfig().effectOf(ReaderCommand.SharePage))
        assertEquals(CommandEffect.Show(ReaderSheet.SharePick(PageView.Two(4, 5))), shareChoice(PageView.Two(4, 5)))
        assertEquals(CommandEffect.Share(3), shareChoice(PageView.One(3)))
        assertEquals(CommandEffect.Share(7), shareChoice(PageView.Half(7, HalfPart.Second)))
    }

    @Test
    fun theNameIsTheLastPartOfTheEntryWithAnExtensionOfItsType() {
        assertEquals("012.jpg", shareFileName("vol1/012.jpg", ImageType.Jpeg, 11))
        assertEquals("012.JPEG", shareFileName("vol1\\012.JPEG", ImageType.Jpeg, 11))
        assertEquals("scan.dat.png", shareFileName("scan.dat", ImageType.Png, 0))
        assertEquals("cover.webp", shareFileName("cover", ImageType.WebP, 0))
        assertEquals("3.png", shareFileName("3", ImageType.PdfPage, 2))
        assertEquals("note.bin", shareFileName("note.bin", null, 0))
    }

    @Test
    fun theNameLeavesOutWhatFileSystemsRefuse() {
        assertEquals("a_b_c_.jpg", shareFileName("a:b*c?.jpg", ImageType.Jpeg, 0))
        assertEquals("hidden.png", shareFileName("..hidden.png", ImageType.Png, 0))
        assertEquals("page-5.gif", shareFileName("dir/", ImageType.Gif, 4))
        val long = shareFileName("x".repeat(300) + ".jpg", ImageType.Jpeg, 0)
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".jpg"), long)
    }

    @Test
    fun theMimeTypeIsTheTypeTheBytesShow() {
        assertEquals("image/jpeg", shareMimeType(ImageType.Jpeg))
        assertEquals("image/png", shareMimeType(ImageType.PdfPage))
        assertEquals("image/jxl", shareMimeType(ImageType.JpegXl))
        assertEquals("image/*", shareMimeType(null))
    }

    @Test
    fun aWrittenPageIsInTheShareFolderUntilItIsCleared() = runBlocking {
        val cache = Files.createTempDirectory("cache").toFile()
        val dir = File(cache, SHARE_DIR)
        val file = assertNotNull(SharedPageFiles.write(dir, "001.jpg") { it.write(byteArrayOf(1, 2, 3)) })
        assertEquals(dir, file.parentFile)
        assertEquals(listOf<Byte>(1, 2, 3), file.readBytes().toList())
        SharedPageFiles.clear(dir)
        assertFalse(dir.exists())
        assertTrue(cache.exists())
        cache.deleteRecursively()
        Unit
    }

    @Test
    fun aWriteThatFailsLeavesNoFileBehind() = runBlocking {
        val dir = Files.createTempDirectory("share").toFile()
        val written = SharedPageFiles.write(dir, "broken.png") { error("the page did not draw") }
        assertEquals(null, written)
        assertFalse(File(dir, "broken.png").exists())
        dir.deleteRecursively()
        Unit
    }
}
