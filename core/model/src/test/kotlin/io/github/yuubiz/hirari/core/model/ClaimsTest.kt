package io.github.yuubiz.hirari.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ClaimsTest {
    private val applied = mutableListOf<String?>()
    private val claims = Claims<String> { applied += it }

    @Test
    fun theOldScreenLettingGoAfterTheNewOneClaimedLeavesTheNewOne() {
        val old = claims.claim("old")
        claims.claim("new")
        old.release()
        assertEquals("new", claims.current)
        assertEquals(listOf<String?>("old", "new"), applied)
    }

    @Test
    fun theNewestLettingGoGivesTheScreenBackToTheOneBeforeItAndThenToNothing() {
        val old = claims.claim("old")
        val new = claims.claim("new")
        new.release()
        assertEquals("old", claims.current)
        old.release()
        assertEquals(null, claims.current)
        assertEquals(listOf("old", "new", "old", null), applied)
    }

    @Test
    fun anOlderClaimThatChangesDoesNotTakeTheScreen() {
        val old = claims.claim("old")
        val new = claims.claim("new")
        old.value = "old, changed"
        assertEquals("new", claims.current)
        new.value = "new, changed"
        new.release()
        assertEquals(listOf<String?>("old", "new", "new, changed", "old, changed"), applied)
    }

    @Test
    fun aClaimLetGoTwiceOrChangedAfterwardsDoesNothing() {
        val first = claims.claim("first")
        first.release()
        first.release()
        first.value = "late"
        claims.claim("second")
        assertEquals("second", claims.current)
        assertEquals(listOf("first", null, "second"), applied)
    }

    @Test
    fun aValueThatDecidesNothingNewIsNotAppliedAgain() {
        claims.claim("same")
        val second = claims.claim("same")
        second.release()
        assertEquals(listOf<String?>("same"), applied)
    }
}
