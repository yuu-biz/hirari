package io.github.yuubiz.hirari.core.image.thumbnail

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * How the last process of this app ended, from the exit reasons the system keeps (API 30 and later). It asks the
 * system over binder, so call it off the main thread.
 */
internal fun previousExit(context: Context): PreviousExit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return PreviousExit.Unknown
    val exits = context.getSystemService(ActivityManager::class.java)
        ?.getHistoricalProcessExitReasons(context.packageName, 0, EXITS_LOOKED_AT)
        .orEmpty()
    val last = exits.firstOrNull { it.processName == context.packageName } ?: return PreviousExit.Unknown
    return when (last.reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> PreviousExit.Crashed
        else -> PreviousExit.Ended
    }
}

/** The newest exits looked at for the main process; another process of the app could only add a few. */
private const val EXITS_LOOKED_AT = 8
