package io.github.yuubiz.hirari

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.yuubiz.hirari.feature.library.DeviceSaves
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a save to the device runs and shows its ongoing notification (docs/spec.md section
 * 4.5). The saves themselves run in [DeviceSaves] on the app's scope; this service only watches them and ends when
 * none is left. The notification is updated at most every [UPDATE_INTERVAL_MS].
 */
class SaveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private lateinit var notifications: SaveNotifications

    override fun onCreate() {
        super.onCreate()
        notifications = SaveNotifications(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HirariApp
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getLongExtra(EXTRA_ID, NO_ID).takeIf { it != NO_ID }
            scope.launch { app.library.await().saves.let { if (id != null) it.cancel(id) else it.cancelAll() } }
            return START_NOT_STICKY
        }
        val ongoing = notifications.ongoing(emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SaveNotifications.ONGOING_ID, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SaveNotifications.ONGOING_ID, ongoing)
        }
        if (watching?.isActive != true) watching = scope.launch { watch(app.library.await().saves) }
        return START_NOT_STICKY
    }

    private suspend fun watch(saves: DeviceSaves) {
        saves.jobs.collect { jobs ->
            if (jobs.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@collect
            }
            getSystemService(android.app.NotificationManager::class.java).notify(SaveNotifications.ONGOING_ID, notifications.ongoing(jobs))
            delay(UPDATE_INTERVAL_MS)
        }
    }

    /** Android 15 ends a data sync service after six hours; the saves stop with it rather than run unseen. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as HirariApp).let { app -> scope.launch { app.library.await().saves.cancelAll() } }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "io.github.yuubiz.hirari.SAVE_CANCEL"
        const val EXTRA_ID = "id"
        private const val NO_ID = -1L
        const val UPDATE_INTERVAL_MS = 500L
    }
}
