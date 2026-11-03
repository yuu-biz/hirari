package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** The main database of docs/spec.md section 7. It is backed up; secrets live in [CredentialDatabase]. */
@Database(
    entities = [
        BookProgressRow::class,
        LocationRow::class,
        BookRow::class,
        HistoryRow::class,
        BookmarkRow::class,
        BookOverridesRow::class,
        PinnedFolderRow::class,
        FolderCoverRow::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
internal abstract class HirariDatabase : RoomDatabase() {
    abstract fun bookProgress(): BookProgressDao
    abstract fun locations(): LocationDao
    abstract fun books(): BookDao
    abstract fun history(): HistoryDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun bookOverrides(): BookOverridesDao
    abstract fun folders(): FolderDao
}

internal fun <T : RoomDatabase> RoomDatabase.Builder<T>.configure(): T =
    setDriver(AndroidSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()

internal const val MAIN_DATABASE = "hirari.db"

internal fun openDatabase(context: Context): HirariDatabase =
    Room.databaseBuilder<HirariDatabase>(context.applicationContext, MAIN_DATABASE).configure()
