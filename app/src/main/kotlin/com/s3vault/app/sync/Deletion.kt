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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

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
 * - Room is updated after every confirmed batch, so the UI never shows a photo that S3 already dropped, and a
 *   cancelled or interrupted run leaves consistent state.
 */
class CloudDeletionController(
    private val db: AppDatabase,
    private val remote: RemoteContextProvider,
    private val onVersionsDeleted: (Collection<String>) -> Unit = {},
    /** One entry per run with what was really deleted: the storage-cost estimate uses it to tell its inventory is stale. */
    private val events: EventLog? = null,
) {
    suspend fun delete(keys: List<String>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): CloudDeleteResult {
        val ctx = remote.get() ?: throw RemoteException(RemoteErrorKind.NOT_CONFIGURED)
        val wanted = keys.distinct().filter { ctx.keys.isPhotoKey(it) }
        val restoring = wanted.chunked(400).flatMap { db.remote().getByKeys(it) }
            .filter { it.state == SyncState.RESTORING }.map { it.key }.toSet()
        val eligible = wanted.filter { it !in restoring }

        val deleted = ArrayList<String>()
        val failed = LinkedHashMap<String, RemoteErrorKind>()
        var stopReason: RemoteErrorKind? = null
        var done = 0
        onProgress(0, eligible.size)

        try {
            for (batch in eligible.chunked(RemoteStorage.MAX_DELETE_BATCH)) {
                currentCoroutineContext().ensureActive()
                val reason = stopReason
                if (reason != null) {
                    batch.forEach { failed[it] = reason } // not attempted: the same cause would repeat
                } else {
                    try {
                        val report = ctx.storage.delete(batch)
                        failed += report.failed
                        if (report.deleted.isNotEmpty()) {
                            deleted += report.deleted
                            deletePreviews(ctx, report.deleted)
                            forget(report.deleted)
                        }
                        // A per-key AccessDenied is the IAM policy: the rest of the batches would be refused as well.
                        if (RemoteErrorKind.ACCESS_DENIED in report.failed.values) stopReason = RemoteErrorKind.ACCESS_DENIED
                    } catch (e: RemoteException) {
                        batch.forEach { failed[it] = e.kind }
                        stopReason = e.kind
                    }
                }
                done += batch.size
                onProgress(done, eligible.size)
            }
        } finally {
            // Also when cancelled half way: what was deleted stays deleted and must be counted.
            if (deleted.isNotEmpty()) withContext(NonCancellable) { events?.log(TransferEventType.CLOUD_DELETED, "${deleted.size} elementos") }
        }
        return CloudDeleteResult(deleted, failed, restoring.size)
    }

    /** Previews are shared by every object with the same content, so one that is still referenced is kept. */
    private suspend fun deletePreviews(ctx: RemoteContext, deletedKeys: List<String>) {
        try {
            val versions = deletedKeys.mapNotNull { ctx.keys.parse(it)?.versionId }.distinct()
            if (versions.isEmpty()) return
            val stillUsed = db.remote().versionsStillUsed(versions, deletedKeys).toSet()
            val orphaned = versions.filter { it !in stillUsed }
            if (orphaned.isEmpty()) return
            ctx.storage.delete(orphaned.map { ctx.keys.thumbnailKey(it) })
            onVersionsDeleted(orphaned)
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
            val orphans = media.orphanIdsOfRemoteKeys(keys)
            media.detachRemote(keys)
            if (orphans.isNotEmpty()) {
                media.deleteLocalRows(orphans)
                db.albums().removeMediaEverywhere(orphans)
            }
            db.remote().forget(keys)
        }
    }
}

/** Checks whether Android still has a file (the only reliable proof that a system deletion really happened). */
fun interface LocalMediaGateway {
    suspend fun exists(uri: String): Boolean
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
        val gone = candidates.filter { !gateway.exists(it.uri!!) }
        val ids = gone.map { it.mediaStoreId!! }
        var kept = 0
        if (ids.isNotEmpty()) {
            db.withTransaction {
                val media = db.media()
                val rows = ids.chunked(400).flatMap { media.getAll(it) }
                val withCopy = rows.filter { it.remoteKey != null }.map { it.mediaStoreId }
                val withoutCopy = rows.filter { it.remoteKey == null }.map { it.mediaStoreId }
                kept = withCopy.size
                withCopy.chunked(400).forEach { media.markGoneKeepingRemote(it) }
                withoutCopy.chunked(400).forEach {
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
