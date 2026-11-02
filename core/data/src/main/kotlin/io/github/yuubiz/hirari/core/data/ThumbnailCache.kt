package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a thumbnail is made from (docs/spec.md section 5.4). A new version of the file, another cover or another
 * size gives another key, so a stale thumbnail is never found. A folder uses the id of the image it shows.
 */
data class ThumbKey(
    val book: BookId,
    val modified: Instant,
    val byteSize: Long,
    /** The `entryIndex` of the cover page, `null` for the first page in natural order. */
    val coverEntry: Int?,
    /** The long side in pixels. */
    val sizePx: Int,
    /** The binding direction picks the half of a cover scanned as a spread, so another direction is another thumbnail. */
    val direction: ReadingDirection,
) {
    /** The SHA-1 of the fields, each written out and ended by a NUL byte. */
    val digest: String by lazy {
        val text = listOf(book.value, modified.toEpochMilli(), byteSize, coverEntry ?: -1, sizePx, direction.name).joinToString("") { "$it\u0000" }
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

sealed interface ThumbLookup {
    data class Hit(val file: File) : ThumbLookup

    /** Making it failed before for this version of the file; docs/spec.md section 4.4 does not retry. */
    data object Failed : ThumbLookup

    data object Miss : ThumbLookup
}

/** The thumbnail disk cache of docs/spec.md section 5.4. Safe to call from the main thread. */
interface ThumbnailCache {
    /** A hit counts as a use. A file the system deleted is a miss. */
    suspend fun get(key: ThumbKey): ThumbLookup

    /** Stores a WebP thumbnail, then deletes the least recently used ones past the size limit. */
    suspend fun put(key: ThumbKey, webp: ByteArray): File

    suspend fun markFailed(key: ThumbKey)

    /** サムネイルを作り直す: forgets the thumbnail or the failure, so the next [get] is a miss. */
    suspend fun remove(key: ThumbKey)

    /**
     * Keeps [key] out of eviction while a screen shows it. Close the hold when the item leaves the screen.
     * Hold before [get]: a thumbnail that [get] returned for a held key is not evicted afterwards.
     */
    fun hold(key: ThumbKey): Closeable

    /** サムネイルのディスクキャッシュを消す. */
    suspend fun clear()

    /** Drops index rows whose file is gone and files the index does not know. Call once at start. */
    suspend fun reconcile()
}

@Entity(tableName = "thumbs", indices = [Index("last_used")])
internal data class ThumbRow(
    @PrimaryKey val digest: String,
    val bytes: Long,
    @ColumnInfo(name = "last_used") val lastUsed: Long,
    val failed: Boolean,
)

@Dao
internal interface ThumbDao {
    @Query("SELECT * FROM thumbs WHERE digest = :digest")
    suspend fun get(digest: String): ThumbRow?

    @Upsert
    suspend fun upsert(row: ThumbRow)

    @Query("UPDATE thumbs SET last_used = :at WHERE digest = :digest")
    suspend fun touch(digest: String, at: Long)

    @Query("DELETE FROM thumbs WHERE digest = :digest")
    suspend fun delete(digest: String)

    @Query("DELETE FROM thumbs")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM thumbs WHERE NOT failed")
    suspend fun totalBytes(): Long

    @Query("SELECT * FROM thumbs WHERE NOT failed ORDER BY last_used")
    suspend fun byAge(): List<ThumbRow>
}

/**
 * The index lives in `noBackupFilesDir`, outside the cache directory: the system may clear the cache while the
 * app runs, and an index that names missing files recovers per entry, where a database file deleted under an
 * open connection does not.
 */
@Database(entities = [ThumbRow::class], version = 1, exportSchema = true)
internal abstract class ThumbnailIndexDatabase : RoomDatabase() {
    abstract fun thumbs(): ThumbDao
}

internal fun openThumbnailIndex(context: Context): ThumbnailIndexDatabase =
    Room.databaseBuilder<ThumbnailIndexDatabase>(context.applicationContext, File(context.noBackupFilesDir, "thumbs.db").path).configure()

internal const val THUMBNAIL_LIMIT_BYTES = 256L * 1024 * 1024

internal class DiskThumbnailCache(
    private val dir: File,
    private val dao: ThumbDao,
    private val limitBytes: Long = THUMBNAIL_LIMIT_BYTES,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ThumbnailCache {
    private val held = ConcurrentHashMap<String, Int>()
    private val writing = Mutex()

    private fun fileOf(digest: String) = File(dir, "$digest.webp")

    override suspend fun get(key: ThumbKey): ThumbLookup {
        val row = dao.get(key.digest) ?: return ThumbLookup.Miss
        if (row.failed) return ThumbLookup.Failed
        val file = fileOf(row.digest)
        if (!withContext(io) { file.isFile }) return forgetIfStillMissing(row.digest)
        dao.touch(row.digest, now().toEpochMilli())
        return ThumbLookup.Hit(file)
    }

    /**
     * A [put] may have written the file after the check that found it missing, so the check is made again
     * under the write lock before the row goes.
     */
    private suspend fun forgetIfStillMissing(digest: String): ThumbLookup = writing.withLock {
        val row = dao.get(digest) ?: return@withLock ThumbLookup.Miss
        if (row.failed) return@withLock ThumbLookup.Failed
        val file = fileOf(digest)
        if (withContext(io) { file.isFile }) {
            dao.touch(digest, now().toEpochMilli())
            ThumbLookup.Hit(file)
        } else {
            dao.delete(digest)
            ThumbLookup.Miss
        }
    }

    /**
     * Not cancellable once started: a cancel between the file and its row, or inside the eviction, would leave a
     * file the index does not count.
     */
    override suspend fun put(key: ThumbKey, webp: ByteArray): File = writing.withLock {
        withContext(NonCancellable) { store(key, webp) }
    }

    private suspend fun store(key: ThumbKey, webp: ByteArray): File {
        val file = fileOf(key.digest)
        withContext(io) {
            dir.mkdirs()
            val partial = File(dir, "${key.digest}.part")
            partial.writeBytes(webp)
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        dao.upsert(ThumbRow(key.digest, webp.size.toLong(), now().toEpochMilli(), failed = false))
        evict()
        return file
    }

    private suspend fun evict() {
        var total = dao.totalBytes()
        if (total <= limitBytes) return
        for (row in dao.byAge()) {
            if (total <= limitBytes) break
            if (held.containsKey(row.digest)) continue
            // The row goes first, then the hold is looked at again: a screen that held the thumbnail and then
            // called get either found no row, or holds it now, and the row is put back.
            dao.delete(row.digest)
            if (held.containsKey(row.digest)) {
                dao.upsert(row)
                continue
            }
            withContext(io) { fileOf(row.digest).delete() }
            total -= row.bytes
        }
    }

    override suspend fun markFailed(key: ThumbKey) = writing.withLock {
        withContext(io) { fileOf(key.digest).delete() }
        dao.upsert(ThumbRow(key.digest, 0, now().toEpochMilli(), failed = true))
    }

    override suspend fun remove(key: ThumbKey) = writing.withLock {
        withContext(io) { fileOf(key.digest).delete() }
        dao.delete(key.digest)
    }

    override fun hold(key: ThumbKey): Closeable {
        val digest = key.digest
        held.merge(digest, 1, Int::plus)
        val open = AtomicBoolean(true)
        return Closeable {
            if (open.getAndSet(false)) held.computeIfPresent(digest) { _, count -> (count - 1).takeIf { it > 0 } }
        }
    }

    override suspend fun clear() = writing.withLock {
        withContext(io) { dir.listFiles()?.forEach { it.delete() } }
        dao.deleteAll()
    }

    override suspend fun reconcile() = writing.withLock {
        val files = withContext(io) { dir.listFiles()?.toList().orEmpty() }
        val known = dao.byAge().map { it.digest }.toSet()
        val present = files.filter { it.name.endsWith(".webp") }.map { it.name.removeSuffix(".webp") }.toSet()
        (known - present).forEach { dao.delete(it) }
        val stray = files.filter { !it.name.endsWith(".webp") || it.name.removeSuffix(".webp") !in known }
        withContext(io) { stray.forEach { it.delete() } }
    }
}
