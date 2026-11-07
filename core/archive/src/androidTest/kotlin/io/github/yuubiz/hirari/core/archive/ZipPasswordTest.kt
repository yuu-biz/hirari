package io.github.yuubiz.hirari.core.archive

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.model.BookPassword
import io.github.yuubiz.hirari.core.source.asByteSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ZipPasswordTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private suspend fun open(path: String, phrase: String): ArchiveSession {
        val file = File(instrumentation.targetContext.cacheDir, "zip-password-test.zip")
        instrumentation.context.assets.open(path).use { input -> file.outputStream().use { input.copyTo(it) } }
        val password = object : BookPassword {
            override suspend fun initial() = phrase.toCharArray()
            override suspend fun retry(wrong: Boolean): CharArray? = null
            override suspend fun accepted() = Unit
        }
        val source = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).asByteSource()
        return (ArchiveSession.open(source, password = password) as OpenResult.Opened).session
    }

    @Test fun traditionalZipReadsImagesAndTextWithTheRightPassword() = runBlocking {
        open("zip/zipcrypto.zip", "hirari").use {
            assertTrue(it.read(0) is EntryResult.Data)
            assertTrue(it.read(1) is EntryResult.Data)
            assertEquals("password protected text", (it.read(2) as EntryResult.Data).bytes.decodeToString())
        }
    }
    @Test fun wrongPasswordFailsOnFirstReadAndLeavesPlainEntriesReadable() = runBlocking {
        open("zip/zipcrypto.zip", "wrong").use {
            assertEquals(EntryResult.Failed(ArchiveFailure.PasswordRequired), it.read(1))
            assertTrue(it.read(0) is EntryResult.Data)
        }
    }
    @Test fun aesRemainsUnsupportedEvenWithThePassword() = runBlocking {
        open("zip/aes.zip", "hirari").use { assertEquals(EntryResult.Encrypted, it.read(1)) }
    }
    @Test fun aPasswordPassingTheHeaderStillNeedsAMatchingCrc() = runBlocking {
        open("zip/zipcrypto.zip", "wrong-131").use {
            assertEquals(EntryResult.Failed(ArchiveFailure.PasswordRequired), it.read(2))
        }
    }
}
