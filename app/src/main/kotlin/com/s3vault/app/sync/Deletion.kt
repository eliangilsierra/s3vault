package com.s3vault.app.sync

import androidx.room.withTransaction
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteStorage
import com.s3vault.app.domain.SyncState
import com.s3vault.app.security.Redactor
import com.s3vault.app.security.SafeLog
import com.s3vault.app.domain.TransferEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One thing the user can delete, described by *where its copies are*. The two kinds of deletion are separate on
 * purpose: [CloudDeletionController] only touches S3 and [LocalDeletionController] only forgets files that Android
 * has already removed. Neither one ever triggers the other.
 */
data class DeleteEntry(
    val name: String,
    /** Null when the item is only known as an S3 object (S3 library). */
    val mediaStoreId: Long?,
    val uri: String?,
    /** Key of the copy in S3, if any. */
    val remoteKey: String?,
    /** A copy exists on this device (so deleting the S3 object does not lose the photo). */
    val hasLocalCopy: Boolean,
)

/** What a confirmation dialog needs to say, computed from the entries. */
data class DeleteImpact(
    val total: Int,
    /** Entries with a file on this device. */
    val onDevice: Int,
    /** Entries with an object in S3. */
    val inCloud: Int,
    /** On the device but not in S3: deleting locally loses them for good. */
    val deviceOnly: Int,
    /** In S3 but not on the device: deleting in S3 loses them for good. */
    val cloudOnly: Int,
) {
    companion object {
        fun of(entries: List<DeleteEntry>) = DeleteImpact(
            total = entries.size,
            onDevice = entries.count { it.hasLocalCopy && it.uri != null },
            inCloud = entries.count { it.remoteKey != null },
            deviceOnly = entries.count { it.hasLocalCopy && it.uri != null && it.remoteKey == null },
            cloudOnly = entries.count { it.remoteKey != null && !it.hasLocalCopy },
        )
    }
}

data class CloudDeleteResult(
    val deleted: List<String>,
    val failed: Map<String, RemoteErrorKind>,
    /** Objects that are being restored right now; deleting them mid-download would corrupt the restore. */
    val skippedRestoring: Int,
) {
    val failedKeys: List<String> get() = failed.keys.toList()
}

/**
 * Deletes objects from the user's own bucket. Only reachable from a confirmed UI action.
 *
 * - Local files are never touched: a photo that still exists on the device just stops being "backed up" and is flagged
 *   so automatic backup does not upload it again behind the user's back.
 * - S3 deletes are idempotent, so [delete] can be repeated with the failed keys as a retry.
 * - Keys go to S3 in DeleteObjects requests of up to [batchSize] (1 000, S3's maximum): the cost of a bulk deletion is a
 *   handful of round trips, not one per hundred photos. S3 answers per key, so one bad key never hides the rest.
 * - A throttled or briefly unreachable S3 is retried a couple of times with a growing pause, for the whole request and for
 *   individual keys alike. Anything still failing afterwards is reported as failed, never as deleted.
 * - Room is updated after every confirmed batch (even if the user cancels right then), so the UI never shows a photo that
 *   S3 already dropped, and a cancelled or interrupted run leaves consistent state. No transaction is open during a request.
 * - Runs are serialised: two overlapping bulk deletions would only repeat each other's requests.
 */
