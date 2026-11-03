package io.github.yuubiz.hirari.feature.library

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
        rule.setContent { FilterField(value = "", onValueChange = {}, label = "絞り込み", placeholder = "名前の一部") }
        rule.waitForIdle()
        rule.onNodeWithText("絞り込み").assertIsFocused()
    }
}
