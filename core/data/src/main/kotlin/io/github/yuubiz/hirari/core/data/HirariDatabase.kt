package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** The main database of docs/spec.md section 7. */
@Database(entities = [BookProgressRow::class], version = 1, exportSchema = true)
internal abstract class HirariDatabase : RoomDatabase() {
    abstract fun bookProgress(): BookProgressDao
}

internal fun RoomDatabase.Builder<HirariDatabase>.configure(): HirariDatabase =
    setDriver(AndroidSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()

internal fun openDatabase(context: Context): HirariDatabase =
    Room.databaseBuilder<HirariDatabase>(context.applicationContext, "hirari.db").configure()
