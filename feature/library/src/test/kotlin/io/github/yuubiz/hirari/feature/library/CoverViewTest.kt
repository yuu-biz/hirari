package io.github.yuubiz.hirari.feature.library

import android.graphics.Bitmap
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbImage
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbResult
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbnailKey
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CoverViewTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `a cached row shows its image on first composition and keeps it while the viewport is pending`() {
        val source = FakeThumbnails()
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()
        val spec = CoverSpec.Book(BookId("cached"), Instant.EPOCH, 10, null,
            ReadingDirection.RightToLeft, CoverOrigin.Document(UriString("content://cached")))
        val ref = CoverRef(spec, 300)
        source.result = ThumbResult.Ready(ThumbImage.Stored(File("cached.webp")),
            ThumbnailKey(spec.id, spec.modified, spec.byteSize, null, ref.sizePx, spec.direction))
        val covers = Covers(source, FakeCoverBooks(), 1 shl 20, { 2f }, Dispatchers.Unconfined) { image }
        runBlocking { covers.show(ref).first() }
        val focus = CoverFocus()
        val compositions = mutableListOf<CoverState<ImageBitmap>>()
        rule.setContent {
            val state = rememberCoverState(ref, covers, focus, onScreen = true).value
            SideEffect { compositions += state }
        }
        rule.waitForIdle()
        assertEquals(CoverState.Loaded(image), compositions.first())
        assertTrue(compositions.all { it == CoverState.Loaded(image) }, "pending viewport hid the memory hit")
        assertEquals(1, source.watches.size, "composing the row must not start another store lookup")

        // Real results after invalidation must still replace the cached image.
        rule.runOnIdle { focus.publish(ref.key, CoverState.Failed) }
        rule.waitForIdle()
        assertEquals(CoverState.Failed, compositions.last())
    }
}
