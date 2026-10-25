package com.s3vault.app.domain.sync

import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.SyncState

/**
 * What the sync center can list. The first six describe files on this device, the last two describe objects in S3, so a
 * photo is always described from the side where it really is. [ACTIVITY] is the default: what is moving or needs attention.
 */
enum class SyncFilter(val label: String, val localStates: List<SyncState>) {
    ACTIVITY("Actividad", listOf(SyncState.UPLOADING, SyncState.VERIFYING, SyncState.QUEUED, SyncState.FAILED)),
    LOCAL_ONLY("Sin respaldar", listOf(SyncState.LOCAL_ONLY)),
    QUEUED("En cola", listOf(SyncState.QUEUED)),
    UPLOADING("Subiendo", listOf(SyncState.UPLOADING, SyncState.VERIFYING)),
    BACKED_UP("Respaldadas", listOf(SyncState.BACKED_UP)),
    FAILED("Con error", listOf(SyncState.FAILED)),
    REMOTE_ONLY("Solo en S3", emptyList()),
    RESTORING("Restaurando", emptyList());

    /** True when the rows come from the cached S3 listing instead of the device index. */
    val isRemote: Boolean get() = this == REMOTE_ONLY || this == RESTORING
}

/**
 * Every number the sync center shows. Built only from sync state that already exists (device index and cached listing), so it
 * can never disagree with what the engines do.
 */
data class SyncCounts(
    val localOnly: Int = 0,
    val queued: Int = 0,
    /** Uploading and verifying: an upload is not finished until the verification step ends. */
    val uploading: Int = 0,
    val backedUp: Int = 0,
    val failed: Int = 0,
    /** Objects in S3 with no copy on this device (according to the listing). */
    val remoteOnly: Int = 0,
    val restoring: Int = 0,
    /** Objects whose last restore attempt failed (they are listed under [remoteOnly]). */
    val restoreFailed: Int = 0,
) {
    val activity: Int get() = uploading + queued + failed
    val pendingUploads: Int get() = queued + uploading

    /** Files on this device that the backup knows about. */
    val localTotal: Int get() = localOnly + queued + uploading + backedUp + failed

    fun of(filter: SyncFilter): Int = when (filter) {
        SyncFilter.ACTIVITY -> activity
        SyncFilter.LOCAL_ONLY -> localOnly
        SyncFilter.QUEUED -> queued
        SyncFilter.UPLOADING -> uploading
        SyncFilter.BACKED_UP -> backedUp
        SyncFilter.FAILED -> failed
        SyncFilter.REMOTE_ONLY -> remoteOnly
        SyncFilter.RESTORING -> restoring
    }

    companion object {
        /** [local] maps a sync state to the number of files on this device in it. */
        fun of(local: Map<SyncState, Int>, remoteOnly: Int, restoring: Int, restoreFailed: Int) = SyncCounts(
            localOnly = local[SyncState.LOCAL_ONLY] ?: 0,
            queued = local[SyncState.QUEUED] ?: 0,
            uploading = (local[SyncState.UPLOADING] ?: 0) + (local[SyncState.VERIFYING] ?: 0),
            backedUp = local[SyncState.BACKED_UP] ?: 0,
            failed = local[SyncState.FAILED] ?: 0,
            remoteOnly = remoteOnly,
            restoring = restoring,
            restoreFailed = restoreFailed,
        )
    }
}

/**
 * How sure the app is that a copy exists in S3. "Local availability" and "confirmed remote availability" are different
 * facts: a photo on the device is not in S3 until its upload passed verification, and an object in the listing is only as
 * current as the listing.
 */
enum class CloudStatus {
    /** Uploaded and verified (size and SHA-256 matched what S3 reported). */
    CONFIRMED,

    /** The upload went through; verification has not finished, so it is not counted as backed up yet. */
    VERIFYING,

    /** Waiting or being uploaded. Nothing is in S3 yet. */
    NOT_YET,

    /** Never uploaded. */
    NONE,

