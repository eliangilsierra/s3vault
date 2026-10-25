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
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.s3vault.app.security.SafeLog
import com.s3vault.app.settings.BackupSettings
import kotlinx.coroutines.flow.first
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

    /** Manual audit of the backup against the bucket: a listing audit, or a bounded HEAD audit when [deep]. */
    fun kickAudit(deep: Boolean, wifiOnly: Boolean, reuseListing: Boolean = false)
    fun cancelAudit()

    /**
     * Looks for identical and similar pictures. Needs no network, and waits for the battery not to be low. [continuation] is for
     * a run that ended with work left: it queues behind the one that just finished instead of being ignored.
     */
    fun kickDuplicateScan(continuation: Boolean = false)
    fun cancelDuplicateScan()

    /**
     * Embeds the pictures that have no vector yet. Needs no network; waits for the battery not to be low, and for charging when
     * [onlyCharging]. [continuation] queues behind the run that just ended instead of being ignored.
     */
    fun kickSemanticIndex(onlyCharging: Boolean, continuation: Boolean = false)
    fun cancelSemanticIndex()

    /** Daily pass that indexes newly added pictures while the feature is on; [enabled] false removes it. */
    fun configureSemanticSchedule(enabled: Boolean, onlyCharging: Boolean)

    /** Downloads the model pack (about 150 MB), resumably, on the network the user allowed. */
    fun kickModelDownload(wifiOnly: Boolean)
    fun cancelModelDownload()

    /** Opt-in low-frequency listing audit. [days] of 0 turns it off. */
    fun configureAuditSchedule(days: Int, wifiOnly: Boolean)
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

    override fun kickAudit(deep: Boolean, wifiOnly: Boolean, reuseListing: Boolean) {
        val req = OneTimeWorkRequestBuilder<AuditWorker>()
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putBoolean(AuditWorker.KEY_DEEP, deep).putBoolean(AuditWorker.KEY_REUSE, reuseListing).build())
            .build()
        // KEEP: asking again while an audit is queued or running must not start a second one (each costs S3 requests).
        wm.enqueueUniqueWork(AUDIT_WORK, ExistingWorkPolicy.KEEP, req)
    }

    override fun kickDuplicateScan(continuation: Boolean) {
        val req = OneTimeWorkRequestBuilder<DuplicateScanWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(DUPLICATES_WORK, if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    override fun cancelDuplicateScan() {
        wm.cancelUniqueWork(DUPLICATES_WORK)
    }

    override fun kickSemanticIndex(onlyCharging: Boolean, continuation: Boolean) {
        val req = OneTimeWorkRequestBuilder<SemanticIndexWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(onlyCharging).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putBoolean(SemanticIndexWorker.KEY_CHARGING, onlyCharging).build())
            .build()
        wm.enqueueUniqueWork(SEMANTIC_WORK, if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    override fun cancelSemanticIndex() { wm.cancelUniqueWork(SEMANTIC_WORK) }

    override fun configureSemanticSchedule(enabled: Boolean, onlyCharging: Boolean) {
        if (!enabled) { wm.cancelUniqueWork(SEMANTIC_AUTO_WORK); return }
        val req = PeriodicWorkRequestBuilder<SemanticIndexWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(onlyCharging).build())
            .setInputData(Data.Builder().putBoolean(SemanticIndexWorker.KEY_CHARGING, onlyCharging).build())
            .build()
        wm.enqueueUniquePeriodicWork(SEMANTIC_AUTO_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    override fun kickModelDownload(wifiOnly: Boolean) {
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(MODEL_WORK, ExistingWorkPolicy.KEEP, req)
    }

    override fun cancelModelDownload() { wm.cancelUniqueWork(MODEL_WORK) }

    override fun cancelAudit() {
        wm.cancelUniqueWork(AUDIT_WORK)
    }

    override fun configureAuditSchedule(days: Int, wifiOnly: Boolean) {
        if (days <= 0) { wm.cancelUniqueWork(AUDIT_AUTO_WORK); return }
        val req = PeriodicWorkRequestBuilder<AuditWorker>(days.toLong(), TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build() // a scheduled audit only lists: it never does the per-object HEAD pass by itself
        wm.enqueueUniquePeriodicWork(AUDIT_AUTO_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    companion object {
        const val SEMANTIC_WORK = "s3vault-semantic-index"
        const val SEMANTIC_AUTO_WORK = "s3vault-semantic-index-auto"
        const val MODEL_WORK = "s3vault-model-download"
        const val DUPLICATES_WORK = "s3vault-duplicates"
        const val AUDIT_WORK = "s3vault-audit"
        const val AUDIT_AUTO_WORK = "s3vault-audit-auto"
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
            // The summary is written only for a run that had work: an empty periodic run must not erase the last real one.
            var started = false
            val outcome = c.backupEngine.run(settings.maxConcurrency) { p ->
                if (!started) { c.jobs.start(JobNames.BACKUP, p.total); started = true }
                c.jobs.progress(JobNames.BACKUP, p.total, p.verified, p.failed, p.done)
            }
            if (started || outcome !is RunOutcome.Completed) c.jobs.finishBackup(outcome)
            when (val out = outcome) {
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
            // Paused or cancelled: the items are back in the queue; the summary says the run stopped, not that it finished.
            withContext(NonCancellable) { c.jobs.finish(JobNames.BACKUP, JobState.CANCELLED) }
            throw e
        } catch (e: Exception) {
            SafeLog.e(TAG, "Backup worker failed", e)
            c.jobs.finish(JobNames.BACKUP, JobState.RETRY_LATER, com.s3vault.app.domain.RemoteErrorKind.UNKNOWN.userMessage)
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
            val outcome = c.restoreEngine.run()
            if (outcome !is RestoreOutcome.Done || outcome.restored + outcome.failed > 0) c.jobs.finishRestore(outcome)
            when (outcome) {
                is RestoreOutcome.RetryLater -> if (runAttemptCount < 8) Result.retry() else Result.success()
                is RestoreOutcome.NeedsCredentials -> { c.settings.setNeedsCredentials(true); Result.success() }
                else -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { c.jobs.finish(JobNames.RESTORE, JobState.CANCELLED) }
            throw e
        } catch (e: Exception) {
            SafeLog.e("RestoreWorker", "Restore worker failed", e)
            c.jobs.finish(JobNames.RESTORE, JobState.RETRY_LATER, com.s3vault.app.domain.RemoteErrorKind.UNKNOWN.userMessage)
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

class AuditWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as S3VaultApp).container
        return try {
            val outcome = if (inputData.getBoolean(KEY_DEEP, false)) c.integrityAuditor.runDeep() else c.integrityAuditor.runListing(if (inputData.getBoolean(KEY_REUSE, false)) IntegrityAuditor.REUSE_LISTING_MS else 0L)
            when (outcome) {
                is AuditOutcome.RetryLater -> if (runAttemptCount < 4) Result.retry() else Result.success()
                is AuditOutcome.NeedsCredentials -> { c.settings.setNeedsCredentials(true); Result.success() }
                else -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.e("AuditWorker", "Audit failed", e)
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_DEEP = "deep"
        const val KEY_REUSE = "reuse"
    }
}

class DuplicateScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as S3VaultApp).container
        return try {
            val r = c.duplicateScanner.run()
            // The run is bounded so it never holds the CPU for long; whatever is left continues as a new run.
            if (r.hashLeft + r.analysisLeft > 0 && r.hashed + r.analysed > 0) c.scheduler.kickDuplicateScan(continuation = true)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.e("DuplicateScanWorker", "Duplicate scan failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

class SemanticIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as S3VaultApp).container
        if (!c.settings.semanticEnabled.first()) return Result.success() // turned off while the work was waiting
        return try {
            when (val r = c.semanticIndexer.run()) {
                is IndexOutcome.Done -> {
                    // Bounded per run; whatever is left continues as a new run under the same constraints.
                    if (r.left > 0 && r.indexed + r.unreadable > 0) c.scheduler.kickSemanticIndex(inputData.getBoolean(KEY_CHARGING, true), continuation = true)
                    Result.success()
                }
                IndexOutcome.ModelMissing -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.e("SemanticIndexWorker", "Indexing failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object { const val KEY_CHARGING = "charging" }
}

class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as S3VaultApp).container
        val pack = com.s3vault.app.domain.semantic.ModelPacks.ClipB32
        return try {
            c.jobs.start(JobNames.MODEL_DOWNLOAD, (pack.totalBytes / 1024).toInt())
            var last = 0L
            val result = c.modelDownloader.download(pack) { done, total ->
                val now = System.currentTimeMillis()
                if (now - last > 700 || done >= total) {
                    last = now
                    kotlinx.coroutines.runBlocking { c.jobs.progress(JobNames.MODEL_DOWNLOAD, (total / 1024).toInt(), (done / 1024).toInt(), 0, (done / 1024).toInt()) }
                }
            }
            when (result) {
                DownloadResult.Done -> {
                    c.jobs.finish(JobNames.MODEL_DOWNLOAD, JobState.COMPLETED)
                    if (c.settings.semanticEnabled.first()) c.scheduler.kickSemanticIndex(c.settings.semanticOnlyCharging.first())
                    Result.success()
                }
                is DownloadResult.NetworkError -> { c.jobs.finish(JobNames.MODEL_DOWNLOAD, JobState.RETRY_LATER, result.reason); if (runAttemptCount < 8) Result.retry() else Result.success() }
                is DownloadResult.HttpError -> { c.jobs.finish(JobNames.MODEL_DOWNLOAD, JobState.NEEDS_ATTENTION, "El servidor respondió ${result.code} para ${result.file}."); Result.success() }
                is DownloadResult.HashMismatch -> { c.jobs.finish(JobNames.MODEL_DOWNLOAD, JobState.NEEDS_ATTENTION, "${result.file} no coincide con la huella esperada y se descartó. El modelo no se usa."); Result.success() }
                is DownloadResult.NotEnoughSpace -> { c.jobs.finish(JobNames.MODEL_DOWNLOAD, JobState.NEEDS_ATTENTION, "No hay espacio suficiente (hacen falta unos ${result.neededBytes / (1024 * 1024)} MB)."); Result.success() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by the user or by the system: the partial files stay, the next attempt resumes.
            withContext(NonCancellable) { c.jobs.finish(JobNames.MODEL_DOWNLOAD, JobState.CANCELLED) }
            throw e
        } catch (e: Exception) {
            SafeLog.e("ModelDownloadWorker", "Model download failed", e)
            c.jobs.finish(JobNames.MODEL_DOWNLOAD, JobState.RETRY_LATER, com.s3vault.app.domain.RemoteErrorKind.UNKNOWN.userMessage)
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
}
