package com.s3vault.app.domain

import java.time.Instant

enum class MediaKind { IMAGE, VIDEO }

/**
 * Explicit sync state machine. See [SyncStateMachine] for the allowed transitions.
 *
 * Local rows use LOCAL_ONLY..FAILED. REMOTE_ONLY and RESTORING describe objects that exist in S3
 * (either a local row whose file vanished from MediaStore after a verified backup, or an entry of the
 * remote library).
 */
enum class SyncState {
    LOCAL_ONLY,
    QUEUED,
    UPLOADING,
    VERIFYING,
    BACKED_UP,
    FAILED,
    REMOTE_ONLY,
    RESTORING,
}

/** A photo or video of the device, as indexed in Room. */
data class MediaItem(
    val mediaStoreId: Long,
    val uri: String,
    val kind: MediaKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val dateTakenMs: Long,
    val width: Int,
    val height: Int,
    val folderName: String,
    val assetId: String,
    val syncState: SyncState,
    val remoteKey: String?,
    val remoteVersionId: String?,
    val lastBackupAtMs: Long?,
    val lastError: String?,
    val lastErrorPermanent: Boolean,
    val attempts: Int,
    val present: Boolean,
) {
    val isBackedUp: Boolean get() = syncState == SyncState.BACKED_UP
}

data class Album(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val coverMediaStoreId: Long?,
    val coverUri: String?,
)

/** One object found under `{prefix}photos/` in the bucket. */
data class RemoteObject(
    val key: String,
    val assetId: String,
    val versionId: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val state: SyncState,
    val lastError: String?,
    val availableLocally: Boolean,
)

/** Metadata that can be read with a HEAD request. */
data class RemoteObjectMetadata(
    val key: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long?,
    val etag: String?,
    val checksumSha256Base64: String?,
    val contentType: String?,
    val userMetadata: Map<String, String>,
)

data class S3Config(
    val region: String,
    val bucket: String,
    /** Normalized: empty, or ends with '/' and never starts with '/'. */
    val prefix: String,
    val authMethod: AuthMethod,
) {
    val isComplete: Boolean get() = region.isNotBlank() && bucket.isNotBlank()
}

enum class AuthMethod { TEMPORARY, STATIC }

/** Secret-bearing credentials. [toString] never prints secret values. */
class AwsCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String?,
    val expiration: Instant?,
) {
    fun isExpired(now: Instant = Instant.now(), skew: java.time.Duration = java.time.Duration.ofMinutes(1)): Boolean =
        expiration != null && !now.isBefore(expiration.minus(skew))

    override fun toString(): String =
        "AwsCredentials(accessKeyId=${accessKeyId.take(4)}…, secret=***, sessionToken=${if (sessionToken != null) "***" else "none"}, expiration=$expiration)"
}

/** Non-secret view of the stored credentials, safe for the UI. */
data class CredentialsInfo(
    val method: AuthMethod,
    val accessKeyIdHint: String,
    val hasSessionToken: Boolean,
    val expiration: Instant?,
) {
    fun isExpired(now: Instant = Instant.now()): Boolean =
        expiration != null && !now.isBefore(expiration)
}

/** CLOUD_DELETED: objects the user deleted from S3 (name = "N elementos"). */
enum class TransferEventType { UPLOADED, UPLOAD_FAILED, RESTORED, RESTORE_FAILED, PAUSED, CREDENTIALS_NEEDED, CLOUD_DELETED }

data class TransferEvent(
    val id: Long,
    val timestampMs: Long,
    val type: TransferEventType,
    val name: String,
    val message: String?,
)
