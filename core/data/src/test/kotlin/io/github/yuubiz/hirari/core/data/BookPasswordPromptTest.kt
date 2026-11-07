package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.BookId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookPasswordPromptTest {
    private val id = BookId("book")
    private class Store : BookPasswordStore {
        val values = mutableMapOf<BookId, CharArray>()
        var deleted = 0
        override suspend fun read(id: BookId) = values[id]?.copyOf()
        override suspend fun put(id: BookId, password: CharArray) { values[id] = password.copyOf() }
        override suspend fun delete(id: BookId) { deleted++; values.remove(id)?.fill('\u0000') }
        override suspend fun clear(): Int = values.size.also { values.values.forEach { it.fill('\u0000') }; values.clear() }
    }

    @Test fun `ask wrong right remembers only after a successful read`() = runTest {
        val store = Store()
        val prompt = BookPasswordPrompt()
        val book = prompt.forBook(id, store)
        assertNull(book.initial())
        val first = async(start = CoroutineStart.UNDISPATCHED) { book.retry(false) }
        assertFalse(prompt.question.value!!.wrong)
        prompt.submit("wrong".toCharArray(), true)
        assertEquals("wrong", first.await()!!.concatToString())
        assertTrue(store.values.isEmpty())
        val next = async(start = CoroutineStart.UNDISPATCHED) { book.retry(true) }
        assertTrue(prompt.question.value!!.wrong)
        prompt.submit("right".toCharArray(), true)
        next.await()!!.fill('\u0000')
        assertTrue(store.values.isEmpty())
        book.accepted()
        assertNull(prompt.question.value)
        assertEquals("right", store.read(id)!!.concatToString())
        assertEquals("right", prompt.forBook(id, store).initial()!!.concatToString())
    }

    @Test fun `a wrong stored password is deleted before asking and unchecked input stays private`() = runTest {
        val store = Store().also { it.put(id, "stale".toCharArray()) }
        val prompt = BookPasswordPrompt()
        val book = prompt.forBook(id, store)
        assertEquals("stale", book.initial()!!.concatToString())
        assertNull(prompt.question.value)
        val next = async(start = CoroutineStart.UNDISPATCHED) { book.retry(true) }
        assertEquals(1, store.deleted)
        assertTrue(store.values.isEmpty())
        assertTrue(prompt.question.value!!.wrong)
        prompt.submit("fresh".toCharArray(), false)
        next.await()!!.fill('\u0000')
        book.accepted()
        assertTrue(store.values.isEmpty())
        assertNull(prompt.question.value)
    }

    @Test fun `cancel releases a waiting read and clears submitted input`() = runTest {
        val store = Store()
        val prompt = BookPasswordPrompt()
        val book = prompt.forBook(id, store)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { book.retry(false) }
        prompt.cancel()
        assertNull(waiting.await())
        assertNull(prompt.question.value)
        val secret = "unused".toCharArray()
        prompt.submit(secret, true)
        assertTrue(secret.all { it == '\u0000' })
        assertTrue(store.values.isEmpty())
    }

    @Test fun `a silent cover deletes a rejected stored password without a dialog`() = runTest {
        val store = Store().also { it.put(id, "stale".toCharArray()) }
        val cover = store.silent(id)
        assertEquals("stale", cover.initial()!!.concatToString())
        assertNull(cover.retry(true))
        assertTrue(store.values.isEmpty())
        assertEquals(1, store.deleted)
    }

    @Test fun `asking without a failed stored attempt does not delete the stored password`() = runTest {
        val store = Store().also { it.put(id, "right".toCharArray()) }
        val prompt = BookPasswordPrompt()
        val book = prompt.forBook(id, store)
        book.initial()!!.fill('\u0000')
        val next = async(start = CoroutineStart.UNDISPATCHED) { book.retry(false) }
        assertEquals(0, store.deleted)
        assertEquals("right", store.read(id)!!.concatToString())
        prompt.cancel()
        assertNull(next.await())
    }

    @Test fun `a cancelled read drops its answer and can ask again`() = runTest {
        val store = Store()
        val prompt = BookPasswordPrompt()
        val book = prompt.forBook(id, store)
        val first = async(start = CoroutineStart.UNDISPATCHED) { book.retry(false) }
        val secret = "unverified".toCharArray()
        prompt.submit(secret, true)
        first.await()!!.fill('\u0000')
        book.discard()
        assertTrue(secret.all { it == '\u0000' })
        assertNull(prompt.question.value)
        assertTrue(store.values.isEmpty())
        val next = async(start = CoroutineStart.UNDISPATCHED) { book.retry(false) }
        assertFalse(prompt.question.value!!.wrong)
        prompt.cancel()
        assertNull(next.await())
    }
}
