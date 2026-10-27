package com.s3vault.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.s3vault.app.domain.Album
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.TransferEvent
import com.s3vault.app.domain.TransferEventType
import com.s3vault.app.domain.library.DateSource

/**
 * Local index of device media and the source of truth for sync state.
 * Contains no secrets. The content hash is cached together with the (size, mtime) it was computed for;
 * it is only trusted while both still match (see [hashIsValid]).
 */
@Entity(
    tableName = "media_items",
    indices = [
        Index("syncState"), Index("dateTakenMs"), Index("assetId", unique = true),
        // The gallery lists present rows by date and groups them by day: both walk these indexes instead of the table.
        Index("present", "dateTakenMs"), Index("present", "dayKey"), Index("remoteKey"), Index("present", "dateAddedSec"),
    ],
)
data class MediaItemEntity(
    @PrimaryKey val mediaStoreId: Long,
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
    /** Random id used in the remote key; stable for the life of this index row. */
    val assetId: String,
    val present: Boolean = true,
    val syncState: SyncState = SyncState.LOCAL_ONLY,
    val contentSha256: String? = null,
    val hashSizeBytes: Long? = null,
    val hashDateModifiedSec: Long? = null,
    val backedUpSizeBytes: Long? = null,
    val backedUpDateModifiedSec: Long? = null,
    val remoteKey: String? = null,
    val remoteVersionId: String? = null,
    val lastBackupAtMs: Long? = null,
    val lastError: String? = null,
    val lastErrorPermanent: Boolean = false,
    val attempts: Int = 0,
    val queuedAtMs: Long? = null,
    /** True once a preview of the backed-up version exists in S3 (see RemoteKeys.thumbnailKey). Reset on every new version. */
    val thumbnailUploaded: Boolean = false,
    /**
     * Set when the user deleted this photo's S3 copy on purpose while keeping the local file. Automatic backup then
     * leaves it alone instead of silently uploading it again; selecting it and choosing "Respaldar" clears the flag.
     */
    val backupExcluded: Boolean = false,
    /** `yyyyMMdd` of [dateTakenMs] in the device zone when it was indexed (recomputed if the zone changes). */
    val dayKey: Int = 0,
    /** Where [dateTakenMs] comes from; FALLBACK dates are the file's own and shown as estimated. */
    val dateSource: DateSource = DateSource.CAPTURE,
    /**
     * Version marker of the S3 object as it was when the backup was verified (an ETag, without quotes). It is only compared
     * for equality, to notice that the object was replaced; it says nothing about the content. Null for older backups: the
     * first audit records one as the starting point.
     */
    val remoteEtag: String? = null,
    /** MediaStore's favourite flag (Android 11+; always false on Android 10, which has none). */
    val isFavorite: Boolean = false,
    /** When the file entered MediaStore (`date_added`, seconds); 0 until the first scan after the upgrade fills it in. */
    val dateAddedSec: Long = 0,
) {
    fun hashIsValid(): Boolean =
        contentSha256 != null && hashSizeBytes == sizeBytes && hashDateModifiedSec == dateModifiedSec

    /** True when the file changed after its last verified backup. */
    fun modifiedSinceBackup(): Boolean =
        backedUpSizeBytes != null && (backedUpSizeBytes != sizeBytes || backedUpDateModifiedSec != dateModifiedSec)

    fun toDomain() = MediaItem(
        mediaStoreId, uri, kind, displayName, mimeType, sizeBytes, dateModifiedSec, dateTakenMs, width, height,
        folderName, assetId, syncState, remoteKey, remoteVersionId, lastBackupAtMs, lastError, lastErrorPermanent,
        attempts, present, dateSource,
    )
}

