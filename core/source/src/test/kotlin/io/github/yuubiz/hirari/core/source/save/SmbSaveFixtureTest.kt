package io.github.yuubiz.hirari.core.source.save

import io.github.yuubiz.hirari.core.source.smb.SmbAddress
import io.github.yuubiz.hirari.core.source.smb.SmbFixture
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import io.github.yuubiz.hirari.core.source.smb.timed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A folder on disk, the way a destination on the device's own storage is. */
private class DiskFolder(private val dir: File) : SaveFolder {
    override fun names(): Set<String> = dir.list().orEmpty().toSet()

    override fun createFolder(name: String): SaveFolder = DiskFolder(File(dir, name).also { check(it.mkdir()) })

    override fun create(name: String): SaveFile {
        val file = File(dir, name)
        val output = file.outputStream()
        return object : SaveFile {
            override val freeBytes: Long = dir.usableSpace

            override fun write(buffer: ByteArray, length: Int) = output.write(buffer, 0, length)

            override fun commit(name: String) {
                output.close()
                check(file.renameTo(File(dir, name)))
            }

            override fun discard() {
                output.close()
                file.delete()
            }
        }
    }

    override fun delete(name: String) {
        File(dir, name).deleteRecursively()
    }
}

/** Saves from the Samba fixture (docs/spec.md section 4.5), skipped without `-Dhirari.smb.host`. */
class SmbSaveFixtureTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val target: File = Files.createTempDirectory("save-fixture").toFile()

    @After
    fun tearDown() {
        scope.cancel()
        target.deleteRecursively()
    }

    private fun server() = SmbServer(SmbAddress(SmbFixture.host!!, "books", SmbFixture.port), SmbFixture.login(), scope)

    @Test
    fun `a file of eight megabytes is copied byte for byte and named by the fixture`() {
        SmbFixture.require()
        val progress = mutableListOf<SaveProgress>()
        val (millis, result) = timed("save big.bin") {
            runBlocking { server().use { it.saveTo(SmbFixture.BIG, false, DiskFolder(target)) { p -> progress += p } } }
        }
        println("SMB save of ${SmbFixture.BIG_SIZE} bytes: $millis ms")
        assertEquals(SaveOutcome.Saved(SmbFixture.BIG, 1, SmbFixture.BIG_SIZE.toLong()), result.getOrThrow())
        assertArrayEquals(SmbFixture.pattern, File(target, SmbFixture.BIG).readBytes())
        assertEquals(listOf(SmbFixture.BIG), target.list()!!.toList())
        assertEquals(100, progress.last().percent)
        assertTrue(progress.map { it.bytesDone }.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `a folder with a subfolder is copied whole and a second copy takes the next name`() {
        SmbFixture.require()
        server().use { server ->
            val first = runBlocking { server.saveTo("画像", true, DiskFolder(target)) }
            assertEquals(SaveOutcome.Saved("画像", 6, 3008 + 4008 + 2008 + 1008 + 1008 + 4L), first)
            val second = runBlocking { server.saveTo("画像", true, DiskFolder(target)) }
            assertEquals("画像 (1)", (second as SaveOutcome.Saved).name)
            assertEquals(setOf("画像", "画像 (1)"), target.list()!!.toSet())
            assertEquals(
                setOf("第1話.png", "第2話.png", "第10話.png", "scan", "メモ.txt", "おまけ"),
                File(target, "画像").list()!!.toSet(),
            )
            val png = File(target, "画像/第2話.png").readBytes()
            assertEquals(3000 + 8, png.size)
            assertArrayEquals(SmbFixture.pattern.copyOf(3000), png.copyOfRange(8, png.size))
            assertEquals(1000 + 8, File(target, "画像/おまけ/01.png").length().toInt())
            assertTrue(target.walkTopDown().none { it.name.endsWith(SAVE_PART_SUFFIX) })
        }
    }

    @Test
    fun `an empty file and a missing file`() {
        SmbFixture.require()
        server().use { server ->
            assertEquals(SaveOutcome.Saved("empty.bin", 1, 0), runBlocking { server.saveTo("empty.bin", false, DiskFolder(target)) })
            assertEquals(0L, File(target, "empty.bin").length())
            assertEquals(SaveOutcome.Failed(SaveFailure.NotFound), runBlocking { server.saveTo("nothing.bin", false, DiskFolder(target)) })
            assertEquals(SaveOutcome.Failed(SaveFailure.NotFound), runBlocking { server.saveTo("nothing", true, DiskFolder(target)) })
        }
    }
}
