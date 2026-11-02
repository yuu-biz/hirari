package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.datastore.core.DataStore

/** The app's persistent data (docs/spec.md section 7). Create one instance per process, off the main thread. */
class Storage internal constructor(
    database: HirariDatabase,
    credentialDatabase: CredentialDatabase,
    cipher: SecretCipher,
    settingsStore: DataStore<GlobalSettings>,
) {
    constructor(context: Context) : this(
        database = openDatabase(context),
        credentialDatabase = openCredentialDatabase(context),
        cipher = SecretCipher(::keystoreKey),
        settingsStore = openSettings(context.applicationContext),
    )

    val progress: ProgressRepository = RoomProgressRepository(database.bookProgress())

    val settings: SettingsRepository = DataStoreSettingsRepository(settingsStore)

    val credentials: CredentialStore = EncryptedCredentialStore(credentialDatabase.credentials(), cipher)

    val locations: LocationRepository = RoomLocationRepository(database, credentials)

    val books: BookRepository = RoomBookRepository(database)

    val history: HistoryRepository = RoomHistoryRepository(database)

    val bookmarks: BookmarkRepository = RoomBookmarkRepository(database)
}
