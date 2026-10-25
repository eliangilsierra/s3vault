package com.s3vault.app.sync

import androidx.room.withTransaction
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.AuditMode
import com.s3vault.app.data.local.AuditStatus
import com.s3vault.app.data.local.AuditTarget
import com.s3vault.app.data.local.IntegrityAuditEntity
import com.s3vault.app.data.local.IntegrityReason
import com.s3vault.app.data.local.IntegrityResultEntity
import com.s3vault.app.data.local.IntegrityVerdict
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import com.s3vault.app.data.local.VerifyLevel
import com.s3vault.app.domain.AwsCredentialsProvider
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.integrity.Finding
import com.s3vault.app.domain.integrity.IntegrityAnalyzer
import com.s3vault.app.domain.integrity.IntegrityPlanner
import com.s3vault.app.domain.integrity.ListedObject
import com.s3vault.app.domain.integrity.ListingFacts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface AuditOutcome {
    data class Completed(val audit: IntegrityAuditEntity) : AuditOutcome
    data class NeedsCredentials(val reason: RemoteErrorKind) : AuditOutcome
    data class RetryLater(val reason: RemoteErrorKind) : AuditOutcome
    data class Blocked(val reason: RemoteErrorKind) : AuditOutcome
    data object NotConfigured : AuditOutcome
}

/**
 * Checks that what the device recorded as backed up is still in the user's bucket. It only reads: nothing here can write or
 * delete in S3, and a repair is a separate, user-confirmed action ([IntegrityRepair]).
 *
 * Two ways to look, with different costs and different strength:
 *  - [runListing]: compares the records with the listing of `photos/` (one LIST request per 1000 objects, or none when a recent
 *    complete listing of the same bucket is reused). Finds missing objects, size changes and replaced objects.
 *  - [runDeep]: one HEAD per object (billed like GET), comparing the SHA-256 S3 stored at upload with the one in the key. Never
 *    downloads content. Bounded and incremental: each run takes the objects confirmed longest ago.
 *
 * It never runs by itself at app start: the user starts it, or opts in to a low-frequency scheduled listing audit.
 */
