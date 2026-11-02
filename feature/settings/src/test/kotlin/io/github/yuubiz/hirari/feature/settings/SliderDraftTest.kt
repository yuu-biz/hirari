package io.github.yuubiz.hirari.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SliderDraftTest {
    @Test
    fun `a slider at rest shows the stored tick`() {
        assertEquals(5, SliderDraft(count = 40).shown(stored = 5))
    }

    @Test
    fun `a drag shows the tick under the finger`() {
        val draft = SliderDraft(count = 40)
        draft.drag(30.999998f)
        assertEquals(31, draft.shown(stored = 5))
    }

    @Test
    fun `a release returns the tick and keeps showing it until the stored value moves`() {
        val draft = SliderDraft(count = 40)
        draft.drag(30.999998f)
        assertEquals(31, draft.release(stored = 5))
        assertEquals(31, draft.shown(stored = 5))
    }

    @Test
    fun `a drag back to the stored tick shows the stored tick`() {
        val draft = SliderDraft(count = 40)
        draft.drag(20f)
        draft.drag(4.000001f)
        assertEquals(4, draft.release(stored = 4))
        assertEquals(4, draft.shown(stored = 4))
    }
}
