package io.github.yuubiz.hirari

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.feature.library.DeviceSaves
import io.github.yuubiz.hirari.feature.library.SaveRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/**
 * The foreground service and the notifications of 端末に保存 (docs/spec.md section 4.5), on a device: a save that does
 * not finish shows the ongoing notification with 取り消す, which ends the save and the service; a save that fails
 * leaves a result notification. The server is a socket on the device that accepts and says nothing, or a closed one.
 */
class SaveServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as HirariApp
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val held = ArrayList<Socket>()
    private var silent: ServerSocket? = null

    @Before
    fun grant() {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37) automation.grantRuntimePermission(context.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        manager.cancelAll()
    }

    @After
    fun close() {
        held.forEach { runCatching { it.close() } }
        silent?.close()
        manager.cancelAll()
    }

    private fun silentServer(): LibraryLocation.SmbShare {
        val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).also { silent = it }
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                synchronized(held) { held += client }
            }
        }.apply { isDaemon = true }.start()
        return place(server.localPort)
    }

    private fun place(port: Int) = LibraryLocation.SmbShare(LocationId(901), "Silent", "127.0.0.1", port, "books", "", null, null, null)

    private fun request(place: LibraryLocation.SmbShare) = SaveRequest(place, "a.zip", "a.zip", false, UriString("content://com.android.externalstorage.documents/tree/primary%3AHirariTest"))

    private suspend fun saves(): DeviceSaves = app.library.await().saves

    private suspend fun until(what: String, check: () -> Boolean) {
        val reached = withTimeoutOrNull(20_000) {
            while (!check()) delay(100)
            true
        }
        assertTrue("timed out waiting for $what", reached == true)
    }

    @Test
    fun anOngoingSaveShowsTheNotificationAndItsCancelActionEndsTheSaveAndTheService() = runBlocking {
        val saves = saves()
        saves.start(request(silentServer()))
        until("ongoing notification") { manager.activeNotifications.any { it.id == SaveNotifications.ONGOING_ID } }
        val ongoing = manager.activeNotifications.single { it.id == SaveNotifications.ONGOING_ID }.notification
        assertTrue(ongoing.extras.getCharSequence("android.title").toString().contains("a.zip"))
        val cancel = ongoing.actions.single()
        assertEquals("取り消す", cancel.title.toString())

        cancel.actionIntent.send()
        until("save cancelled") { saves.jobs.value.isEmpty() }
        until("service stopped") { manager.activeNotifications.none { it.id == SaveNotifications.ONGOING_ID } }
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun aFailedSaveLeavesAResultNotificationAndStopsTheService() = runBlocking {
        val saves = saves()
        val refusing = ServerSocket(0).use { it.localPort }
        saves.start(request(place(refusing)))
        until("result notification") { manager.activeNotifications.any { it.id != SaveNotifications.ONGOING_ID } }
        val result = manager.activeNotifications.first { it.id != SaveNotifications.ONGOING_ID }.notification
        val title = result.extras.getCharSequence("android.title").toString()
        assertTrue(title, title.contains("a.zip") && title.contains("保存できませんでした"))
        until("service stopped") { saves.jobs.value.isEmpty() && manager.activeNotifications.none { it.id == SaveNotifications.ONGOING_ID } }
        assertNotNull(result)
    }
}
