package io.github.yuubiz.hirari.core.image

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/** The SAF paths of the book opener through [TestDocumentsProvider], against the `zip/images.zip` expectations. */
class SafBookTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val decoding = ImageDecoding.forDevice(context)
    private val target = PixelSize(1080, 1920)
    private val copyDir = File(context.cacheDir, "copy")
    private val root = File(context.cacheDir, "documents/${TestDocumentsProvider.ROOT}")
    private val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT)

    private fun asset(path: String): ByteArray = instrumentation.context.assets.open(path).use { it.readBytes() }

    private val expected: ExpectedBook by lazy {
        parsePagesManifest(String(asset("pages.tsv"), Charsets.UTF_8)).single { it.path == "zip/images.zip" }
    }

    @Before
    fun writeDocuments() {
        root.deleteRecursively()
        copyDir.deleteRecursively()
        root.mkdirs()
        ZipInputStream(asset("zip/images.zip").inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { File(root, it.name).writeBytes(zip.readBytes()) }
        }
        File(root, "sub").mkdirs()
        File(root, "baseline.jpg").copyTo(File(root, "sub/0000.jpg"))
    }

    @After
    fun servePlainFiles() {
        TestDocumentsProvider.pipe = false
    }

    private fun opened(result: BookResult): OpenBook = when (result) {
        is BookResult.Opened -> result.book
        is BookResult.Failed -> throw AssertionError("open failed: ${result.reason}")
    }

    @Test
    fun aTreeFolderIsABookOfItsOwnImagesInNaturalOrder() = runBlocking {
        val book = opened(Books.openTreeFolder(context.contentResolver, treeUri, null, copyDir, decoding))
        val problems = book.use { pageMismatches(it, expected, target) }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun aDocumentServedThroughAPipeIsCopiedAndReadsTheSame() = runBlocking {
        root.listFiles()!!.forEach { it.deleteRecursively() }
        File(root, "images.zip").writeBytes(asset("zip/images.zip"))
        TestDocumentsProvider.pipe = true
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "${TestDocumentsProvider.ROOT}/images.zip")

        val book = opened(Books.openDocument(context.contentResolver, uri, copyDir, decoding))

        assertTrue("the copy has no name on disk", copyDir.listFiles().orEmpty().isEmpty())
        val problems = book.use { pageMismatches(it, expected, target) }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun aMissingDocumentFailsToOpenAsNotFound() = runBlocking {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "${TestDocumentsProvider.ROOT}/gone.zip")
        assertEquals(BookResult.Failed(BookFailure.NotFound), Books.openDocument(context.contentResolver, uri, copyDir, decoding))
    }
}
