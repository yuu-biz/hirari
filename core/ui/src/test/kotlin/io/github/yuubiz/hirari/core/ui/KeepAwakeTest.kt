package io.github.yuubiz.hirari.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.yuubiz.hirari.core.model.KeepAwake
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** docs/spec.md section 4.10, 画面を消さない, on the composition's virtual clock. */
@RunWith(RobolectricTestRunner::class)
class KeepAwakeTest {
    @get:Rule
    val rule = createComposeRule()

    private var mode by mutableStateOf(KeepAwake.TenMinutes)
    private var touched by mutableIntStateOf(0)
    private var awake: Boolean? = null

    private fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent { awake = keepsAwake(mode, touched) }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun after(ms: Long): Boolean? {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(ms)
        return awake
    }

    @Test
    fun `ten minutes keeps the screen on until ten minutes pass without input, and an input starts them again`() {
        show()
        assertEquals(true, after(AWAKE_AFTER_INPUT_MS - 1_000))
        touched++
        assertEquals(true, after(AWAKE_AFTER_INPUT_MS - 1_000))
        assertEquals(false, after(2_000))
        touched++
        assertEquals(true, after(1_000))
    }

    @Test
    fun `always keeps it on however long nothing happens, and the device's setting never does`() {
        mode = KeepAwake.Always
        show()
        assertEquals(true, after(3 * AWAKE_AFTER_INPUT_MS))
        mode = KeepAwake.System
        assertEquals(false, after(1_000))
        touched++
        assertEquals(false, after(1_000))
    }
}
