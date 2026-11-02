package io.github.yuubiz.hirari.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class OwnershipTest {
    private val closed = mutableListOf<String>()
    private val owner = Ownership<String> { closed += it }

    @Test
    fun releasingClosesWhatWasTaken() {
        owner.take("book")
        assertEquals(emptyList(), closed)
        owner.release()
        assertEquals(listOf("book"), closed)
    }

    @Test
    fun releasingTwiceClosesOnce() {
        owner.take("book")
        owner.release()
        owner.release()
        assertEquals(listOf("book"), closed)
    }

    @Test
    fun releasingBeforeAnythingWasTakenClosesNothing() {
        owner.release()
        assertEquals(emptyList(), closed)
    }

    @Test
    fun aBookThatOpensAfterTheReleaseIsClosedAtOnce() {
        owner.release()
        owner.take("late")
        assertEquals(listOf("late"), closed)
    }
}
