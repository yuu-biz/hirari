package io.github.yuubiz.hirari

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptScreensTest {
    @Test
    fun aDiscardedScreenNeverLoadsWhileAScreenOpenedAfterTheVerdictDoes() {
        val kept = KeptScreens(restoring = true)
        assertFalse(kept.loads("reader"))

        kept.decide(adopt = false)
        assertFalse("the discarded reader loaded while leaving", kept.loads("reader"))
        assertTrue(kept.loads("folder"))
    }

    @Test
    fun anAdoptedScreenLoadsOnceTheVerdictIsIn() {
        val kept = KeptScreens(restoring = true)
        assertFalse(kept.loads("reader"))

        kept.decide(adopt = true)
        assertTrue(kept.loads("reader"))
    }

    @Test
    fun aStartThatRestoresNothingHoldsNothingBack() {
        val kept = KeptScreens(restoring = false)
        assertTrue(kept.loads("reader"))
        kept.decide(adopt = false)
        assertTrue(kept.loads("reader"))
    }
}
