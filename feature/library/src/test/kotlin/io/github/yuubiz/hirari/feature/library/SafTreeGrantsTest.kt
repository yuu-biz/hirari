package io.github.yuubiz.hirari.feature.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.test.assertEquals

@Implements(ContentResolver::class)
class GrantResolverShadow {
    companion object { val released = mutableListOf<Pair<Uri, Int>>() }
    @Implementation fun releasePersistableUriPermission(uri: Uri, flags: Int) { released += uri to flags }
}

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [GrantResolverShadow::class])
class SafTreeGrantsTest {
    @Test fun `unused place and save trees return both persisted grants`() = runTest {
        GrantResolverShadow.released.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val grants = SafTreeGrants(context.contentResolver)
        val place = UriString("content://books/tree/place")
        val save = UriString("content://books/tree/save")
        grants.release(place)
        grants.releaseWrite(save)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertEquals(listOf(Uri.parse(place.value) to flags, Uri.parse(save.value) to flags), GrantResolverShadow.released)
    }
}
