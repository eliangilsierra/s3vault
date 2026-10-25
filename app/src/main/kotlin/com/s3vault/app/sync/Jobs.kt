package com.s3vault.app.sync

import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.JobEntity
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import com.s3vault.app.domain.RemoteErrorKind

/** Progress of one run of the backup engine, reported after every batch. */
data class RunProgress(
    /** Items waiting when the run started. */
    val total: Int,
    /** Verified in S3 (uploaded and checked, or linked to an object that was already there and checked). */
    val verified: Int,
    val failed: Int,
    /** Put back in the queue for a later attempt (network, throttling). */
    val deferred: Int,
) {
    val done: Int get() = verified + failed + deferred
}

/**
 * Writes the one-row summary of each background job to Room, so the sync center shows the last run after the process died or
 * the device restarted. It only ever reports what the engines already decided: "completed" always means every counted item
 * went through the verification the engine requires, and a run with failures is [JobState.PARTIAL], never "completed".
 */
class JobRecorder(
    private val db: AppDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.jobs()

    suspend fun start(name: String, total: Int = 0) {
        val now = nowMs()
        dao.upsert(JobEntity(name, JobState.RUNNING, 0, total, 0, 0, startedAtMs = now, updatedAtMs = now))
    }

    suspend fun progress(name: String, total: Int, succeeded: Int, failed: Int, done: Int) {
        val old = dao.get(name) ?: return
        if (old.state != JobState.RUNNING) return // a finished job is never reopened by a late progress report
        dao.upsert(old.copy(totalItems = total, doneItems = done, succeededItems = succeeded, failedItems = failed, updatedAtMs = nowMs()))
    }

    suspend fun finish(name: String, state: JobState, error: String? = null) {
        val old = dao.get(name) ?: return
        val now = nowMs()
        dao.upsert(old.copy(state = state, lastError = error ?: if (state == JobState.COMPLETED) null else old.lastError, updatedAtMs = now, finishedAtMs = now))
    }

    /** Summarises the outcome of [BackupEngine.run]. */
    suspend fun finishBackup(outcome: RunOutcome) {
        val now = nowMs()
        val old = dao.get(JobNames.BACKUP) ?: JobEntity(JobNames.BACKUP, JobState.RUNNING, startedAtMs = now, updatedAtMs = now)
        val done = when (outcome) {
            is RunOutcome.Completed -> old.copy(
                state = if (outcome.failed > 0) JobState.PARTIAL else JobState.COMPLETED,
                succeededItems = outcome.uploaded + outcome.skippedAlreadyBackedUp, failedItems = outcome.failed,
                doneItems = outcome.uploaded + outcome.skippedAlreadyBackedUp + outcome.failed,
                lastError = if (outcome.failed > 0) "${outcome.failed} elementos no se pudieron respaldar." else null,
            )
            is RunOutcome.RetryLater -> old.copy(state = JobState.RETRY_LATER, lastError = outcome.reason.userMessage)
            is RunOutcome.NeedsCredentials -> old.copy(state = JobState.NEEDS_ATTENTION, lastError = outcome.reason.userMessage)
            is RunOutcome.Blocked -> old.copy(state = JobState.NEEDS_ATTENTION, lastError = outcome.reason.userMessage)
            RunOutcome.NotConfigured -> old.copy(state = JobState.NEEDS_ATTENTION, lastError = RemoteErrorKind.NOT_CONFIGURED.userMessage)
        }
        dao.upsert(done.copy(updatedAtMs = now, finishedAtMs = now))
    }

    /** Summarises the outcome of [RestoreEngine.run]. */
    suspend fun finishRestore(outcome: RestoreOutcome) {
        val now = nowMs()
        val old = dao.get(JobNames.RESTORE) ?: JobEntity(JobNames.RESTORE, JobState.RUNNING, startedAtMs = now, updatedAtMs = now)
        val done = when (outcome) {
            is RestoreOutcome.Done -> old.copy(
                state = if (outcome.failed > 0) JobState.PARTIAL else JobState.COMPLETED,
                succeededItems = outcome.restored, failedItems = outcome.failed, doneItems = outcome.restored + outcome.failed,
                lastError = if (outcome.failed > 0) "${outcome.failed} elementos no se pudieron restaurar." else null,
            )
            is RestoreOutcome.RetryLater -> old.copy(state = JobState.RETRY_LATER, lastError = outcome.reason.userMessage)
            is RestoreOutcome.NeedsCredentials -> old.copy(state = JobState.NEEDS_ATTENTION, lastError = outcome.reason.userMessage)
            is RestoreOutcome.Blocked -> old.copy(state = JobState.NEEDS_ATTENTION, lastError = outcome.reason.userMessage)
            RestoreOutcome.NotConfigured -> old.copy(state = JobState.NEEDS_ATTENTION, lastError = RemoteErrorKind.NOT_CONFIGURED.userMessage)
        }
        dao.upsert(done.copy(updatedAtMs = now, finishedAtMs = now))
    }

    /** Called once per process start with the moment the process began. */
    suspend fun recoverInterrupted(processStartMs: Long) {
        dao.markInterrupted(processStartMs)
    }
}
