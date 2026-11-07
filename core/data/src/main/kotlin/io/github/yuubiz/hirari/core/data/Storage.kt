package io.github.yuubiz.hirari.core.data

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.core.DataStore
import java.io.File

/** The app's persistent data (docs/spec.md section 7). Create one instance per process, off the main thread. */
class Storage internal constructor(
    database: HirariDatabase,
    credentialDatabase: CredentialDatabase,
    cipher: SecretCipher,
    settingsStore: DataStore<GlobalSettings>,
    launchStore: DataStore<StoredLaunch>,
    /** The thumbnail disk cache. */
    val thumbnails: ThumbnailCache,
    /** The fonts imported for the text reader. */
    val fonts: FontStore,
    debuggable: Boolean = false,
) {
    constructor(context: Context) : this(
        database = openDatabase(context),
        credentialDatabase = openCredentialDatabase(context),
        cipher = SecretCipher(::keystoreKey),
        settingsStore = openSettings(context.applicationContext),
        launchStore = openLaunch(context.applicationContext),
        thumbnails = DiskThumbnailCache(
            File(context.cacheDir, "thumbs"),
            openThumbnailIndex(context).thumbs(),
            makingDir = File(context.noBackupFilesDir, "thumbs-making"),
        ),
        fonts = FontStore(File(context.filesDir, "fonts")),
        debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )

    val progress: ProgressRepository = RoomProgressRepository(database.bookProgress())

    val settings: SettingsRepository = DataStoreSettingsRepository(settingsStore)

    val launch: LaunchRepository = DataStoreLaunchRepository(launchStore, debuggable)

    val credentials: CredentialStore = EncryptedCredentialStore(credentialDatabase.credentials(), cipher)
    val bookPasswords: BookPasswordStore = EncryptedBookPasswords(credentialDatabase.bookPasswords(), cipher, thumbnails::clearFailures)

    val locations: LocationRepository = RoomLocationRepository(database, credentials)

    val books: BookRepository = RoomBookRepository(database)

    val covers: CoverIndex = RoomCoverIndex(database)

    val history: HistoryRepository = RoomHistoryRepository(database)

    val bookmarks: BookmarkRepository = RoomBookmarkRepository(database)

    val transfer: SettingsTransfer = SettingsTransfer(settings, database)

    val itemRecords: ItemRecords = RoomItemRecords(database)
}