/** Cached listing of `{prefix}photos/`. A cache only: deleting a row never deletes anything in S3. */
@Entity(
    tableName = "remote_objects",
    indices = [Index("assetId"), Index("versionId"), Index("dayKey", "effectiveDateMs"), Index("uploadDayKey")],
)
data class RemoteObjectEntity(
    @PrimaryKey val key: String,
    val assetId: String,
    val versionId: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val state: SyncState = SyncState.REMOTE_ONLY,
    val lastError: String? = null,
    val restoredUri: String? = null,
    /** Date used to place the object on the timeline; [lastModifiedMs] (the upload time) until something better is known. */
    val effectiveDateMs: Long = lastModifiedMs,
    val dateSource: DateSource = DateSource.FALLBACK,
    /** `yyyyMMdd` of the capture date, or 0 when there is none (upload time is not a capture date). */
    val dayKey: Int = 0,
    /** `yyyyMMdd` of [lastModifiedMs], used when the list is sorted and grouped by upload date. */
    val uploadDayKey: Int = 0,
    /** From the file extension; null when unknown. */
    val mediaKind: MediaKind? = null,
    /** True once the object's metadata was asked for (or no longer needs to be), so it is never requested twice. */
    val metaChecked: Boolean = false,
    /** ETag of the object as the last listing reported it (opaque marker, see [MediaItemEntity.remoteEtag]). */
    val etag: String? = null,
) {
    fun toDomain(availableLocally: Boolean) = RemoteObject(
        key, assetId, versionId, displayName, sizeBytes, lastModifiedMs, state, lastError, availableLocally || restoredUri != null,
        effectiveDateMs, dateSource, mediaKind,
    )
}

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)

