package com.s3vault.app.sync

import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.RemoteObjectEntity
import com.s3vault.app.domain.AwsCredentialsProvider
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.SyncStateMachine
import com.s3vault.app.domain.TransferEventType
import com.s3vault.app.security.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Destination for restored files. Implementations must never overwrite an existing file. */
interface RestoreSink {
    /** Opens a new, uniquely-named destination. Name collisions are resolved by the sink (e.g. "name (1).jpg"). */
    suspend fun create(displayName: String, mimeType: String): Target

    interface Target {
        val output: OutputStream
        /** Makes the file visible and returns its URI. */
        suspend fun commit(): String
        /** Removes the half-written file. */
        suspend fun abort()
    }
}

sealed interface RestoreOutcome {
    data class Done(val restored: Int, val failed: Int) : RestoreOutcome
    data class NeedsCredentials(val reason: RemoteErrorKind) : RestoreOutcome
    data class RetryLater(val reason: RemoteErrorKind) : RestoreOutcome
    data class Blocked(val reason: RemoteErrorKind) : RestoreOutcome
    data object NotConfigured : RestoreOutcome
}

/**
 * Restores objects marked RESTORING in the remote library. Download -> verify SHA-256 against the content hash
 * embedded in the key (versionId) -> only then publish into the destination. Partial downloads are kept in the
 * cache dir and resumed with a Range request guarded by If-Match (ETag).
 */
class RestoreEngine(
    private val db: AppDatabase,
    private val remote: RemoteContextProvider,
    private val credentials: AwsCredentialsProvider,
    private val sink: RestoreSink,
    private val partDir: File,
    private val progress: TransferProgress,
    private val events: EventLog,
) {
    private val dao get() = db.remote()

    suspend fun run(): RestoreOutcome {
        val ctx = remote.get() ?: return RestoreOutcome.NotConfigured
        try {
            credentials.getCredentials()
        } catch (e: RemoteException) {
            return if (e.needsCredentials) RestoreOutcome.NeedsCredentials(e.kind) else RestoreOutcome.Blocked(e.kind)
        }
        partDir.mkdirs()
        var restored = 0
        var failed = 0
        var retry: RemoteErrorKind? = null
        val seen = HashSet<String>()
        while (true) {
            val next = dao.getAll().firstOrNull { it.state == SyncState.RESTORING && it.key !in seen } ?: break
            seen += next.key
            try {
                restoreOne(ctx, next)
                restored++
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteException) {
                when {
                    e.needsCredentials -> { release(next.key, null); return RestoreOutcome.NeedsCredentials(e.kind) }
                    e.kind.retryable -> { release(next.key, e.message, keepQueued = true); retry = e.kind }
                    else -> {
                        release(next.key, e.message)
                        failed++
                        events.log(TransferEventType.RESTORE_FAILED, next.displayName, e.message)
                        if (e.kind in setOf(RemoteErrorKind.ACCESS_DENIED, RemoteErrorKind.BUCKET_NOT_FOUND, RemoteErrorKind.WRONG_REGION)) {
                            return RestoreOutcome.Blocked(e.kind)
                        }
                    }
                }
            } catch (e: Exception) {
                release(next.key, Redactor.describe(e), keepQueued = true)
                retry = RemoteErrorKind.UNKNOWN
            } finally {
                progress.downloadDone(next.key)
            }
        }
        return retry?.let { RestoreOutcome.RetryLater(it) } ?: RestoreOutcome.Done(restored, failed)
    }

    private suspend fun restoreOne(ctx: RemoteContext, obj: RemoteObjectEntity) {
        val meta = ctx.storage.head(obj.key) ?: throw RemoteException(RemoteErrorKind.OBJECT_NOT_FOUND)
        val total = meta.sizeBytes
        val part = File(partDir, partName(obj.key))
        val etagFile = File(partDir, partName(obj.key) + ".etag")
        val etag = meta.etag

        var offset = 0L
        if (part.exists() && etag != null && etagFile.exists() && etagFile.readText() == etag && part.length() in 1 until total) {
            offset = part.length()
        } else {
            part.delete()
        }
        if (etag != null) etagFile.writeText(etag)

        withContext(Dispatchers.IO) {
            FileOutputStream(part, offset > 0).use { out ->
                ctx.storage.download(obj.key, out, offset, if (offset > 0) etag else null) { done -> progress.download(obj.key, done, total) }
            }
        }

        // Integrity: the key's versionId IS the SHA-256 of the original content.
        val actual = withContext(Dispatchers.IO) { sha256Hex(part) }
        if (part.length() != total || actual != obj.versionId) {
            part.delete(); etagFile.delete()
            throw RemoteException(RemoteErrorKind.INTEGRITY_MISMATCH, detail = "restore checksum")
        }

        val mime = meta.contentType?.takeIf { it.contains('/') } ?: guessMime(obj.displayName)
        val target = sink.create(obj.displayName, mime)
        try {
            withContext(Dispatchers.IO) { part.inputStream().use { it.copyTo(target.output, 64 * 1024) } }
            val uri = target.commit()
            SyncStateMachine.require(SyncState.RESTORING, SyncState.REMOTE_ONLY)
            dao.markRestored(obj.key, uri)
            events.log(TransferEventType.RESTORED, obj.displayName)
        } catch (t: Throwable) {
            withContext(NonCancellable) { target.abort() }
            throw t
        } finally {
            part.delete(); etagFile.delete()
        }
    }

    private suspend fun release(key: String, error: String?, keepQueued: Boolean = false) {
        if (keepQueued) {
            dao.transition(key, SyncState.RESTORING, SyncState.RESTORING, Redactor.redact(error))
        } else {
            SyncStateMachine.require(SyncState.RESTORING, SyncState.REMOTE_ONLY)
            dao.transition(key, SyncState.RESTORING, SyncState.REMOTE_ONLY, Redactor.redact(error))
        }
    }

    private fun partName(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).toHex() + ".part"

    private fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }
}
