package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.BookId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.coroutines.CoroutineContext
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ThumbnailCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val index = inMemoryThumbnailIndex()
    private var clock = 0L
    private val dir by lazy { File(folder.root, "thumbs") }
    private val cache by lazy {
        DiskThumbnailCache(dir, index.thumbs(), limitBytes = 100, now = { Instant.ofEpochSecond(++clock) })
    }

    @After
    fun close() = index.close()

    private fun key(name: String) = ThumbKey(BookId(name), Instant.ofEpochMilli(1000), byteSize = 5000, coverEntry = null, sizePx = 300)

    private fun bytes(size: Int) = ByteArray(size) { it.toByte() }

    private suspend fun ThumbnailCache.isHit(name: String) = get(key(name)) is ThumbLookup.Hit

    @Test
    fun `the key changes with every field it is made from`() {
        val base = key("a")
        assertEquals(40, base.digest.length)
        assertEquals(base.digest, key("a").digest)
        val others = listOf(
            base.copy(book = BookId("b")),
            base.copy(modified = Instant.ofEpochMilli(1001)),
            base.copy(byteSize = 5001),
            base.copy(coverEntry = 0),
            base.copy(sizePx = 301),
        )
        assertEquals(others.size + 1, (others + base).map { it.digest }.toSet().size)
    }

    @Test
    fun `a stored thumbnail is found with its bytes`() = runTest {
        cache.put(key("a"), bytes(40))
        val hit = assertIs<ThumbLookup.Hit>(cache.get(key("a")))
        assertEquals(bytes(40).toList(), hit.file.readBytes().toList())
        assertEquals(ThumbLookup.Miss, cache.get(key("b")))
    }

    @Test
    fun `past the limit the least recently used thumbnails go first`() = runTest {
        cache.put(key("a"), bytes(40))
        cache.put(key("b"), bytes(40))
        assertTrue(cache.isHit("a"))
        cache.put(key("c"), bytes(40))
        assertEquals(listOf(true, false, true), listOf("a", "b", "c").map { cache.isHit(it) })
        assertEquals(listOf("${key("a").digest}.webp", "${key("c").digest}.webp").sorted(), dir.list()!!.sorted())
    }

    @Test
    fun `a thumbnail on screen is never evicted`() = runTest {
        cache.put(key("a"), bytes(40))
        val shown = cache.hold(key("a"))
        cache.put(key("b"), bytes(40))
        cache.put(key("c"), bytes(40))
        assertEquals(listOf(true, false, true), listOf("a", "b", "c").map { cache.isHit(it) })
        shown.close()
        shown.close()
        cache.put(key("d"), bytes(80))
        assertEquals(listOf(false, false, true), listOf("a", "c", "d").map { cache.isHit(it) })
    }

    @Test
    fun `a hold taken while the eviction decides keeps the thumbnail`() = runTest {
        var cache: DiskThumbnailCache? = null
        var armed = false
        var fired = false
        fun fire() {
            if (armed && !fired) {
                fired = true
                cache!!.hold(key("a"))
            }
        }
        val dao = object : ThumbDao by index.thumbs() {
            override suspend fun byAge() = index.thumbs().byAge().also { armed = true }

            override suspend fun delete(digest: String) {
                fire()
                index.thumbs().delete(digest)
            }
        }
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                fire()
                block.run()
            }
        }
        cache = DiskThumbnailCache(dir, dao, limitBytes = 100, now = { Instant.ofEpochSecond(++clock) }, io = io)
        cache.put(key("a"), bytes(40))
        cache.put(key("b"), bytes(40))
        cache.put(key("c"), bytes(40))
        assertTrue(fired)
        assertTrue(cache.isHit("a"))
        assertTrue(File(dir, "${key("a").digest}.webp").isFile)
        assertEquals(listOf(false, true), listOf("b", "c").map { cache.isHit(it) })
    }

    @Test
    fun `a put cancelled during eviction leaves no file without a row`() = runTest {
        var writer: Job? = null
        val dao = object : ThumbDao by index.thumbs() {
            override suspend fun delete(digest: String) {
                index.thumbs().delete(digest)
                writer?.cancel()
            }
        }
        val cache = DiskThumbnailCache(dir, dao, limitBytes = 100, now = { Instant.ofEpochSecond(++clock) })
        cache.put(key("a"), bytes(40))
        cache.put(key("b"), bytes(40))
        writer = launch(start = CoroutineStart.LAZY) { cache.put(key("c"), bytes(40)) }
        writer.join()
        val files = dir.listFiles().orEmpty().map { it.name }.toSet()
        val rows = index.thumbs().byAge().map { "${it.digest}.webp" }.toSet()
        assertEquals(rows, files)
    }

    @Test
    fun `a thumbnail stored while get finds the file missing keeps its row`() = runTest {
        var cache: DiskThumbnailCache? = null
        var checked = false
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                block.run()
                if (!checked) {
                    checked = true
                    runBlocking { cache!!.put(key("a"), bytes(40)) }
                }
            }
        }
        cache = DiskThumbnailCache(dir, index.thumbs(), limitBytes = 100, now = { Instant.ofEpochSecond(++clock) }, io = io)
        index.thumbs().upsert(ThumbRow(key("a").digest, 10, 1, failed = false))
        cache.get(key("a"))
        assertTrue(checked)
        assertTrue(File(dir, "${key("a").digest}.webp").isFile)
        assertEquals(40L, index.thumbs().totalBytes())
        assertTrue(cache.isHit("a"))
    }

    @Test
    fun `a hold closed twice does not release another hold`() = runTest {
        cache.put(key("a"), bytes(40))
        val first = cache.hold(key("a"))
        cache.hold(key("a"))
        first.close()
        first.close()
        cache.put(key("b"), bytes(80))
        assertTrue(cache.isHit("a"))
    }

    @Test
    fun `when the system clears the cache, thumbnails are made again`() = runTest {
        cache.put(key("a"), bytes(40))
        cache.put(key("b"), bytes(40))
        dir.deleteRecursively()
        assertEquals(ThumbLookup.Miss, cache.get(key("a")))
        cache.reconcile()
        assertEquals(0L, index.thumbs().totalBytes())
        cache.put(key("a"), bytes(40))
        assertTrue(cache.isHit("a"))
    }

    @Test
    fun `reconcile drops stray files and rows without files`() = runTest {
        cache.put(key("a"), bytes(40))
        cache.put(key("b"), bytes(40))
        File(dir, "${key("b").digest}.webp").delete()
        File(dir, "leftover.part").writeBytes(bytes(10))
        File(dir, "unknown.webp").writeBytes(bytes(10))
        cache.reconcile()
        assertEquals(listOf("${key("a").digest}.webp"), dir.list()!!.toList())
        assertEquals(40L, index.thumbs().totalBytes())
    }

    @Test
    fun `a failure is remembered until the thumbnail is made again`() = runTest {
        cache.markFailed(key("a"))
        assertEquals(ThumbLookup.Failed, cache.get(key("a")))
        cache.remove(key("a"))
        assertEquals(ThumbLookup.Miss, cache.get(key("a")))
        cache.put(key("a"), bytes(10))
        cache.markFailed(key("a"))
        assertEquals(ThumbLookup.Failed, cache.get(key("a")))
        assertEquals(0L, index.thumbs().totalBytes())
    }

    @Test
    fun `clearing empties the folder and the index`() = runTest {
        cache.put(key("a"), bytes(40))
        cache.markFailed(key("b"))
        cache.clear()
        assertEquals(listOf(ThumbLookup.Miss, ThumbLookup.Miss), listOf(cache.get(key("a")), cache.get(key("b"))))
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun `the index opens outside the cache directory`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = openThumbnailIndex(context)
        try {
            database.thumbs().upsert(ThumbRow("x", 1, 1, failed = false))
            assertTrue(File(context.noBackupFilesDir, "thumbs.db").isFile)
            assertNotEquals(context.cacheDir, File(context.noBackupFilesDir, "thumbs.db").parentFile)
        } finally {
            database.close()
        }
    }
}
