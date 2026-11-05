package io.github.yuubiz.hirari.feature.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.archive.EntryNames
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.entryBookId
import io.github.yuubiz.hirari.core.source.FileChannelSource
import io.github.yuubiz.hirari.core.text.CP932
import io.github.yuubiz.hirari.core.text.Figure
import io.github.yuubiz.hirari.core.text.PageContent
import io.github.yuubiz.hirari.core.text.PaintGlyphMetrics
import io.github.yuubiz.hirari.core.text.VerticalLayout
import io.github.yuubiz.hirari.core.text.decodeText
import io.github.yuubiz.hirari.core.text.detectCharset
import io.github.yuubiz.hirari.core.text.parseAozora
import io.github.yuubiz.hirari.core.text.resolvePicturePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What only the device can tell: how its ICU converter reads a CP932 byte with no mapping (docs/spec.md section
 * 6.2), that a text and its 挿絵 open from an archive through libarchive (M5), and that a page laid out with the
 * system font's metrics draws with ink on the paper.
 */
class TextOnDeviceTest {
    @Test
    fun anUnmappedCp932LeadByteBeforeALineBreakReadsAsOneReplacementCharacter() {
        val bytes = "前".toByteArray(CP932) + byteArrayOf(0x85.toByte(), '\n'.code.toByte()) + "後".toByteArray(CP932)
        assertEquals("前�\n後", decodeText(bytes, CP932))
    }

    @Test
    fun aTextInAnArchiveOpensWithItsPictureFromTheSameArchive() = runBlocking {
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val text = "題\n本文［＃挿絵（img/a.png、横4×縦4）入る］\n終わり\n".toByteArray(Charsets.UTF_8)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "text-entry.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in listOf("dir/本文.txt" to text, "dir/img/a.png" to png)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val archiveId = BookId("archive")
        val opened = openEntry(FileChannelSource(FileInputStream(file).channel), archiveId, entryIndex = 0, EntryCharsets(null, CP932), EntryArchive.Document(Uri.fromFile(file)))
        val entry = (opened as TextOpenResult.Opened).text
        try {
            assertArrayEquals(text, opened.bytes)
            assertEquals("dir/本文.txt", entry.path)
            assertEquals(entryBookId(archiveId, "dir/本文.txt".toByteArray(Charsets.UTF_8)), entry.id)
            val figure = parseAozora(decodeText(opened.bytes, Charsets.UTF_8)).blocks.filterIsInstance<Figure>().single()
            val path = resolvePicturePath(entry.path, figure.path)!!
            assertArrayEquals(png, entry.pictures!!.read(path))
        } finally {
            entry.close()
            file.delete()
        }
    }

    @Test
    fun aTextEntryIsNamedInTheCharsetChosenForItsArchive() = runBlocking {
        val name = "dir/本文.txt"
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "text-entry-charset.zip")
        ZipOutputStream(file.outputStream(), CP932).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write("本文\n".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val eucJp = Charset.forName("EUC-JP")
        val archiveId = BookId("archive")
        val detected = openEntry(FileChannelSource(FileInputStream(file).channel), archiveId, entryIndex = 0, EntryCharsets(null, CP932), EntryArchive.Document(Uri.fromFile(file)))
        val chosen = openEntry(FileChannelSource(FileInputStream(file).channel), archiveId, entryIndex = 0, EntryCharsets(eucJp, CP932), EntryArchive.Document(Uri.fromFile(file)))
        val texts = listOf(detected, chosen).map { (it as TextOpenResult.Opened).text }
        try {
            assertEquals(name, texts[0].path)
            assertEquals(EntryNames.decode(name.toByteArray(CP932), eucJp), texts[1].path)
            assertEquals(texts[0].id, texts[1].id)
        } finally {
            texts.forEach { it.close() }
            file.delete()
        }
    }

    @Test
    fun aMegabyteTextReachesItsFirstPageWithTheSystemFontAndDrawsInk() {
        val line = "　吾輩《わがはい》は猫である。名前はまだ無い。どこで生れたかとんと見当《けんとう》がつかぬ。「何でも薄暗いじめじめした所で泣いていた」\n"
        val bytes = ("吾輩は猫である\n" + line.repeat((1 shl 20) / (line.length * 2) + 1)).toByteArray(CP932)
        val viewport = Viewport(width = 1080, height = 2340, density = 2.75f)
        val frame = pageFrame(viewport, GlobalSettings())
        val start = SystemClock.elapsedRealtime()
        val text = decodeText(bytes, detectCharset(bytes, CP932).charset)
        val decoded = SystemClock.elapsedRealtime()
        val document = parseAozora(text)
        val parsed = SystemClock.elapsedRealtime()
        val metrics = PaintGlyphMetrics(Typeface.SERIF)
        val setter = Typesetter(VerticalLayout(document, frame.params, metrics), document.sourceLength, 0)
        val near = setter.step(0)!!
        val end = SystemClock.elapsedRealtime()
        Log.i(
            "TextOnDeviceTest",
            "P9 on this device: ${bytes.size} bytes to the first page in ${end - start} ms " +
                "(decode ${decoded - start}, parse ${parsed - decoded}, first pages ${end - parsed})",
        )
        val page = near.first()
        assertTrue((page.content as PageContent.Lines).lines.size > 5)

        val colors = pageColors(GlobalSettings())
        val bitmap = Bitmap.createBitmap(frame.pageWidth.toInt(), frame.pageHeight.toInt(), Bitmap.Config.ARGB_8888)
        PagePainter(Typeface.SERIF, colors).draw(Canvas(bitmap), page, frame, 0f, 0f, PageDecor("吾輩は猫である", "1 / 2", null)) { null }
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val paper = argb(colors.paper)
        assertEquals(paper, pixels[0])
        assertTrue("no ink on the page", pixels.count { Color.red(it) < 64 && Color.green(it) < 64 } > 1000)
    }
}
