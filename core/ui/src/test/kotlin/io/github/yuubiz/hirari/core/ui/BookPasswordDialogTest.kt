package io.github.yuubiz.hirari.core.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class BookPasswordDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `restoration keeps the opt out but discards the password text`() {
        val restoration = StateRestorationTester(rule)
        var submitted: Pair<String, Boolean>? = null
        restoration.setContent {
            HirariTheme(ThemeChoice()) {
                BookPasswordDialog(false, false, { chars, remember ->
                    submitted = chars.concatToString() to remember
                    chars.fill('\u0000')
                }, {})
            }
        }
        rule.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
        rule.onNode(hasSetTextAction()).performTextInput("private input")
        restoration.emulateSavedInstanceStateRestore()
        rule.onNode(isToggleable()).assertIsOff()
        rule.onNode(hasSetTextAction()).performTextInput("new input")
        rule.onNodeWithText("開く").performClick()
        rule.runOnIdle {
            assertEquals("new input", submitted!!.first)
            assertFalse(submitted!!.second)
        }
    }
}
