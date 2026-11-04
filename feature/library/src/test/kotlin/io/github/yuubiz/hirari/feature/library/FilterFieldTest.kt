package io.github.yuubiz.hirari.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The filter of a folder takes the focus when it opens, so the first tap on 絞り込み is enough to type. */
@RunWith(RobolectricTestRunner::class)
class FilterFieldTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theFieldHasTheFocusAsSoonAsItAppears() {
        rule.setContent { FilterField(initial = "", onValueChange = {}, label = "絞り込み", placeholder = "名前の一部") }
        rule.waitForIdle()
        rule.onNodeWithText("絞り込み").assertIsFocused()
    }

    /** The list's state reaches the screen a little after each keystroke; the field must keep its own cursor. */
    @Test
    fun typingInTheMiddleKeepsTheCursorWhileTheListCatchesUp() {
        var reported = ""
        rule.setContent {
            var filter by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            FilterField(
                initial = filter,
                onValueChange = { text ->
                    reported = text
                    scope.launch {
                        delay(50)
                        filter = text
                    }
                },
                label = "絞り込み",
                placeholder = "名前の一部",
            )
        }
        val field = rule.onNodeWithText("絞り込み")
        field.performTextInput("ab")
        rule.mainClock.advanceTimeBy(100)
        field.performTextInputSelection(TextRange(1))
        field.performTextInput("X")
        rule.mainClock.advanceTimeBy(100)
        field.assert(hasText("aXb"))
        rule.runOnIdle { assertEquals("aXb", reported) }
    }
}
