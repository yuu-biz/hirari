package io.github.yuubiz.hirari

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import io.github.yuubiz.hirari.core.data.ProgressWriter
import io.github.yuubiz.hirari.core.data.Storage
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.feature.library.LibraryServices
import io.github.yuubiz.hirari.feature.library.SafFolderLister
import io.github.yuubiz.hirari.feature.library.SafTreeGrants
import io.github.yuubiz.hirari.feature.reader.ReaderServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "HirariApp"

class HirariApp : Application() {
    /** Outlives every screen; the progress writer finishes its writes here after the reader is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Built off the main thread, since opening the storage touches the disk. */
    lateinit var services: Deferred<ReaderServices>
        private set

    lateinit var library: Deferred<LibraryServices>
        private set

    /** The start in progress; it outlives an activity recreated before the start finished. */
    internal var start: StartGate? = null

    override fun onCreate() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) enableStrictMode()
        super.onCreate()
        services = appScope.async {
            val storage = Storage(this@HirariApp)
            appScope.launch { reconcile(storage) }
            ReaderServices(storage, ProgressWriter(storage.progress, appScope), ImageDecoding.forDevice(this@HirariApp), appScope)
        }
        library = appScope.async {
            val storage = services.await().storage
            LibraryServices(
                locations = storage.locations,
                books = storage.books,
                history = storage.history,
                bookmarks = storage.bookmarks,
                progress = storage.progress,
                settings = storage.settings,
                grants = SafTreeGrants(contentResolver),
                folders = SafFolderLister(contentResolver),
                launch = storage.launch,
            )
        }
    }

    private fun isAppClass(name: String): Boolean =
        runCatching { Class.forName(name, false, classLoader).classLoader === classLoader }.getOrDefault(false)

    /**
     * Start-up reconciliation of the thumbnail index against the files (docs/spec.md section 5.4). Runs on
     * [appScope], off the main thread, and a failure leaves the stale state until the next start. The SAF grants
     * are synced by the start's plan, which needs them first.
     */
    private suspend fun reconcile(storage: Storage) {
        attempt("reconciling the thumbnail cache") { storage.thumbnails.reconcile() }
    }

    private suspend fun attempt(what: String, step: suspend () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "start-up failed at $what", e)
        }
    }

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