/** Membership in a virtual album. Removing a row never touches the file. */
@Entity(
    tableName = "album_items",
    primaryKeys = ["albumId", "mediaStoreId"],
    foreignKeys = [ForeignKey(AlbumEntity::class, ["id"], ["albumId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mediaStoreId")],
)
data class AlbumItemEntity(
    val albumId: Long,
    val mediaStoreId: Long,
    val addedAtMs: Long,
)

/**
 * Membership of a photo that exists only in S3. It is keyed by the content hash (the `versionId` of the key), not by the
 * object key, so it survives a re-listing and never points at a file that is not there: the photo shows up only while the
 * cached listing has an object with that version.
 */
@Entity(
    tableName = "album_remote_items",
    primaryKeys = ["albumId", "versionId"],
    foreignKeys = [ForeignKey(AlbumEntity::class, ["id"], ["albumId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("versionId")],
)
data class AlbumRemoteItemEntity(
    val albumId: Long,
    val versionId: String,
    val addedAtMs: Long,
)

/** Capped local log of transfer outcomes (shown in the Backup screen). */
@Entity(tableName = "transfer_events", indices = [Index("timestampMs")])
data class TransferEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val type: TransferEventType,
    val name: String,
    val message: String?,
) {
    fun toDomain() = TransferEvent(id, timestampMs, type, name, message)
}

data class AlbumRow(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val coverMediaStoreId: Long?,
    val coverUri: String?,
    /** Key of the S3 object used as the cover when no photo of the album is on this device. */
    val coverRemoteKey: String? = null,
) {
    fun toDomain(coverRemote: RemoteObject? = null) = Album(id, name, itemCount, coverMediaStoreId, coverUri, coverRemote)
}

data class StateCount(val syncState: SyncState, val n: Int)

/** Where a long-running background job stands. Rows survive process death; a RUNNING row from a dead process becomes INTERRUPTED. */
enum class JobState { RUNNING, COMPLETED, PARTIAL, RETRY_LATER, NEEDS_ATTENTION, CANCELLED, INTERRUPTED }

/**
 * Persistent summary of the last run of one background job (backup, restore, audit, scans...). It is a summary, never a queue:
 * the work itself is always the rows in the other tables. One row per job name keeps it tiny and cheap to observe.
 */
@Entity(tableName = "background_jobs")
data class JobEntity(
    @PrimaryKey val name: String,
    val state: JobState,
    /** Items handled so far in the current (or last) run, and how many there were when it started. */
    val doneItems: Int = 0,
    val totalItems: Int = 0,
    /** Of [doneItems]: confirmed good, and failed. The difference was skipped or deferred. */
    val succeededItems: Int = 0,
    val failedItems: Int = 0,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val finishedAtMs: Long? = null,
    /** Plain-language reason of the last problem of the run; never a secret. */
    val lastError: String? = null,
)

/** Well-known job names. */
object JobNames {
    const val BACKUP = "backup"
    const val RESTORE = "restore"
    const val AUDIT = "audit"
    const val DUPLICATES = "duplicates"
    const val SEMANTIC = "semantic-index"
    const val MODEL_DOWNLOAD = "model-download"
    const val FACES = "face-index"
    const val FACE_RULES = "face-rules"
}

/** What an audit concluded about one S3 object that a backup record points to. */
enum class IntegrityVerdict { VERIFIED, MISSING, CHANGED, UNKNOWN, STALE_RECORD }

/** How strong the evidence behind a verdict is. */
enum class VerifyLevel {
    /** The object is in the listing with the expected size (and the same version marker, when one was recorded). */
    LISTING,

    /** HEAD answered with the expected size and the uploader's own `sha256-hex` metadata; S3 holds no checksum for it. */
    METADATA,

    /** HEAD returned the SHA-256 that S3 stored at upload time and it equals the one in the key. Content was never downloaded. */
    CHECKSUM,
}

/** Why. Stored as a code so the screen can explain it in plain language, and old rows stay readable. */
enum class IntegrityReason {
    NONE, NOT_LISTED, HEAD_NOT_FOUND, SIZE_MISMATCH, ETAG_CHANGED, MODIFIED_AFTER_BACKUP, CHECKSUM_MISMATCH,
    LISTING_INCOMPLETE, LISTING_EMPTY, FORBIDDEN, NO_REMOTE_CHECKSUM, TEMPORARY_ERROR, KEY_OUTSIDE_PREFIX, VERSION_MISMATCH,
}

enum class AuditMode { LISTING, DEEP }

enum class AuditStatus { RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

/** One run of the auditor: what it asked S3 and what it found. Kept so the report survives restarts. */
@Entity(tableName = "integrity_audits")
data class IntegrityAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: AuditMode,
    val status: AuditStatus,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    /** Objects the run set out to check. */
    val planned: Int = 0,
    val checked: Int = 0,
    val verified: Int = 0,
    val missing: Int = 0,
    val changed: Int = 0,
    val unknown: Int = 0,
    val stale: Int = 0,
    /** S3 requests the run made (LIST pages or HEAD requests), so the cost stays visible. */
    val requests: Int = 0,
    val error: String? = null,
)

/** The latest verdict for each S3 object a backup record points to. A new audit replaces the row; it never accumulates. */
@Entity(tableName = "integrity_results", indices = [Index("verdict")])
data class IntegrityResultEntity(
    @PrimaryKey val remoteKey: String,
    val verdict: IntegrityVerdict,
    val level: VerifyLevel,
    val reason: IntegrityReason = IntegrityReason.NONE,
    val checkedAtMs: Long,
    /** When a HEAD last confirmed the checksum; null if only the listing ever vouched for it. Drives the incremental deep audit. */
    val deepCheckedAtMs: Long? = null,
    val expectedSize: Long = 0,
    val remoteSize: Long? = null,
    val auditId: Long = 0,
)

/** A backup record seen from the auditor's side: one S3 key and what the device recorded about it. */
data class AuditTarget(
    val key: String,
    val versionId: String?,
    val expectedSize: Long,
    val etag: String?,
    val backedUpAtMs: Long?,
    /** At least one file on this device still points to this object. */
    val anyPresent: Boolean,
)

enum class DuplicateKind { EXACT, SIMILAR }

enum class AnalysisStatus {
    /** A usable perceptual hash was computed. */
    OK,

    /** The picture has too little detail (a flat colour, a blank screenshot) for a hash to mean anything: never matched. */
    FLAT,

    /** The file could not be decoded. Retried only if the file changes. */
    UNREADABLE,
}

/**
 * Visual fingerprint of one picture. Valid only while the file still has the size and modification time it had when it was
 * analysed (the same rule as the content hash cache), so an unchanged file is never analysed twice.
 */
@Entity(tableName = "media_analysis")
data class MediaAnalysisEntity(
    @PrimaryKey val mediaStoreId: Long,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    /** 64-bit perceptual hash; null unless [status] is OK. */
    val phash: Long?,
    val status: AnalysisStatus,
    val analyzedAtMs: Long,
)

/**
 * A set of pictures found to be the same (EXACT: identical bytes) or probably the same (SIMILAR: perceptual match). The groups are
 * rebuilt after a scan; members that leave the device simply stop counting, so a group never lists a file that is gone.
 */
@Entity(tableName = "duplicate_groups", indices = [Index("signature", unique = true), Index("kind")])
data class DuplicateGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: DuplicateKind,
    /** EXACT: the SHA-256. SIMILAR: the sorted member ids. A group whose members change is a new group. */
    val signature: String,
    val createdAtMs: Long,
)

@Entity(
    tableName = "duplicate_members",
    primaryKeys = ["groupId", "mediaStoreId"],
    foreignKeys = [ForeignKey(DuplicateGroupEntity::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mediaStoreId")],
)
data class DuplicateMemberEntity(
    val groupId: Long,
    val mediaStoreId: Long,
    /** Hamming distance to the group's first member (0 for exact copies). */
    val distance: Int = 0,
)

/** Groups the user said are not duplicates. The signature is remembered so the same set is not offered again. */
@Entity(tableName = "duplicate_ignored")
data class DuplicateIgnoredEntity(
    @PrimaryKey val signature: String,
    val ignoredAtMs: Long,
)

/** What a scan needs to read a file. */
data class ScanCandidate(val mediaStoreId: Long, val uri: String, val sizeBytes: Long, val dateModifiedSec: Long)

/** One picture of a group, with what the user needs to choose which copy to keep. */
data class GroupMemberRow(
    val groupId: Long,
    val distance: Int,
    @androidx.room.Embedded val media: MediaItemEntity,
)

data class GroupSummaryRow(val id: Long, val kind: DuplicateKind, val signature: String, val n: Int, val bytes: Long, val biggest: Long)

data class GroupTotals(val groupCount: Int, val items: Int, val recoverableBytes: Long)

/** A smart album the user defined: only its name and the question it asks. Which photos match is decided when it is opened. */
@Entity(tableName = "smart_albums")
data class SmartAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** [com.s3vault.app.domain.smart.SmartFilterCodec] JSON. */
    val filterJson: String,
    val createdAtMs: Long,
)

/**
 * The vector that stands for one picture in the shared text-picture space, kept only on this device. Valid for one model and for
 * the file as it was when it was embedded (size and modification time): an unchanged file is embedded once in its life, and a
 * different model never reads another model's vectors. [ok] false records a picture that could not be decoded, so it is not
 * retried until it changes.
 */
@Entity(tableName = "semantic_embeddings", indices = [Index("modelId")])
class SemanticEmbeddingEntity(
    @PrimaryKey val mediaStoreId: Long,
    val modelId: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val ok: Boolean,
    /** Dequantisation factor of [vector]. */
    val scale: Float,
    /** Signed bytes, one per dimension (see EmbeddingCodec). Empty when [ok] is false. */
    val vector: ByteArray,
    val indexedAtMs: Long,
)

/**
 * The key of the private vault, wrapped (encrypted) by an Android Keystore key that needs the user's screen-lock credential or
 * biometrics to be used (see [com.s3vault.app.domain.privacy.VaultKeyring]). One row. Without the Keystore key this row is
 * useless, which is why a restored copy of the database on another device cannot open anything. [keyCheck] is a small sealed
 * constant that tells a wrong or lost key apart from damaged files.
 */
@Entity(tableName = "private_vault")
class PrivateVaultEntity(
    @PrimaryKey val id: Int = 1,
    val wrappedKey: ByteArray,
    val wrapIv: ByteArray,
    val keyCheck: ByteArray,
    val createdAtMs: Long,
)

/** A private album. The name is sealed with the vault key: with the vault locked the database says only that albums exist. */
@Entity(tableName = "private_albums")
class PrivateAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nameSealed: ByteArray,
    val createdAtMs: Long,
)

/**
 * One protected picture. The picture and its preview live in the app's private storage as `<fileName>.v` and `<fileName>.t`,
 * both sealed. Everything that describes the picture (name, type, size, dimensions, dates, SHA-256 of the original) is in
 * [metaSealed], so nothing that identifies it is readable without the vault key. [sealedBytes] is the size of the sealed file.
 */
@Entity(
    tableName = "private_items",
    foreignKeys = [ForeignKey(entity = PrivateAlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("albumId")],
)
class PrivateItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumId: Long,
    val fileName: String,
    val sealedBytes: Long,
    val addedAtMs: Long,
    val metaSealed: ByteArray,
)

data class PrivateAlbumCount(val albumId: Long, val items: Int, val bytes: Long)
