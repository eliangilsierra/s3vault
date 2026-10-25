package com.s3vault.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.s3vault.app.S3VaultApp
import com.s3vault.app.security.SafeLog
import com.s3vault.app.settings.BackupSettings
import java.util.concurrent.TimeUnit

/** Abstraction over WorkManager so controllers can be tested without Android. */
interface WorkScheduler {
    fun kickBackup(wifiOnly: Boolean)
    fun cancelBackup()
    fun configureAuto(settings: BackupSettings)
    fun kickRestore(wifiOnly: Boolean)
    fun cancelRestore()

    /** Opt-in pass that reads the capture date of S3 objects that have none (HEAD requests). */
    fun kickCloudDates(wifiOnly: Boolean)
    fun cancelCloudDates()
}

/**
 * Persistent, constraint-aware scheduling. WorkManager decides when work actually runs (Doze, battery,
 * standby buckets); nothing here promises immediate execution, and no foreground service is used.
 */
class WorkManagerScheduler(private val context: Context) : WorkScheduler {
    private val wm get() = WorkManager.getInstance(context)

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    override fun kickBackup(wifiOnly: Boolean) {
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE would cancel an in-flight run; KEEP lets the running worker pick up newly queued items.
        wm.enqueueUniqueWork(BACKUP_WORK, ExistingWorkPolicy.KEEP, req)
    }

    override fun cancelBackup() {
        wm.cancelUniqueWork(BACKUP_WORK)
    }

    override fun configureAuto(settings: BackupSettings) {
        if (!settings.autoBackup || settings.paused) {
            wm.cancelUniqueWork(AUTO_WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(settings.intervalHours.toLong().coerceAtLeast(1), TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putBoolean(BackupWorker.KEY_SCAN, true).build())
            .build()
        // UPDATE applies changed interval/constraints without resetting a running worker.
        wm.enqueueUniquePeriodicWork(AUTO_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    override fun kickRestore(wifiOnly: Boolean) {
        val req = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(RESTORE_WORK, ExistingWorkPolicy.KEEP, req)
    }

    override fun cancelRestore() {
        wm.cancelUniqueWork(RESTORE_WORK)
    }

    override fun kickCloudDates(wifiOnly: Boolean) {
        val req = OneTimeWorkRequestBuilder<CloudDatesWorker>()
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(DATES_WORK, ExistingWorkPolicy.KEEP, req)
    }

    override fun cancelCloudDates() {
        wm.cancelUniqueWork(DATES_WORK)
    }

    companion object {
        const val DATES_WORK = "s3vault-cloud-dates"
        const val BACKUP_WORK = "s3vault-backup"
        const val AUTO_WORK = "s3vault-backup-auto"
        const val RESTORE_WORK = "s3vault-restore"
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as S3VaultApp).container
        val settings = c.settings.currentBackup()
        if (settings.paused) return Result.success()
        return try {
            if (inputData.getBoolean(KEY_SCAN, false)) {
                c.library.refresh()
                if (settings.autoBackup) c.backup.enqueuePending(settings.includeVideos, kick = false)
            }
            when (val out = c.backupEngine.run(settings.maxConcurrency)) {
                is RunOutcome.Completed -> {
                    c.settings.setNeedsCredentials(false)
                    // Same network constraints as the uploads; a few KB per photo, capped per run.
                    c.backupEngine.backfillPreviews()
                    Result.success()
                }
                is RunOutcome.RetryLater ->
                    // Exponential backoff via WorkManager; give up after a while, items stay QUEUED for the next trigger.
                    if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
                is RunOutcome.NeedsCredentials -> { c.settings.setNeedsCredentials(true); Result.success() }
                is RunOutcome.Blocked -> { SafeLog.w(TAG, "Backup blocked: ${out.reason}"); Result.success() }
                RunOutcome.NotConfigured -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.e(TAG, "Backup worker failed", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_SCAN = "scan"
        private const val MAX_RETRIES = 8
        private const val TAG = "BackupWorker"
    }
}

class RestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as S3VaultApp).container
        return try {
            when (c.restoreEngine.run()) {
                is RestoreOutcome.RetryLater -> if (runAttemptCount < 8) Result.retry() else Result.success()
                is RestoreOutcome.NeedsCredentials -> { c.settings.setNeedsCredentials(true); Result.success() }
                else -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.e("RestoreWorker", "Restore worker failed", e)
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }
}

class CloudDatesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as S3VaultApp).container
        return try {
            when (c.cloudDates.run()) {
                is DatesOutcome.RetryLater -> if (runAttemptCount < 8) Result.retry() else Result.success()
                is DatesOutcome.NeedsCredentials -> { c.settings.setNeedsCredentials(true); Result.success() }
                else -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.e("CloudDatesWorker", "Capture date pass failed", e)
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }
}
