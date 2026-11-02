package io.github.yuubiz.hirari.feature.settings

import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.data.FontImport
import io.github.yuubiz.hirari.core.data.FontStore
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StorageFontsTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fonts get() = StorageFonts(FontStore(temp.root.resolve("fonts")), context.contentResolver, UnconfinedTestDispatcher())

    private fun provide(uri: String, bytes: ByteArray): UriString {
        shadowOf(context.contentResolver).registerInputStream(Uri.parse(uri), ByteArrayInputStream(bytes))
        return UriString(uri)
    }

    @Test
    fun `a picked font is stored under the last segment of its address and listed`() = runBlocking {
        val library = fonts
        val outcome = library.import(provide("content://fonts/mincho.ttf", byteArrayOf(0, 1, 0, 0, 5, 6)))
        assertEquals(FontImport.Imported("mincho.ttf"), outcome)
        assertEquals(listOf("mincho.ttf"), library.list())
    }

    @Test
    fun `a picked file that is no font is refused`() = runBlocking {
        val library = fonts
        assertEquals(FontImport.NotAFont, library.import(provide("content://fonts/readme.txt", "hello".toByteArray())))
        assertEquals(emptyList(), library.list())
    }

    @Test
    fun `a file the provider cannot open is unreadable`() = runBlocking {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("provider died")
        }
        shadowOf(context.contentResolver).registerInputStream(Uri.parse("content://fonts/gone.ttf"), broken)
        assertEquals(FontImport.Unreadable, fonts.import(UriString("content://fonts/gone.ttf")))
    }

    @Test
    fun `the typeface dialog offers the imported fonts and the import button`() {
        var imports = 0
        var changed: GlobalSettings? = null
        rule.setContent {
            MaterialTheme {
                SettingDialog(
                    TypefaceSetting,
                    GlobalSettings(),
                    importedFonts = listOf("mincho.ttf"),
                    onImportFont = { imports++ },
                    onChange = { transform -> changed = transform(GlobalSettings()) },
                    onClose = {},
                )
            }
        }
        rule.onNodeWithText("明朝体").assertExists()
        rule.onNodeWithText("ゴシック体").assertExists()
        rule.onNodeWithText("mincho.ttf").performClick()
        assertEquals(TextTypeface.Imported("mincho.ttf"), assertIs<GlobalSettings>(changed).textTypeface)
        rule.onNodeWithText(context.getString(R.string.settings_import_font)).performClick()
        assertTrue(imports == 1)
    }
}
