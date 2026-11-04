package io.github.yuubiz.hirari

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.model.LayoutMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * 組み方 set to 1 ページ for a test that counts views as pages, and put back after it. The default, 見開き, pairs the
 * pages of the corpus book (docs/spec.md section 2.5).
 */
class OnePageLayout : ExternalResource() {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HirariApp
    private var before: LayoutMode? = null

    override fun before() {
        runBlocking {
            val settings = app.services.await().storage.settings
            before = settings.settings.first().layoutMode
            settings.update { it.copy(layoutMode = LayoutMode.Single) }
        }
    }

    override fun after() {
        val mode = before ?: return
        runBlocking { app.services.await().storage.settings.update { it.copy(layoutMode = mode) } }
    }
}
