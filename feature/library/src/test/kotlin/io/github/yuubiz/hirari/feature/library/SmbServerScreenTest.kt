package io.github.yuubiz.hirari.feature.library

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The password field of the SMB server form never lets its text out to the accessibility tree (docs/spec.md section 4.5). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SmbServerScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun `the password field is a password to a screen reader and exposes none of what was typed`() {
        val model = SmbServerViewModel(CompletableDeferred(Fixture().services), null)
        rule.setContent { MaterialTheme { SmbServerScreen(model, onClose = {}) } }

        val field = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        field.performTextInput(TYPED)

        assertNotNull(field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Password))
        val info = checkNotNull(checkNotNull(host(rule.activity.window.decorView).accessibilityNodeProvider).createAccessibilityNodeInfo(field.fetchSemanticsNode().id))
        assertTrue(info.isPassword, "the accessibility node is not a password")
        assertEquals("•".repeat(TYPED.length), info.text.toString(), "the accessibility node carries the typed password")
    }

    private fun host(view: View): View {
        if (view.accessibilityNodeProvider != null) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) host(view.getChildAt(i)).takeIf { it.accessibilityNodeProvider != null }?.let { return it }
        return view
    }

    private companion object {
        const val TYPED = "hunter2-secret"
    }
}
