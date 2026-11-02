package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import javax.crypto.KeyGenerator

/** Robolectric has no AndroidKeyStore, so JVM tests seal with a software AES-256 key. */
internal fun softwareCipher(): SecretCipher {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    return SecretCipher { key }
}

private val context get() = ApplicationProvider.getApplicationContext<Context>()

internal fun inMemoryDatabase(): HirariDatabase = Room.inMemoryDatabaseBuilder<HirariDatabase>(context).configure()

internal fun inMemoryCredentialDatabase(): CredentialDatabase = Room.inMemoryDatabaseBuilder<CredentialDatabase>(context).configure()

internal fun testCredentials(database: CredentialDatabase = inMemoryCredentialDatabase()): CredentialStore =
    EncryptedCredentialStore(database.credentials(), softwareCipher())

internal fun inMemoryThumbnailIndex(): ThumbnailIndexDatabase = Room.inMemoryDatabaseBuilder<ThumbnailIndexDatabase>(context).configure()
