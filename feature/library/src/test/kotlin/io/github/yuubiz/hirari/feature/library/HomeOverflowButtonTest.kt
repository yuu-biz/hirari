package io.github.yuubiz.hirari.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class HomeOverflowButtonTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the overflow is an icon with the file list spoken name and opens the menu`() {
        var opened = 0
        rule.setContent { MaterialTheme { HomeOverflowButton { opened++ } } }
        rule.onNodeWithText("メニュー").assertDoesNotExist()
        rule.onNodeWithContentDescription("その他の操作").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }
}
