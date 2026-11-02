package io.github.yuubiz.hirari.core.image

import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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

    private companion object {
        /** The user password `tools/corpus` gives `pdf/password.pdf`. */
        const val USER_PASSWORD = "hirari"
    }
}
