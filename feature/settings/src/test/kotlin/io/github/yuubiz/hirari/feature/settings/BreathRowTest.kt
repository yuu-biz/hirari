package io.github.yuubiz.hirari.feature.settings

import android.Manifest
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.yuubiz.hirari.core.data.GlobalSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals

/** 息でめくる asks for the microphone only when it turns on (docs/spec.md section 4.11). */
@RunWith(RobolectricTestRunner::class)
class BreathRowTest {
    @get:Rule
    val rule = createComposeRule()

    private val row = SettingsTable.filterIsInstance<ToggleSetting>().single { it.key == "breathControl" }

    private fun tap(settings: GlobalSettings): List<GlobalSettings> {
        val saved = mutableListOf<GlobalSettings>()
        rule.setContent { SettingRow(row, settings, saveFailures = 0, onChange = { saved += it(settings) }, onOpen = {}) }
        rule.onNodeWithText(row.label).performClick()
        rule.waitForIdle()
        return saved
    }

    @Test
    fun `without the microphone turning on asks and saves nothing yet`() {
        assertEquals(emptyList(), tap(GlobalSettings()))
    }

    @Test
    fun `with the microphone it turns on at once`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(listOf(true), tap(GlobalSettings()).map { it.breathControl })
    }

    @Test
    fun `turning off never asks`() {
        assertEquals(listOf(false), tap(GlobalSettings(breathControl = true)).map { it.breathControl })
    }
}