    /** The last attempt failed; nothing confirmed in S3. */
    FAILED,

    /** Known only from the cached listing (an object with no local file); not re-checked. */
    LISTED,
}

object SyncPresentation {
    fun cloudStatus(state: SyncState): CloudStatus = when (state) {
        SyncState.BACKED_UP -> CloudStatus.CONFIRMED
        SyncState.VERIFYING -> CloudStatus.VERIFYING
        SyncState.UPLOADING, SyncState.QUEUED -> CloudStatus.NOT_YET
        SyncState.FAILED -> CloudStatus.FAILED
        SyncState.LOCAL_ONLY -> CloudStatus.NONE
        SyncState.REMOTE_ONLY, SyncState.RESTORING -> CloudStatus.LISTED
    }

    /** What the user can do with an item of the list. Decided from state alone, so it never offers something the engines would refuse. */
    fun actionsFor(item: MediaItem): Set<RowAction> = when (item.syncState) {
        SyncState.FAILED -> setOf(RowAction.RETRY, RowAction.OPEN)
        SyncState.QUEUED -> setOf(RowAction.CANCEL, RowAction.OPEN)
        SyncState.LOCAL_ONLY -> setOf(RowAction.BACK_UP, RowAction.OPEN)
        SyncState.UPLOADING, SyncState.VERIFYING, SyncState.BACKED_UP, SyncState.REMOTE_ONLY, SyncState.RESTORING -> setOf(RowAction.OPEN)
    }
}

enum class RowAction { RETRY, CANCEL, BACK_UP, OPEN }

/** What to tell the user about a failed item, and what they can do about it. */
data class FailureAdvice(
    val summary: String,
    val hint: String,
    val action: FailureFix,
    val kind: RemoteErrorKind?,
)

enum class FailureFix { RETRY, CREDENTIALS, PERMISSIONS, S3_SETTINGS, NOTHING }

object FailureAdviser {
    /**
     * Failure texts are written from [RemoteErrorKind.userMessage] (plus an optional technical tag), so the kind is recovered
     * from the text instead of keeping a second copy of the reason. Unknown text is shown as it is.
     */
    fun of(error: String?, permanent: Boolean, attempts: Int = 0): FailureAdvice {
        val kind = error?.let { e -> RemoteErrorKind.entries.firstOrNull { e.startsWith(it.userMessage) } }
        val summary = (error ?: "Error desconocido").substringBefore(" [").ifBlank { "Error desconocido" }
        return when (kind) {
            RemoteErrorKind.ACCESS_DENIED -> FailureAdvice(summary, "Revisa la política IAM del usuario (ListBucket, PutObject y GetObject).", FailureFix.PERMISSIONS, kind)
            RemoteErrorKind.CREDENTIALS_EXPIRED, RemoteErrorKind.CREDENTIALS_MISSING, RemoteErrorKind.CREDENTIALS_INVALID ->
                FailureAdvice(summary, "Introduce credenciales nuevas; después se reintenta solo.", FailureFix.CREDENTIALS, kind)
            RemoteErrorKind.BUCKET_NOT_FOUND, RemoteErrorKind.WRONG_REGION, RemoteErrorKind.NOT_CONFIGURED ->
                FailureAdvice(summary, "Revisa el bucket y la región en los ajustes de Amazon S3.", FailureFix.S3_SETTINGS, kind)
            RemoteErrorKind.FILE_UNAVAILABLE, RemoteErrorKind.FILE_TOO_LARGE ->
                FailureAdvice(summary, "Reintentar no lo arregla: el archivo no se puede subir tal cual.", FailureFix.NOTHING, kind)
            else -> FailureAdvice(
                summary,
                if (permanent) "Error permanente: corrige la causa y pulsa Reintentar."
                else "Se agotaron los reintentos${if (attempts > 0) " ($attempts)" else ""}. Suele ser la red o AWS: reintenta cuando haya conexión.",
                FailureFix.RETRY, kind,
            )
        }
    }
}
