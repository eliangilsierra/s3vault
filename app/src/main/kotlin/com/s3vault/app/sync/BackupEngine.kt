package com.s3vault.app.sync

import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.domain.AwsCredentialsProvider
import com.s3vault.app.domain.LocalFileReader
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteObjectMetadata
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.SyncStateMachine
import com.s3vault.app.domain.ThumbnailMaker
import com.s3vault.app.domain.TransferEventType
import com.s3vault.app.domain.UploadRequest
import com.s3vault.app.security.Redactor
import com.s3vault.app.security.SafeLog
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
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLEncoder

sealed interface RunOutcome {
    data class Completed(val uploaded: Int, val skippedAlreadyBackedUp: Int, val failed: Int) : RunOutcome
    /** Work remains but could not proceed now (network, throttling); the worker should retry with backoff. */
    data class RetryLater(val uploaded: Int, val reason: RemoteErrorKind) : RunOutcome
    /** Credentials missing/expired: queue is intact, nothing was marked as backed up. */
    data class NeedsCredentials(val reason: RemoteErrorKind) : RunOutcome
    /** A configuration/permission problem the user must fix (bucket, region, IAM policy, quota). */
    data class Blocked(val reason: RemoteErrorKind) : RunOutcome
    data object NotConfigured : RunOutcome
}

/**
 * Drains the persistent QUEUED set. All durable state lives in Room, so a killed process loses nothing: the next
 * run calls [AppDatabase.media] recoverInterrupted and continues.
 *
 * Invariant: BACKED_UP is only written after (a) the PUT succeeded with a client-supplied SHA-256 that S3 validated
 * and (b) a HEAD confirmed size and checksum (state VERIFYING).
 */
