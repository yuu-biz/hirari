package io.github.yuubiz.hirari.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import io.github.yuubiz.hirari.core.model.CredentialRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A secret as the credential store keeps it: AES-256-GCM output and the IV the cipher chose. */
internal class SealedSecret(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * AES-256-GCM with the key that [key] returns. The cipher picks the IV, as a Keystore key with randomized
 * encryption requires, so the same code runs with a software key in JVM tests. [aad] binds a sealed secret
 * to its alias, so swapping rows does not hand one server another server's password.
 */
internal class SecretCipher(private val key: () -> SecretKey) {
    fun seal(plain: ByteArray, aad: ByteArray): SealedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        return SealedSecret(cipher.iv, cipher.doFinal(plain))
    }

    /** Throws [GeneralSecurityException] when the key is gone or the data does not match it. */
    fun open(sealed: SealedSecret, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.ciphertext)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "hirari.credentials"

/** The one Keystore key of the credential store, made on first use. It never leaves the device. */
internal fun keystoreKey(): SecretKey {
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build(),
    )
    return generator.generateKey()
}

sealed interface StoredSecret {
    /** The caller clears [secret] with `fill('\u0000')` when done. */
    class Found(val secret: CharArray) : StoredSecret {
        override fun toString() = "Found(***)"
    }

    /** Nothing is stored under the reference. */
    data object Missing : StoredSecret

    /** The key cannot open the stored value, as after a restore to another device. The user enters it again. */
    data object Unreadable : StoredSecret
}

/**
 * SMB passwords (docs/spec.md section 7). The values never reach a log, a `toString`, or the main database.
 * Safe to call from the main thread.
 */
interface CredentialStore {
    /** Stores [secret] under a new random reference. [secret] is left for the caller to clear. */
    suspend fun store(secret: CharArray): CredentialRef

    suspend fun replace(ref: CredentialRef, secret: CharArray)

    suspend fun read(ref: CredentialRef): StoredSecret

    suspend fun delete(ref: CredentialRef)
}

@Entity(tableName = "credentials")
internal data class CredentialRow(
    @PrimaryKey val alias: String,
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun toString() = "CredentialRow($alias)"

    override fun equals(other: Any?) = other is CredentialRow && other.alias == alias

    override fun hashCode() = alias.hashCode()
}

@Dao
internal interface CredentialDao {
    @Query("SELECT * FROM credentials WHERE alias = :alias")
    suspend fun get(alias: String): CredentialRow?

    @Upsert
    suspend fun upsert(row: CredentialRow)

    @Query("DELETE FROM credentials WHERE alias = :alias")
    suspend fun delete(alias: String)
}

/** A database file of its own, so the backup rules can leave it out (docs/spec.md section 7). */
@Database(entities = [CredentialRow::class], version = 1, exportSchema = true)
internal abstract class CredentialDatabase : RoomDatabase() {
    abstract fun credentials(): CredentialDao
}

internal const val CREDENTIAL_DATABASE = "credentials.db"

internal fun openCredentialDatabase(context: Context): CredentialDatabase =
    Room.databaseBuilder<CredentialDatabase>(context.applicationContext, CREDENTIAL_DATABASE).configure()

/** Keystore calls are Binder calls, so they run on [io] (docs/spec.md section 6.4). */
internal class EncryptedCredentialStore(
    private val dao: CredentialDao,
    private val cipher: SecretCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CredentialStore {
    override suspend fun store(secret: CharArray): CredentialRef =
        CredentialRef(UUID.randomUUID().toString()).also { replace(it, secret) }

    override suspend fun replace(ref: CredentialRef, secret: CharArray) {
        val plain = secret.toUtf8()
        val sealed = try {
            withContext(io) { cipher.seal(plain, ref.alias.encodeToByteArray()) }
        } finally {
            plain.fill(0)
        }
        dao.upsert(CredentialRow(ref.alias, sealed.iv, sealed.ciphertext))
    }

    override suspend fun read(ref: CredentialRef): StoredSecret {
        val row = dao.get(ref.alias) ?: return StoredSecret.Missing
        val plain = try {
            withContext(io) { cipher.open(SealedSecret(row.iv, row.ciphertext), ref.alias.encodeToByteArray()) }
        } catch (_: GeneralSecurityException) {
            return StoredSecret.Unreadable
        } catch (_: ProviderException) {
            // The Keystore reports some failures, such as a key it cannot load, as this unchecked exception.
            return StoredSecret.Unreadable
        }
        return try {
            StoredSecret.Found(plain.toChars())
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun delete(ref: CredentialRef) = dao.delete(ref.alias)
}

/** UTF-8 without a [String] in between, so no copy of the secret waits for the garbage collector. */
private fun CharArray.toUtf8(): ByteArray {
    val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(this))
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    buffer.array().fill(0)
    return bytes
}

private fun ByteArray.toChars(): CharArray {
    val buffer = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(this))
    val chars = CharArray(buffer.remaining())
    buffer.get(chars)
    buffer.array().fill('\u0000')
    return chars
}
