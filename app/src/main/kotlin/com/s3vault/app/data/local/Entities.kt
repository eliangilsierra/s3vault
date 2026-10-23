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

/**
 * Local index of device media and the source of truth for sync state.
 * Contains no secrets. The content hash is cached together with the (size, mtime) it was computed for;
 * it is only trusted while both still match (see [hashIsValid]).
 */
@Entity(
    tableName = "media_items",
    indices = [Index("syncState"), Index("dateTakenMs"), Index("assetId", unique = true)],
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
) {
    fun hashIsValid(): Boolean =
        contentSha256 != null && hashSizeBytes == sizeBytes && hashDateModifiedSec == dateModifiedSec

    /** True when the file changed after its last verified backup. */
    fun modifiedSinceBackup(): Boolean =
        backedUpSizeBytes != null && (backedUpSizeBytes != sizeBytes || backedUpDateModifiedSec != dateModifiedSec)

    fun toDomain() = MediaItem(
        mediaStoreId, uri, kind, displayName, mimeType, sizeBytes, dateModifiedSec, dateTakenMs, width, height,
        folderName, assetId, syncState, remoteKey, remoteVersionId, lastBackupAtMs, lastError, lastErrorPermanent,
        attempts, present,
    )
}

/** Cached listing of `{prefix}photos/`. A cache only: deleting a row never deletes anything in S3. */
@Entity(tableName = "remote_objects", indices = [Index("assetId"), Index("versionId")])
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
) {
    fun toDomain(availableLocally: Boolean) = RemoteObject(
        key, assetId, versionId, displayName, sizeBytes, lastModifiedMs, state, lastError, availableLocally || restoredUri != null,
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
) {
    fun toDomain() = Album(id, name, itemCount, coverMediaStoreId, coverUri)
}

data class StateCount(val syncState: SyncState, val n: Int)
