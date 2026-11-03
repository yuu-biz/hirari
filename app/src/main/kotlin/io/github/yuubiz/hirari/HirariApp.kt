package io.github.yuubiz.hirari

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import io.github.yuubiz.hirari.core.data.ProgressWriter
import io.github.yuubiz.hirari.core.data.Storage
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.thumbnail.Thumbnails
import io.github.yuubiz.hirari.feature.library.LibraryServices
import io.github.yuubiz.hirari.core.source.smb.SmbPrefetchCache
import io.github.yuubiz.hirari.core.source.smb.networkAvailable
import io.github.yuubiz.hirari.feature.library.AndroidLocalNetwork
import io.github.yuubiz.hirari.feature.library.Covers
import io.github.yuubiz.hirari.feature.library.DeviceArchives
import io.github.yuubiz.hirari.feature.library.DeviceCoverBooks
import io.github.yuubiz.hirari.feature.library.DeviceSaves
import io.github.yuubiz.hirari.feature.library.SafSaveFolders
import io.github.yuubiz.hirari.feature.library.ThumbnailsSource
import io.github.yuubiz.hirari.feature.library.SmbjPlaces
import io.github.yuubiz.hirari.feature.library.SafFolderLister
import io.github.yuubiz.hirari.feature.library.SafTreeGrants
import io.github.yuubiz.hirari.feature.reader.ReaderServices
import io.github.yuubiz.hirari.feature.text.SmbFiles
import io.github.yuubiz.hirari.feature.text.TextReaderServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "HirariApp"

/** docs/spec.md section 5.4, メモリ: the decoded covers take 1/8 of the memory budget. */
private const val COVER_MEMORY_SHARE = 8

class HirariApp : Application() {
    /** Outlives every screen; the progress writer finishes its writes here after the reader is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Built off the main thread, since opening the storage touches the disk. */
    lateinit var services: Deferred<ReaderServices>
        private set

    lateinit var library: Deferred<LibraryServices>
        private set

    /** Shares the image reader's [ProgressWriter], so both write positions the same way. */
    lateinit var text: Deferred<TextReaderServices>
        private set

    /** The last start of this process; it outlives an activity recreated before the start finished. */
    internal var start: AppStart? = null

    override fun onCreate() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) enableStrictMode()
        super.onCreate()
        val storage = appScope.async { Storage(this@HirariApp) }
        val smbPool = appScope.async {
            SmbServerPool(storage.await().credentials, appScope, getSystemService(ConnectivityManager::class.java).networkAvailable())
        }
        val smbPlaces = appScope.async {
            SmbjPlaces(smbPool.await().shares, storage.await().credentials, AndroidLocalNetwork(this@HirariApp), appScope)
        }
        services = appScope.async {
            val storage = storage.await()
            appScope.launch { reconcile(storage) }
            val siblings = FolderSiblings(storage.locations, SafFolderLister(contentResolver), smbPlaces.await(), storage.settings)
            ReaderServices(
                storage,
                ProgressWriter(storage.progress, appScope),
                ImageDecoding.forDevice(this@HirariApp),
                appScope,
                siblings,
                smbPool.await(),
                SmbPrefetchCache(File(cacheDir, "smb")),
            )
        }
        library = appScope.async {
            val storage = services.await().storage
            val smb = smbPlaces.await()
            val folders = SafFolderLister(contentResolver)
            val thumbnails = Thumbnails.forDevice(this@HirariApp, ThumbnailStoreAdapter(storage.thumbnails))
            val covers = Covers(
                source = ThumbnailsSource(thumbnails),
                books = DeviceCoverBooks(contentResolver, cacheDir, folders, smb),
                memoryBytes = ImageDecoding.deviceBudget(this@HirariApp) / COVER_MEMORY_SHARE,
                density = { resources.displayMetrics.density },
            )
            LibraryServices(
                locations = storage.locations,
                books = storage.books,
                history = storage.history,
                bookmarks = storage.bookmarks,
                progress = storage.progress,
                settings = storage.settings,
                grants = SafTreeGrants(contentResolver),
                folders = folders,
                smb = smb,
                archives = DeviceArchives(contentResolver, cacheDir, smb),
                launch = storage.launch,
                coverIndex = storage.covers,
                covers = covers,
                saves = DeviceSaves(smb, SafSaveFolders(contentResolver), appScope) { result -> SaveNotifications(this@HirariApp).finished(result) },
                appScope = appScope,
            )
        }
        appScope.launch { runSaveService() }
        text = appScope.async {
            val smb = smbPlaces.await()
            services.await().let {
                TextReaderServices(
                    it.storage,
                    it.progress,
                    appScope,
                    SmbFiles { place, path -> smb.open(place, path) },
                    TextFolderSiblings(it.storage.locations, SafFolderLister(contentResolver), smb, it.storage.settings),
                )
            }
        }
    }

    /** Runs the foreground service for as long as a save to the device is under way (docs/spec.md section 4.5). */
    private suspend fun runSaveService() {
        library.await().saves.jobs.map { it.isNotEmpty() }.distinctUntilChanged().filter { it }.collect {
            try {
                startForegroundService(Intent(this, SaveService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "the save service could not start", e)
            }
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
