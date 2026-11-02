package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** The main database of docs/spec.md section 7. It is backed up; secrets live in [CredentialDatabase]. */
@Database(entities = [BookProgressRow::class], version = 1, exportSchema = true)
internal abstract class HirariDatabase : RoomDatabase() {
    abstract fun bookProgress(): BookProgressDao
}

internal fun <T : RoomDatabase> RoomDatabase.Builder<T>.configure(): T =
    setDriver(AndroidSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()

internal const val MAIN_DATABASE = "hirari.db"

internal fun openDatabase(context: Context): HirariDatabase =
    Room.databaseBuilder<HirariDatabase>(context.applicationContext, MAIN_DATABASE).configure()