class IntegrityAuditor(
    private val db: AppDatabase,
    private val remote: RemoteContextProvider,
    private val credentials: AwsCredentialsProvider,
    private val catalog: RemoteCatalog,
    private val jobs: JobRecorder,
    private val lastListing: suspend () -> ListingResult?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val concurrency: Int = HEAD_CONCURRENCY,
) {
    private val dao get() = db.integrity()

    // ------------------------------------------------------------------ listing audit

    /**
     * By default the bucket is listed afresh: an audit that compares against an old listing would report what the bucket looked
     * like then. A caller that wants the free option passes [reuseListingYoungerThanMs] (the screen offers it, with the age).
     */
    suspend fun runListing(reuseListingYoungerThanMs: Long = 0): AuditOutcome {
        val ctx = remote.get() ?: return AuditOutcome.NotConfigured
        credentialProblem()?.let { return it }
        val targets = db.media().auditTargets()
        val audit = start(AuditMode.LISTING, targets.size)
        jobs.start(JobNames.AUDIT, targets.size)
        try {
            val source = ctx.config?.let { "${it.region}/${it.bucket}/${it.prefix}" }.orEmpty()
            val last = lastListing()
            val reuse = last != null && last.complete && last.source == source && source.isNotEmpty() && nowMs() - last.atMs in 0 until reuseListingYoungerThanMs
            var requests = 0
            if (!reuse) {
                val count = catalog.refresh(ctx)
                requests = maxOf(1, (count + 999) / 1000)
            }
            val facts = ListingFacts(complete = true, empty = db.remote().objectCount() == 0)
            val listed = HashMap<String, ListedObject>(targets.size * 2)
            targets.map { it.key }.chunked(CHUNK).forEach { keys ->
                db.remote().getByKeys(keys).forEach { listed[it.key] = ListedObject(it.sizeBytes, it.lastModifiedMs, it.etag) }
            }
            val previous = dao.allResults().associateBy { it.remoteKey }
            val findings = targets.map { t -> t to IntegrityAnalyzer.fromListing(t, listed[t.key], facts, ctx.keys) }
            val done = persist(audit.id, findings, previous, deep = false, adoptFrom = listed)
            val finished = finish(audit.copy(requests = requests), done, AuditStatus.COMPLETED, null)
            jobs.finish(JobNames.AUDIT, if (finished.missing + finished.changed > 0) JobState.PARTIAL else JobState.COMPLETED,
                if (finished.missing + finished.changed > 0) "${finished.missing + finished.changed} objetos con problemas." else null)
            return AuditOutcome.Completed(finished)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { closeWith(audit, AuditStatus.CANCELLED, null); jobs.finish(JobNames.AUDIT, JobState.CANCELLED) }
            throw e
        } catch (e: RemoteException) {
            // The listing failed: nothing is concluded about any object. Previous verdicts stay as they were.
            closeWith(audit, AuditStatus.FAILED, e.kind.userMessage)
            jobs.finish(JobNames.AUDIT, if (e.retryable) JobState.RETRY_LATER else JobState.NEEDS_ATTENTION, e.kind.userMessage)
            return outcomeOf(e)
        }
    }

    // ------------------------------------------------------------------ deep audit

    /**
     * [onlyKeys] rechecks exactly those objects (the user selected them); without it the run takes up to [limit] objects whose
     * checksum was never confirmed, or not within [freshForMs].
     */
    suspend fun runDeep(
        onlyKeys: Set<String>? = null,
        limit: Int = DEFAULT_DEEP_LIMIT,
        freshForMs: Long = DEEP_FRESH_MS,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): AuditOutcome {
        val ctx = remote.get() ?: return AuditOutcome.NotConfigured
        credentialProblem()?.let { return it }
        val all = db.media().auditTargets()
        val previous = dao.allResults().associateBy { it.remoteKey }
        val chosen = IntegrityPlanner.nextDeep(all, previous, nowMs() - freshForMs, limit, onlyKeys)
        val audit = start(AuditMode.DEEP, chosen.size)
        jobs.start(JobNames.AUDIT, chosen.size)
        var totals = Totals()
        var stop: AuditOutcome? = null
        try {
            val gate = Semaphore(concurrency.coerceIn(1, 8))
            var temporaryInARow = 0
            for (batch in chosen.chunked(BATCH)) {
                currentCoroutineContext().ensureActive()
                val results = coroutineScope {
                    batch.map { t -> async { gate.withPermit { t to check(ctx, t) } } }.awaitAll()
                }
                val usable = ArrayList<Pair<AuditTarget, Finding>>()
                for ((t, r) in results) {
                    when (r) {
                        is Check.Found -> { usable += t to r.finding; temporaryInARow = 0 }
                        is Check.Temporary -> { usable += t to r.finding; temporaryInARow++ }
                        is Check.Credentials -> stop = AuditOutcome.NeedsCredentials(r.kind)
                        is Check.Blocked -> stop = stop ?: AuditOutcome.Blocked(r.kind)
                    }
                }
                totals += persist(audit.id, usable, previous, deep = true, adoptFrom = emptyMap())
                jobs.progress(JobNames.AUDIT, chosen.size, totals.verified, totals.missing + totals.changed, totals.checked)
                onProgress(totals.checked, chosen.size)
                dao.updateAudit(audit.copy(checked = totals.checked, verified = totals.verified, missing = totals.missing, changed = totals.changed, unknown = totals.unknown, stale = totals.stale, requests = totals.requests))
                if (stop != null) break
                if (temporaryInARow >= TEMPORARY_LIMIT) { stop = AuditOutcome.RetryLater(RemoteErrorKind.NETWORK); break }
            }
            val s = stop
            val status = if (s == null) AuditStatus.COMPLETED else AuditStatus.FAILED
            val message = when (s) {
                is AuditOutcome.NeedsCredentials -> s.reason.userMessage
                is AuditOutcome.Blocked -> s.reason.userMessage
                is AuditOutcome.RetryLater -> s.reason.userMessage
                else -> null
            }
            val finished = finish(audit, totals, status, message)
            jobs.finish(JobNames.AUDIT, when {
                s is AuditOutcome.NeedsCredentials || s is AuditOutcome.Blocked -> JobState.NEEDS_ATTENTION
                s is AuditOutcome.RetryLater -> JobState.RETRY_LATER
                finished.missing + finished.changed > 0 -> JobState.PARTIAL
                else -> JobState.COMPLETED
            }, message)
            return s ?: AuditOutcome.Completed(finished)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // What was already confirmed stays confirmed (each batch was stored); the run is simply marked stopped.
                dao.updateAudit(audit.copy(status = AuditStatus.CANCELLED, finishedAtMs = nowMs(), checked = totals.checked, verified = totals.verified, missing = totals.missing, changed = totals.changed, unknown = totals.unknown, stale = totals.stale, requests = totals.requests))
                jobs.finish(JobNames.AUDIT, JobState.CANCELLED)
            }
            throw e
        }
    }

    private sealed interface Check {
        data class Found(val finding: Finding) : Check
        data class Temporary(val finding: Finding) : Check
        data class Credentials(val kind: RemoteErrorKind) : Check
        data class Blocked(val kind: RemoteErrorKind) : Check
    }

    /** One HEAD. A refusal or a network error is an inconclusive check (UNKNOWN), never a missing object. */
    private suspend fun check(ctx: RemoteContext, t: AuditTarget): Check = try {
        Check.Found(IntegrityAnalyzer.fromHead(t, ctx.storage.head(t.key), ctx.keys))
    } catch (e: RemoteException) {
        when {
            e.needsCredentials -> Check.Credentials(e.kind)
            e.kind == RemoteErrorKind.ACCESS_DENIED ->
                // With a ListBucket policy limited to a prefix, S3 also answers 403 for an object that does not exist: it proves nothing.
                Check.Found(Finding(IntegrityVerdict.UNKNOWN, VerifyLevel.LISTING, IntegrityReason.FORBIDDEN))
            e.kind in BLOCKING -> Check.Blocked(e.kind)
            else -> Check.Temporary(Finding(IntegrityVerdict.UNKNOWN, VerifyLevel.LISTING, IntegrityReason.TEMPORARY_ERROR))
        }
    }

    // ------------------------------------------------------------------ shared

    private data class Totals(
        val checked: Int = 0, val verified: Int = 0, val missing: Int = 0, val changed: Int = 0, val unknown: Int = 0, val stale: Int = 0, val requests: Int = 0,
    ) {
        operator fun plus(o: Totals) = Totals(checked + o.checked, verified + o.verified, missing + o.missing, changed + o.changed, unknown + o.unknown, stale + o.stale, requests + o.requests)
    }

    /** Stores the verdicts and reports their totals. [deep] findings count one HEAD each. */
    private suspend fun persist(
        auditId: Long,
        findings: List<Pair<AuditTarget, Finding>>,
        previous: Map<String, IntegrityResultEntity>,
        deep: Boolean,
        adoptFrom: Map<String, ListedObject>,
    ): Totals {
        val now = nowMs()
        var t = Totals(requests = if (deep) findings.size else 0)
        val rows = ArrayList<IntegrityResultEntity>(findings.size)
        db.withTransaction {
            for ((target, f) in findings) {
                val old = previous[target.key]
                val deepAt = when {
                    deep && f.level == VerifyLevel.CHECKSUM && f.verdict == IntegrityVerdict.VERIFIED -> now
                    deep && f.verdict == IntegrityVerdict.UNKNOWN -> old?.deepCheckedAtMs // inconclusive: keep what an earlier run proved
                    !deep && f.verdict == IntegrityVerdict.VERIFIED -> old?.deepCheckedAtMs // the listing did not contradict it
                    else -> null // contradicted: the earlier confirmation no longer counts
                }
                rows += IntegrityResultEntity(
                    remoteKey = target.key, verdict = f.verdict, level = f.level, reason = f.reason, checkedAtMs = now,
                    deepCheckedAtMs = deepAt, expectedSize = target.expectedSize, remoteSize = f.remoteSize, auditId = auditId,
                )
                if (f.verdict == IntegrityVerdict.VERIFIED && target.etag == null) adoptFrom[target.key]?.etag?.let { db.media().adoptEtag(target.key, it) }
                t = t + when (f.verdict) {
                    IntegrityVerdict.VERIFIED -> Totals(checked = 1, verified = 1)
                    IntegrityVerdict.MISSING -> Totals(checked = 1, missing = 1)
                    IntegrityVerdict.CHANGED -> Totals(checked = 1, changed = 1)
                    IntegrityVerdict.UNKNOWN -> Totals(checked = 1, unknown = 1)
                    IntegrityVerdict.STALE_RECORD -> Totals(checked = 1, stale = 1)
                }
            }
            rows.chunked(CHUNK).forEach { dao.upsertResults(it) }
            dao.dropOrphans()
        }
        return t
    }

    private suspend fun start(mode: AuditMode, planned: Int): IntegrityAuditEntity {
        val a = IntegrityAuditEntity(mode = mode, status = AuditStatus.RUNNING, startedAtMs = nowMs(), planned = planned)
        return a.copy(id = dao.insertAudit(a))
    }

    private suspend fun finish(a: IntegrityAuditEntity, t: Totals, status: AuditStatus, error: String?): IntegrityAuditEntity {
        val done = a.copy(
            status = status, finishedAtMs = nowMs(), checked = t.checked, verified = t.verified, missing = t.missing,
            changed = t.changed, unknown = t.unknown, stale = t.stale, requests = maxOf(a.requests, t.requests), error = error,
        )
        dao.updateAudit(done)
        return done
    }

    private suspend fun closeWith(a: IntegrityAuditEntity, status: AuditStatus, error: String?) {
        dao.updateAudit(a.copy(status = status, finishedAtMs = nowMs(), error = error))
    }

    private suspend fun credentialProblem(): AuditOutcome? = try {
        credentials.getCredentials(); null
    } catch (e: RemoteException) {
        if (e.needsCredentials) AuditOutcome.NeedsCredentials(e.kind) else AuditOutcome.Blocked(e.kind)
    }

    private fun outcomeOf(e: RemoteException): AuditOutcome = when {
        e.needsCredentials -> AuditOutcome.NeedsCredentials(e.kind)
        e.retryable -> AuditOutcome.RetryLater(e.kind)
        else -> AuditOutcome.Blocked(e.kind)
    }

    /** A run left RUNNING by a process that died is shown as interrupted. */
    suspend fun recoverInterrupted(processStartMs: Long) { dao.markInterrupted(processStartMs) }

    companion object {
        const val HEAD_CONCURRENCY = 4
        const val BATCH = 50
        const val CHUNK = 400
        const val DEFAULT_DEEP_LIMIT = 200
        const val TEMPORARY_LIMIT = 6
        /** A complete listing this young is reused instead of listing again. */
        const val REUSE_LISTING_MS = 10L * 60 * 1000
        /** A checksum confirmation this young is not repeated by an incremental run. */
        const val DEEP_FRESH_MS = 90L * 24 * 3600 * 1000
        private val BLOCKING = setOf(RemoteErrorKind.BUCKET_NOT_FOUND, RemoteErrorKind.WRONG_REGION, RemoteErrorKind.QUOTA_EXCEEDED, RemoteErrorKind.NOT_CONFIGURED)
    }
}

