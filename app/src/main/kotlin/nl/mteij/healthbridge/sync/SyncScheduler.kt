package nl.mteij.healthbridge.sync

import android.content.Context
import androidx.work.BackoffPolicy
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import nl.mteij.healthbridge.R
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.data.Prefs
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(Progress())

    override suspend fun doWork(): Result {
        // only the first attempt starts a new full import; retries resume the pending types
        val full = inputData.getBoolean(KEY_FULL, false) && runAttemptCount == 0
        val trigger = inputData.getString(KEY_TRIGGER) ?: "scheduled"
        val long = full || trigger == "manual" || Graph.prefs.pendingFull.isNotEmpty()

        // long runs are promoted to a foreground task so Android does not stop them halfway
        val notifier = if (long) {
            runCatching { setForeground(foregroundInfo(Progress())) }
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                Graph.engine.progress.filterNotNull().collect { runCatching { setForeground(foregroundInfo(it)) } }
            }
        } else {
            null
        }
        val outcome = try {
            Graph.engine.run(trigger, full)
        } finally {
            notifier?.cancel()
        }
        return when (outcome) {
            Outcome.SUCCESS -> Result.success()
            Outcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            Outcome.FAILED -> Result.failure()
        }
    }

    private fun foregroundInfo(progress: Progress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val text = if (progress.total > 0) "${progress.label} · ${progress.index + 1}/${progress.total}" else applicationContext.getString(R.string.syncing)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationContext.getString(R.string.syncing))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(progress.total, progress.index, progress.total == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_FULL = "full"
        const val KEY_TRIGGER = "trigger"
        private const val MAX_ATTEMPTS = 8
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 1
    }
}

object SyncScheduler {
    private const val PERIODIC = "healthbridge-periodic"
    const val MANUAL = "healthbridge-manual"

    private fun constraints(prefs: Prefs) = Constraints.Builder()
        .setRequiredNetworkType(if (prefs.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun schedule(context: Context, prefs: Prefs) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            prefs.syncIntervalMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES,
        )
            .setConstraints(constraints(prefs))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to "scheduled"))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun syncNow(context: Context, prefs: Prefs, full: Boolean = false) {
        Graph.engine.markQueued()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SyncWorker.KEY_FULL to full, SyncWorker.KEY_TRIGGER to "manual"))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(MANUAL)
    }
}
