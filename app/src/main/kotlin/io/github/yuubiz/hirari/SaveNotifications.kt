package io.github.yuubiz.hirari

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import io.github.yuubiz.hirari.feature.library.SaveFinished
import io.github.yuubiz.hirari.feature.library.SaveJob
import io.github.yuubiz.hirari.feature.library.text
import io.github.yuubiz.hirari.feature.library.R as Library
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the ongoing notification of the saves to the device says (docs/spec.md section 4.5): one save shows its name and
 * `45% 12.3 MB / 27.0 MB`; several show their count and the progress of all of them together. [percent] is null while
 * no save has read a block yet.
 */
data class SavingSummary(val name: String?, val count: Int, val progressText: String?, val percent: Int?)

fun summarize(jobs: List<SaveJob>): SavingSummary {
    val known = jobs.mapNotNull { it.progress }
    val done = known.sumOf { it.bytesDone }
    val total = known.sumOf { it.bytesTotal }
    val percent = if (known.isEmpty()) null else if (total > 0) (done * 100 / total).coerceIn(0, 100).toInt() else 0
    val text = jobs.singleOrNull()?.progress?.text()
    return SavingSummary(jobs.singleOrNull()?.name, jobs.size, text, percent)
}

/** The notifications of 端末に保存: one ongoing, which the service shows, and one for each save that ended. */
class SaveNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, context.getString(R.string.save_channel_progress), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(RESULT_CHANNEL, context.getString(R.string.save_channel_result), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun ongoing(jobs: List<SaveJob>): Notification {
        val summary = summarize(jobs)
        val title = when {
            summary.count == 0 -> context.getString(R.string.save_notification_idle)
            summary.name != null -> context.getString(Library.string.save_running, summary.name)
            else -> context.getString(R.string.save_notification_many, summary.count)
        }
        val builder = Notification.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        summary.progressText?.let(builder::setContentText)
        when (val percent = summary.percent) {
            null -> if (summary.count > 0) builder.setProgress(0, 0, true)
            else -> builder.setProgress(PERCENT_MAX, percent, false)
        }
        if (summary.count > 0) {
            val cancel = if (summary.count == 1) R.string.save_notification_cancel else R.string.save_notification_cancel_all
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel), context.getString(cancel), cancelIntent(jobs.singleOrNull()?.id)).build())
        }
        return builder.build()
    }

    /** Posts how a save ended. Without the permission to notify (API 33), the system drops it. */
    fun finished(result: SaveFinished) {
        val notification = Notification.Builder(context, RESULT_CHANNEL)
            .setSmallIcon(if (result is SaveFinished.Saved) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(result.text(context.resources))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(RESULT_ID_BASE + nextResult.getAndIncrement() % RESULT_ID_SPAN, notification)
    }

    private fun openApp(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun cancelIntent(id: Long?): PendingIntent {
        val intent = Intent(context, SaveService::class.java).setAction(SaveService.ACTION_CANCEL)
        if (id != null) intent.putExtra(SaveService.EXTRA_ID, id)
        return PendingIntent.getService(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ONGOING_ID = 1
        private const val RESULT_ID_BASE = 100
        private const val RESULT_ID_SPAN = 50
        private const val PERCENT_MAX = 100
        private const val PROGRESS_CHANNEL = "save_progress"
        private const val RESULT_CHANNEL = "save_result"
        private val nextResult = AtomicInteger()
    }
}