/**
 * Repairs the user asks for after an audit. Each one changes only what the user selected, and none runs without that choice:
 * re-uploading overwrites the object in S3 with the copy on this device (through the normal verified queue); discarding only
 * forgets a local record. Nothing here deletes anything in S3.
 */
class IntegrityRepair(private val db: AppDatabase, private val backup: BackupController) {
    /** Sends the files still on this device back through the verified upload. Returns how many were queued. */
    suspend fun reupload(keys: List<String>): Int {
        if (keys.isEmpty()) return 0
        val ids = keys.distinct().chunked(CHUNK).flatMap { db.media().idsBackedUpAs(it) }
        if (ids.isEmpty()) return 0
        db.withTransaction {
            keys.distinct().chunked(CHUNK).forEach { db.media().resetForReupload(it); db.integrity().forget(it) }
        }
        return backup.enqueue(ids)
    }

    /**
     * Forgets records whose file is gone from the device AND whose object the audit found missing: nothing is left to restore
     * or repair, only a record. Photos still on this device are never touched.
     */
    suspend fun discardRecords(keys: List<String>): Int {
        if (keys.isEmpty()) return 0
        var n = 0
        db.withTransaction {
            keys.distinct().chunked(CHUNK).forEach { all ->
                // Only what an audit found gone: a record the audit could not judge is never discarded.
                val chunk = db.integrity().resultsFor(all)
                    .filter { it.verdict == IntegrityVerdict.MISSING || it.verdict == IntegrityVerdict.STALE_RECORD }.map { it.remoteKey }
                if (chunk.isEmpty()) return@forEach
                val orphans = db.media().orphanIdsOfRemoteKeys(chunk)
                orphans.chunked(CHUNK).forEach { db.media().deleteLocalRows(it); db.albums().removeMediaEverywhere(it) }
                n += orphans.size
                db.integrity().forget(chunk)
            }
        }
        return n
    }

    private companion object { const val CHUNK = 400 }
}
