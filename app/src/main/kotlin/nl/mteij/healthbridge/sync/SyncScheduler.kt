package nl.mteij.healthbridge.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
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
    override suspend fun doWork(): Result {
        val full = inputData.getBoolean(KEY_FULL, false)
        val trigger = inputData.getString(KEY_TRIGGER) ?: "scheduled"
        return when (Graph.engine.run(trigger, full)) {
            Outcome.SUCCESS -> Result.success()
            Outcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            Outcome.FAILED -> Result.failure()
        }
    }

    companion object {
        const val KEY_FULL = "full"
        const val KEY_TRIGGER = "trigger"
        private const val MAX_ATTEMPTS = 5
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