class CloudDeletionController(
    private val db: AppDatabase,
    private val remote: RemoteContextProvider,
    private val onVersionsDeleted: (Collection<String>) -> Unit = {},
    /** One entry per run with what was really deleted: the storage-cost estimate uses it to tell its inventory is stale. */
    private val events: EventLog? = null,
    private val batchSize: Int = RemoteStorage.MAX_DELETE_BATCH,
    /** Pause before retry number [attempt] (0-based). Replaced in tests so they do not wait. */
    private val backoff: suspend (attempt: Int) -> Unit = { attempt -> delay(RETRY_BASE_MS shl attempt) },
) {
    private val runLock = Mutex()

    suspend fun delete(keys: List<String>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): CloudDeleteResult = runLock.withLock {
        val ctx = remote.get() ?: throw RemoteException(RemoteErrorKind.NOT_CONFIGURED)
        val wanted = keys.distinct().filter { ctx.keys.isPhotoKey(it) }
        val restoring = wanted.chunked(DB_CHUNK).flatMap { db.remote().getByKeys(it) }
            .filter { it.state == SyncState.RESTORING }.map { it.key }.toSet()
        val eligible = wanted.filter { it !in restoring }

        val deleted = ArrayList<String>()
        val failed = LinkedHashMap<String, RemoteErrorKind>()
        var stopReason: RemoteErrorKind? = null
        var done = 0
        onProgress(0, eligible.size)

        try {
            for (batch in eligible.chunked(batchSize)) {
                currentCoroutineContext().ensureActive()
                val reason = stopReason
                if (reason != null) {
                    batch.forEach { failed[it] = reason } // not attempted: the same cause would repeat
                } else {
                    val outcome = deleteBatch(ctx, batch)
                    deleted += outcome.deleted
                    failed += outcome.failed
                    stopReason = outcome.stopReason
                }
                done += batch.size
                onProgress(done, eligible.size)
            }
        } finally {
            // Also when cancelled half way: what was deleted stays deleted and must be counted.
            if (deleted.isNotEmpty()) withContext(NonCancellable) { events?.log(TransferEventType.CLOUD_DELETED, "${deleted.size} elementos") }
        }
        CloudDeleteResult(deleted, failed, restoring.size)
    }

    private class BatchOutcome(val deleted: List<String>, val failed: Map<String, RemoteErrorKind>, val stopReason: RemoteErrorKind?)

    /** One batch, retrying what S3 said it could not do *now* (throttling, a dropped connection). */
    private suspend fun deleteBatch(ctx: RemoteContext, batch: List<String>): BatchOutcome {
        val deleted = ArrayList<String>()
        val failed = LinkedHashMap<String, RemoteErrorKind>()
        var pending = batch
        var attempt = 0
        var stop: RemoteErrorKind? = null
        while (pending.isNotEmpty()) {
            val report = try {
                ctx.storage.delete(pending)
            } catch (e: RemoteException) {
                if (e.kind in TRANSIENT && attempt < MAX_RETRIES) { backoff(attempt++); continue }
                pending.forEach { failed[it] = e.kind }
                stop = e.kind
                break
            }
            if (report.deleted.isNotEmpty()) {
                deleted += report.deleted
                // The rows go as soon as S3 confirmed, even if the user cancels this very moment.
                withContext(NonCancellable) { forget(report.deleted) }
                deletePreviews(ctx, report.deleted)
            }
            // A per-key AccessDenied is the IAM policy: the rest of the batches would be refused as well.
            if (RemoteErrorKind.ACCESS_DENIED in report.failed.values) stop = RemoteErrorKind.ACCESS_DENIED
            val again = report.failed.filterValues { it in TRANSIENT }.keys.toList()
            // What cannot be retried is final now; what can is decided by the next attempt (or by the last one, below).
            report.failed.forEach { (k, kind) -> if (k !in again) failed[k] = kind }
            if (again.isEmpty()) break
            if (attempt >= MAX_RETRIES) { again.forEach { failed[it] = report.failed.getValue(it) }; break }
            backoff(attempt++)
            pending = again
        }
        return BatchOutcome(deleted, failed, stop)
    }

    /** Previews are shared by every object with the same content, so one that is still referenced is kept. */
    private suspend fun deletePreviews(ctx: RemoteContext, deletedKeys: List<String>) {
        try {
            val versions = deletedKeys.mapNotNull { ctx.keys.parse(it)?.versionId }.distinct()
            if (versions.isEmpty()) return
            // The deleted rows are already gone from the cached listing, so any version still listed is used by another object.
            val stillUsed = versions.chunked(DB_CHUNK).flatMap { db.remote().versionsListed(it) }.toHashSet()
            val orphaned = versions.filter { it !in stillUsed }
            if (orphaned.isEmpty()) return
            orphaned.chunked(batchSize).forEach { ctx.storage.delete(it.map(ctx.keys::thumbnailKey)) }
            // Evicting cached previews deletes files: never on the caller's (possibly main) thread.
            withContext(Dispatchers.IO) { onVersionsDeleted(orphaned) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The photo is already gone; a leftover few-KB preview is harmless and unreachable from the app.
            SafeLog.w("CloudDeletion", "Preview cleanup skipped: " + Redactor.describe(e))
        }
    }

    private suspend fun forget(keys: List<String>) {
        db.withTransaction {
            val media = db.media()
            val orphans = keys.chunked(DB_CHUNK).flatMap { media.orphanIdsOfRemoteKeys(it) }
            keys.chunked(DB_CHUNK).forEach { media.detachRemote(it) }
            if (orphans.isNotEmpty()) {
                orphans.chunked(DB_CHUNK).forEach {
                    media.deleteLocalRows(it)
                    db.albums().removeMediaEverywhere(it)
                }
            }
            val versions = keys.chunked(DB_CHUNK).flatMap { db.remote().getByKeys(it) }.map { it.versionId }.distinct()
            keys.chunked(DB_CHUNK).forEach { db.remote().forget(it) }
            // An S3-only album member goes with its last object; while another object has the same content it stays.
            val gone = versions - versions.chunked(DB_CHUNK).flatMap { db.remote().versionsListed(it) }.toSet()
            gone.chunked(DB_CHUNK).forEach { db.albums().removeRemoteItemsEverywhere(it) }
        }
    }

    private companion object {
        /** Errors that are about *when*, not *whether*: worth asking again. Permission, credential and quota errors are not. */
        val TRANSIENT = setOf(RemoteErrorKind.THROTTLED, RemoteErrorKind.NETWORK, RemoteErrorKind.UNKNOWN)
        const val MAX_RETRIES = 2
        const val RETRY_BASE_MS = 500L
    }
}

/** Checks whether Android still has a file (the only reliable proof that a system deletion really happened). */
fun interface LocalMediaGateway {
    suspend fun exists(uri: String): Boolean

    /** Which of [uris] Android still has. Gateways that can ask for many at once override this; the default asks one by one. */
    suspend fun existing(uris: List<String>): Set<String> = uris.filterTo(HashSet()) { exists(it) }
}

data class LocalDeleteResult(
    /** Files that are really gone from the device. */
    val deleted: Int,
    /** Of those, how many still have their copy in S3 (shown as "only in S3" from now on). */
    val keptInCloud: Int,
    /** Of those, how many had no S3 copy and are gone for good. */
    val lost: Int,
    /** Requested files that are still on the device (user cancelled, or the system refused). */
    val remaining: Int,
)

/**
 * Settles the index after Android's own deletion flow (system confirmation dialog). The result of that dialog is not
 * trusted: each file is checked, and only files that are verifiably gone change state. Nothing here touches S3.
 */
class LocalDeletionController(
    private val db: AppDatabase,
    private val gateway: LocalMediaGateway,
) {
    suspend fun settle(entries: List<DeleteEntry>): LocalDeleteResult {
        val candidates = entries.filter { it.mediaStoreId != null && it.uri != null }
        val present = gateway.existing(candidates.map { it.uri!! })
        val gone = candidates.filter { it.uri!! !in present }
        val ids = gone.map { it.mediaStoreId!! }
        var kept = 0
        if (ids.isNotEmpty()) {
            db.withTransaction {
                val media = db.media()
                val rows = ids.chunked(DB_CHUNK).flatMap { media.getAll(it) }
                val withCopy = rows.filter { it.remoteKey != null }.map { it.mediaStoreId }
                val withoutCopy = rows.filter { it.remoteKey == null }.map { it.mediaStoreId }
                kept = withCopy.size
                withCopy.chunked(DB_CHUNK).forEach { media.markGoneKeepingRemote(it) }
                withoutCopy.chunked(DB_CHUNK).forEach {
                    media.deleteLocalRows(it)
                    db.albums().removeMediaEverywhere(it)
                }
            }
        }
        return LocalDeleteResult(
            deleted = gone.size, keptInCloud = kept, lost = gone.size - kept, remaining = candidates.size - gone.size,
        )
    }
}

/** Older SQLite builds (Android 10/11) allow 999 bound variables per statement. */
private const val DB_CHUNK = 400
