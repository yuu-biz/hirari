package io.github.yuubiz.hirari

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import io.github.yuubiz.hirari.core.data.ProgressWriter
import io.github.yuubiz.hirari.core.data.Storage
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.feature.reader.ReaderServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class HirariApp : Application() {
    /** Outlives every screen; the progress writer finishes its writes here after the reader is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Built off the main thread, since opening the storage touches the disk. */
    lateinit var services: Deferred<ReaderServices>
        private set

    override fun onCreate() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) enableStrictMode()
        super.onCreate()
        services = appScope.async {
            val storage = Storage(this@HirariApp)
            ReaderServices(storage, ProgressWriter(storage.progress, appScope), ImageDecoding.forDevice(this@HirariApp), appScope)
        }
    }

    private fun isAppClass(name: String): Boolean =
        runCatching { Class.forName(name, false, classLoader).classLoader === classLoader }.getOrDefault(false)

    /** docs/spec.md section 6.3. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .penaltyListener(Runnable::run) { violation ->
                    if (blame(violation.stackTrace.asList(), ::isAppClass) == IoCulprit.App) {
                        Handler(Looper.getMainLooper()).post { throw IllegalStateException("main-thread I/O", violation) }
                    }
                }
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .detectContentUriWithoutPermission()
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) detectUnsafeIntentLaunch() }
                .penaltyLog()
                .build(),
        )
    }
}
