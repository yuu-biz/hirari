package io.github.yuubiz.hirari.core.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookPassword
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.ProviderException

@Entity(tableName = "book_passwords")
internal class BookPasswordRow(@PrimaryKey val bookId: String, val iv: ByteArray, val ciphertext: ByteArray)

@Dao
internal interface BookPasswordDao {
    @Query("SELECT * FROM book_passwords WHERE bookId = :id")
    suspend fun get(id: String): BookPasswordRow?
    @Upsert suspend fun put(row: BookPasswordRow)
    @Query("DELETE FROM book_passwords WHERE bookId = :id")
    suspend fun delete(id: String)
    @Query("DELETE FROM book_passwords")
    suspend fun clear(): Int
}

interface BookPasswordStore {
    suspend fun read(id: BookId): CharArray?
    suspend fun put(id: BookId, password: CharArray)
    suspend fun delete(id: BookId)
    suspend fun clear(): Int
}

internal class EncryptedBookPasswords(
    private val dao: BookPasswordDao,
    private val cipher: SecretCipher,
    private val saved: suspend (BookId) -> Unit = {},
) : BookPasswordStore {
    private fun aad(id: BookId) = "book:${id.value}".encodeToByteArray()
    override suspend fun read(id: BookId): CharArray? {
        val row = dao.get(id.value) ?: return null
        val held = java.util.concurrent.atomic.AtomicReference<CharArray?>()
        var delivered = false
        try {
            withContext(Dispatchers.IO) {
                try {
                    val bytes = cipher.open(SealedSecret(row.iv, row.ciphertext), aad(id))
                    try { held.set(bytes.toChars()) } finally { bytes.fill(0) }
                } catch (_: GeneralSecurityException) {
                    // A lost key or a swapped row is a missing password.
                } catch (_: ProviderException) {
                    // Keystore also reports inaccessible keys as unchecked provider failures.
                }
            }
            return held.get().also { delivered = true }
        } finally {
            if (!delivered) held.get()?.fill('\u0000')
        }
    }
    override suspend fun put(id: BookId, password: CharArray) {
        val bytes = password.toUtf8()
        try {
            val sealed = withContext(Dispatchers.IO) { cipher.seal(bytes, aad(id)) }
            dao.put(BookPasswordRow(id.value, sealed.iv, sealed.ciphertext))
            saved(id)
        } finally { bytes.fill(0) }
    }
    override suspend fun delete(id: BookId) = dao.delete(id.value)
    override suspend fun clear(): Int = dao.clear()
}

/** No secret is part of observable UI state or saved instance state. */
class PasswordQuestion(val wrong: Boolean, val busy: Boolean = false)

/** One reader's dialog. The calling read is suspended until an answer, including every retry. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookPasswordPrompt {
    private val mutable = MutableStateFlow<PasswordQuestion?>(null)
    val question = mutable.asStateFlow()
    private val lock = Mutex()
    @Volatile private var answer: CompletableDeferred<Answer?>? = null
    @Volatile private var cancelled = false
    private val secrets = java.util.concurrent.ConcurrentHashMap.newKeySet<CharArray>()
    private class Answer(val password: CharArray, val remember: Boolean)

    fun submit(password: CharArray, remember: Boolean) {
        val pending = answer
        if (pending == null || cancelled) { password.fill('\u0000'); return }
        secrets.add(password)
        mutable.value = PasswordQuestion(mutable.value?.wrong == true, busy = true)
        if (!pending.complete(Answer(password, remember))) { secrets.remove(password); password.fill('\u0000') }
    }
    fun cancel() {
        cancelled = true
        answer?.complete(null)
        secrets.forEach { it.fill('\u0000') }
        secrets.clear()
        mutable.value = null
    }

    fun forBook(id: BookId, store: BookPasswordStore): BookPassword = object : BookPassword {
        private var pending: Answer? = null
        private var fromStore = false
        override suspend fun initial(): CharArray? = store.read(id).also { fromStore = it != null }
        override suspend fun retry(wrong: Boolean): CharArray? = lock.withLock {
            pending?.password?.let { secrets.remove(it); it.fill('\u0000') }
            pending = null
            if (cancelled) return@withLock null
            if (fromStore && wrong) store.delete(id)
            fromStore = false
            val waiting = CompletableDeferred<Answer?>()
            answer = waiting
            mutable.value = PasswordQuestion(wrong)
            try {
                pending = waiting.await()
                pending?.password?.copyOf()
            } finally {
                answer = null
                if (pending == null) mutable.value = null
                if (waiting.isCompleted && waiting.getCompleted() !== pending) waiting.getCompleted()?.password?.let {
                    secrets.remove(it); it.fill('\u0000')
                }
            }
        }
        override suspend fun accepted() {
            val accepted = pending ?: return
            pending = null
            try {
                if (accepted.remember && !cancelled) { store.put(id, accepted.password); fromStore = true }
            }
            finally { secrets.remove(accepted.password); accepted.password.fill('\u0000'); mutable.value = null }
        }
        override fun discard() {
            pending?.password?.let { secrets.remove(it); it.fill('\u0000') }
            pending = null
            mutable.value = null
        }
    }
}

/** Cover generation never asks a question. */
fun BookPasswordStore.silent(id: BookId): BookPassword = object : BookPassword {
    override suspend fun initial(): CharArray? = read(id)
    override suspend fun retry(wrong: Boolean): CharArray? { if (wrong) delete(id); return null }
    override suspend fun accepted() = Unit
}
