package io.github.yuubiz.hirari.core.data

import android.content.Context

/** The app's persistent data. Create one instance per process. */
class Storage(context: Context) {
    private val database = openDatabase(context)

    val progress: ProgressRepository = RoomProgressRepository(database.bookProgress())

    val settings: SettingsRepository = DataStoreSettingsRepository(openSettings(context.applicationContext))
}
