package io.github.yuubiz.hirari.core.image

import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.github.yuubiz.hirari.core.model.BookPassword
import io.github.yuubiz.hirari.core.source.asByteSource
import kotlinx.coroutines.runBlocking
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/** docs/spec.md Q4: whether `PdfRenderer` from API 35 opens a PDF with a user password when it is given. */
class PdfPasswordTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun descriptor(): ParcelFileDescriptor {
        val file = File(instrumentation.targetContext.cacheDir, "password.pdf")
        instrumentation.context.assets.open("pdf/password.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Test
    fun withoutThePasswordTheRendererRefusesTheDocument() {
        val descriptor = descriptor()
        assertThrows(SecurityException::class.java) { PdfRenderer(descriptor) }
        descriptor.close()
    }

    @Test
    @SdkSuppress(minSdkVersion = 35)
    fun fromApi35TheRendererOpensItWithThePassword() {
        val params = LoadParams.Builder().setPassword(USER_PASSWORD).build()
        PdfRenderer(descriptor(), params).use { assertEquals(1, it.pageCount) }
    }

    @Test
    @SdkSuppress(minSdkVersion = 35)
    fun theBookOpenerReasksAfterAWrongPasswordAndOpensANewRenderer() = runBlocking {
        val wrongs = mutableListOf<Boolean>()
        var accepted = 0
        val password = object : BookPassword {
            override suspend fun initial() = "wrong".toCharArray()
            override suspend fun retry(wrong: Boolean): CharArray { wrongs += wrong; return USER_PASSWORD.toCharArray() }
            override suspend fun accepted() { accepted++ }
        }
        val result = Books.openPdf(descriptor().asByteSource(), ImageDecoding.forDevice(instrumentation.targetContext), password)
        val book = (result as BookResult.Opened).book
        book.use { assertEquals(1, it.pages.size) }
        assertEquals(listOf(true), wrongs)
        assertEquals(1, accepted)
    }

    @Test
    @SdkSuppress(minSdkVersion = 35)
    fun aWrongPasswordFailsAsPasswordRequiredThroughTheProductionOpener() = runBlocking {
        val password = object : BookPassword {
            override suspend fun initial() = "wrong".toCharArray()
            override suspend fun retry(wrong: Boolean): CharArray? = null
            override suspend fun accepted() = Unit
        }
        assertEquals(BookResult.Failed(BookFailure.PasswordRequired),
            Books.openPdf(descriptor().asByteSource(), ImageDecoding.forDevice(instrumentation.targetContext), password))
    }

    private companion object {
        /** The user password `tools/corpus` gives `pdf/password.pdf`. */
        const val USER_PASSWORD = "hirari"
    }
}