class BackupEngine(
    private val db: AppDatabase,
    private val remote: RemoteContextProvider,
    private val credentials: AwsCredentialsProvider,
    private val reader: LocalFileReader,
    private val progress: TransferProgress,
    private val events: EventLog,
    private val catalog: RemoteCatalog,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onTransferSuccess: suspend (Long) -> Unit = {},
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** Builds the S3 preview of each backed-up file. Null disables previews (tests, or no decoder available). */
    private val thumbnails: ThumbnailMaker? = null,
    private val previewsEnabled: suspend () -> Boolean = { true },
) {
    private val media get() = db.media()

    suspend fun run(maxConcurrency: Int): RunOutcome {
        media.recoverInterrupted()
        if (media.queuedCount() == 0) return RunOutcome.Completed(0, 0, 0)

        val ctx = remote.get() ?: return RunOutcome.NotConfigured
        try {
            credentials.getCredentials() // fail fast, before touching any item
        } catch (e: RemoteException) {
            return if (e.needsCredentials) RunOutcome.NeedsCredentials(e.kind) else RunOutcome.Blocked(e.kind)
        }

        try {
            catalog.refresh(ctx) // one LIST sweep per run; enables cross-install de-duplication
        } catch (e: RemoteException) {
            classifyRunLevel(e)?.let { return it }
        }

        var uploaded = 0
        var skipped = 0
        var failed = 0
        var retryReason: RemoteErrorKind? = null
        val deferred = HashSet<Long>()
        val gate = Semaphore(maxConcurrency.coerceIn(1, 4))

        while (true) {
            val batch = media.nextQueuedExcluding(deferred.toList(), maxConcurrency.coerceIn(1, 4) * 4)
            if (batch.isEmpty()) break
            val results = coroutineScope {
                batch.map { item -> async { gate.withPermit { process(ctx, item) } } }.awaitAll()
            }
            var stop: RunOutcome? = null
            for ((item, r) in batch.zip(results)) {
                when (r) {
                    is ItemResult.Uploaded -> uploaded++
                    is ItemResult.AlreadyBackedUp -> skipped++
                    is ItemResult.Failed -> failed++
                    is ItemResult.Deferred -> { deferred += item.mediaStoreId; retryReason = r.kind }
                    is ItemResult.NeedsCredentials -> stop = RunOutcome.NeedsCredentials(r.kind)
                    is ItemResult.Blocked -> stop = stop ?: RunOutcome.Blocked(r.kind)
                }
            }
            if (stop != null) return stop
        }
        return retryReason?.let { RunOutcome.RetryLater(uploaded, it) } ?: RunOutcome.Completed(uploaded, skipped, failed)
    }

    private fun classifyRunLevel(e: RemoteException): RunOutcome? = when {
        e.needsCredentials -> RunOutcome.NeedsCredentials(e.kind)
        e.kind == RemoteErrorKind.NETWORK || e.kind == RemoteErrorKind.THROTTLED -> RunOutcome.RetryLater(0, e.kind)
        e.kind in setOf(RemoteErrorKind.ACCESS_DENIED, RemoteErrorKind.BUCKET_NOT_FOUND, RemoteErrorKind.WRONG_REGION, RemoteErrorKind.QUOTA_EXCEEDED) ->
            RunOutcome.Blocked(e.kind)
        else -> null
    }

    private sealed interface ItemResult {
        data object Uploaded : ItemResult
        data object AlreadyBackedUp : ItemResult
        data object Failed : ItemResult
        data class Deferred(val kind: RemoteErrorKind) : ItemResult
        data class NeedsCredentials(val kind: RemoteErrorKind) : ItemResult
        data class Blocked(val kind: RemoteErrorKind) : ItemResult
    }

    private suspend fun process(ctx: RemoteContext, queued: MediaItemEntity): ItemResult {
        val id = queued.mediaStoreId
        SyncStateMachine.require(SyncState.QUEUED, SyncState.UPLOADING)
        if (media.transition(id, SyncState.QUEUED, SyncState.UPLOADING, null, false, nowMs()) == 0) return ItemResult.AlreadyBackedUp
        try {
            val item = media.get(id) ?: return ItemResult.Failed
            return transfer(ctx, item)
        } catch (e: CancellationException) {
            // Pause/cancel/stop: put the item back in the queue, nothing is lost.
            withContext(NonCancellable) { releaseToQueue(id) }
            throw e
        } catch (e: RemoteException) {
            return handleRemoteFailure(queued, e)
        } catch (e: FileNotFoundException) {
            return fail(queued, RemoteErrorKind.FILE_UNAVAILABLE, permanent = true, message = RemoteErrorKind.FILE_UNAVAILABLE.userMessage)
        } catch (e: SecurityException) {
            return fail(queued, RemoteErrorKind.FILE_UNAVAILABLE, permanent = true, message = "Sin permiso para leer el archivo local.")
        } catch (e: IOException) {
            return defer(queued, RemoteErrorKind.NETWORK, "Error de lectura: " + Redactor.describe(e))
        } catch (e: Exception) {
            return defer(queued, RemoteErrorKind.UNKNOWN, Redactor.describe(e))
        } finally {
            progress.uploadDone(id)
        }
    }

    private suspend fun transfer(ctx: RemoteContext, item: MediaItemEntity): ItemResult {
        val id = item.mediaStoreId
        if (!item.present) return fail(item, RemoteErrorKind.FILE_UNAVAILABLE, true, RemoteErrorKind.FILE_UNAVAILABLE.userMessage)
        if (item.sizeBytes > MAX_SINGLE_PUT_BYTES) return fail(item, RemoteErrorKind.FILE_TOO_LARGE, true, RemoteErrorKind.FILE_TOO_LARGE.userMessage)

        // 1. Hash only now (never on app start), and only if the cached one no longer matches (size, mtime).
        val hash = if (item.hashIsValid()) {
            ContentHash(item.contentSha256!!, Hasher.hexToBase64(item.contentSha256), item.sizeBytes)
        } else {
            val h = Hasher.sha256(reader, item.uri)
            if (item.sizeBytes > 0 && h.byteCount != item.sizeBytes) {
                return defer(item, RemoteErrorKind.INTEGRITY_MISMATCH, "El archivo cambió mientras se leía.")
            }
            media.saveHash(id, h.hexLower, item.sizeBytes, item.dateModifiedSec)
            h
        }
        val size = if (item.sizeBytes > 0) item.sizeBytes else hash.byteCount
        val versionId = hash.hexLower

        // 2. Is this exact content already in S3 (this device's earlier attempt, or another install)?
        val existingKey = findExistingKey(ctx, item, versionId, size)
        if (existingKey != null) {
            val meta = headOrNull(ctx, existingKey)
            if (meta != null && verify(meta, size, hash, requireChecksum = false)) {
                toVerifying(id)
                finishBackup(item, existingKey, versionId, size)
                uploadPreview(ctx, item, versionId)
                return ItemResult.AlreadyBackedUp
            }
        }

        // 3. Upload under {prefix}photos/{assetId}/{versionId}/{name}.
        val key = ctx.keys.photoKey(item.assetId, versionId, item.displayName)
        ctx.storage.upload(
            UploadRequest(
                key = key,
                openStream = { reader.open(item.uri) },
                sizeBytes = size,
                contentType = item.mimeType,
                sha256Base64 = hash.base64,
                metadata = mapOf(
                    "original-name" to URLEncoder.encode(item.displayName, "UTF-8"),
                    "asset-id" to item.assetId,
                    "sha256-hex" to versionId,
                    "captured-at-ms" to item.dateTakenMs.toString(),
                ),
            ),
        ) { done -> progress.upload(id, done, size) }

        // 4. Verify before declaring success.
        toVerifying(id)
        val meta = ctx.storage.head(key) ?: throw RemoteException(RemoteErrorKind.INTEGRITY_MISMATCH, detail = "object missing after upload")
        if (!verify(meta, size, hash, requireChecksum = true)) throw RemoteException(RemoteErrorKind.INTEGRITY_MISMATCH, detail = "size/checksum")
        finishBackup(item, key, versionId, size)
        uploadPreview(ctx, item, versionId)
        return ItemResult.Uploaded
    }

    /**
     * Best effort and never part of the backup's success: a few KB next to the photo so the S3 library can show it
     * without downloading the original. A failure only leaves the row in the preview backlog for [backfillPreviews].
     */
    private suspend fun uploadPreview(ctx: RemoteContext, item: MediaItemEntity, versionId: String) {
        val maker = thumbnails ?: return
        if (!previewsEnabled()) return
        try {
            val bytes = maker.create(item.uri, item.kind)
            if (bytes != null) ctx.storage.putThumbnail(ctx.keys.thumbnailKey(versionId), bytes)
            media.markThumbnailUploaded(item.mediaStoreId, versionId) // null bytes: nothing decodable, nothing to retry
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.w(TAG, "Preview not uploaded for now: " + Redactor.describe(e))
        }
    }

    /**
     * Creates the missing previews of files that were backed up before previews existed (or whose preview upload
     * failed), from the copy still on this device. Stops at the first error that would repeat for every file.
     */
    suspend fun backfillPreviews(limit: Int = DEFAULT_BACKFILL_LIMIT): Int {
        val maker = thumbnails ?: return 0
        if (!previewsEnabled()) return 0
        val ctx = remote.get() ?: return 0
        try {
            credentials.getCredentials()
        } catch (e: RemoteException) {
            return 0
        }
        var created = 0
        for (item in media.thumbnailBacklog(limit)) {
            currentCoroutineContext().ensureActive()
            val versionId = item.remoteVersionId ?: continue
            try {
                val bytes = maker.create(item.uri, item.kind)
                if (bytes != null) ctx.storage.putThumbnail(ctx.keys.thumbnailKey(versionId), bytes)
                media.markThumbnailUploaded(item.mediaStoreId, versionId)
                if (bytes != null) created++
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteException) {
                if (e.needsCredentials || !e.retryable || e.kind == RemoteErrorKind.NETWORK || e.kind == RemoteErrorKind.THROTTLED) break
            } catch (e: Exception) {
                SafeLog.w(TAG, "Preview backfill skipped one file: " + Redactor.describe(e))
            }
        }
        return created
    }

    /**
     * With a prefix-scoped ListBucket policy S3 answers HEAD on a missing object with 403 instead of 404, so both mean
     * "not there / can't confirm": fall through to a normal upload (PutObject is allowed) instead of failing the run.
     */
    private suspend fun headOrNull(ctx: RemoteContext, key: String): RemoteObjectMetadata? = try {
        ctx.storage.head(key)
    } catch (e: RemoteException) {
        if (e.kind == RemoteErrorKind.ACCESS_DENIED || e.kind == RemoteErrorKind.OBJECT_NOT_FOUND) null else throw e
    }

    private suspend fun findExistingKey(ctx: RemoteContext, item: MediaItemEntity, versionId: String, size: Long): String? {
        if (item.remoteKey != null && item.remoteVersionId == versionId) return item.remoteKey
        val sameKey = ctx.keys.photoKey(item.assetId, versionId, item.displayName)
        db.remote().get(sameKey)?.let { if (it.sizeBytes == size) return it.key }
        return db.remote().findByContent(versionId, size)?.key
    }

    /** Size must match; the SHA-256 must match when S3 reports it. Fresh uploads must report it. */
    internal fun verify(meta: RemoteObjectMetadata, expectedSize: Long, hash: ContentHash, requireChecksum: Boolean): Boolean {
        if (meta.sizeBytes != expectedSize) return false
        val remoteSum = meta.checksumSha256Base64
        if (remoteSum != null) return remoteSum == hash.base64
        if (requireChecksum) return false
        return meta.userMetadata["sha256-hex"] == hash.hexLower
    }

    private suspend fun toVerifying(id: Long) {
        // From QUEUED (already backed up elsewhere) the item is in UPLOADING here as well; both reach VERIFYING.
        SyncStateMachine.require(SyncState.UPLOADING, SyncState.VERIFYING)
        media.transition(id, SyncState.UPLOADING, SyncState.VERIFYING, null, false, nowMs())
    }

    private suspend fun finishBackup(item: MediaItemEntity, key: String, versionId: String, size: Long) {
        SyncStateMachine.require(SyncState.VERIFYING, SyncState.BACKED_UP)
        val now = nowMs()
        // Signature of the bytes that were actually backed up = the current (size, mtime) at hash time.
        val ok = media.markBackedUp(item.mediaStoreId, key, versionId, now, item.sizeBytes.takeIf { it > 0 } ?: size, item.dateModifiedSec)
        if (ok > 0) {
            events.log(TransferEventType.UPLOADED, item.displayName)
            onTransferSuccess(now)
        }
    }

    private suspend fun releaseToQueue(id: Long) {
        media.transition(id, SyncState.UPLOADING, SyncState.QUEUED, null, false, nowMs())
        media.transition(id, SyncState.VERIFYING, SyncState.QUEUED, null, false, nowMs())
    }

    private suspend fun handleRemoteFailure(item: MediaItemEntity, e: RemoteException): ItemResult {
        val id = item.mediaStoreId
        return when {
            e.needsCredentials -> {
                withContext(NonCancellable) { releaseToQueue(id) }
                events.log(TransferEventType.CREDENTIALS_NEEDED, item.displayName, e.kind.userMessage)
                ItemResult.NeedsCredentials(e.kind)
            }
            e.kind.retryable -> defer(item, e.kind, e.message)
            else -> {
                val global = e.kind in setOf(RemoteErrorKind.ACCESS_DENIED, RemoteErrorKind.BUCKET_NOT_FOUND, RemoteErrorKind.WRONG_REGION, RemoteErrorKind.QUOTA_EXCEEDED)
                fail(item, e.kind, permanent = true, message = e.message)
                if (global) ItemResult.Blocked(e.kind) else ItemResult.Failed
            }
        }
    }

    /** Retryable: back to QUEUED with an incremented attempt counter, or FAILED once attempts are exhausted. */
    private suspend fun defer(item: MediaItemEntity, kind: RemoteErrorKind, message: String?): ItemResult {
        val id = item.mediaStoreId
        val current = media.get(id)
        val attempts = (current?.attempts ?: item.attempts) + 1
        media.incrementAttempts(id)
        val from = current?.syncState ?: SyncState.UPLOADING
        if (attempts >= maxAttempts) {
            SyncStateMachine.require(from, SyncState.FAILED)
            media.transition(id, from, SyncState.FAILED, Redactor.redact(message), false, nowMs())
            events.log(TransferEventType.UPLOAD_FAILED, item.displayName, message)
            return ItemResult.Failed
        }
        SyncStateMachine.require(from, SyncState.QUEUED)
        media.transition(id, from, SyncState.QUEUED, Redactor.redact(message), false, nowMs())
        return ItemResult.Deferred(kind)
    }

    private suspend fun fail(item: MediaItemEntity, kind: RemoteErrorKind, permanent: Boolean, message: String?): ItemResult {
        val id = item.mediaStoreId
        val from = media.get(id)?.syncState ?: SyncState.UPLOADING
        SyncStateMachine.require(from, SyncState.FAILED)
        media.transition(id, from, SyncState.FAILED, Redactor.redact(message ?: kind.userMessage), permanent, nowMs())
        events.log(TransferEventType.UPLOAD_FAILED, item.displayName, message ?: kind.userMessage)
        return ItemResult.Failed
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 6
        const val DEFAULT_BACKFILL_LIMIT = 300
        private const val TAG = "BackupEngine"
        /** Simple PutObject limit is 5 GiB; multipart is out of scope for this version. */
        const val MAX_SINGLE_PUT_BYTES = 4L * 1024 * 1024 * 1024
    }
}
