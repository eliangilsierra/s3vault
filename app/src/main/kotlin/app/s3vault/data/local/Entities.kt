package app.s3vault.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.s3vault.domain.model.ErrorKind
import app.s3vault.domain.model.MediaItem
import app.s3vault.domain.model.SyncState

/**
 * Índice local. Nunca contiene secretos (credenciales, tokens): solo metadatos de fotos y estado
 * de sincronización.
 */
@Entity(
    tableName = "media_items",
    indices = [Index("uri", unique = true), Index("state"), Index("sha256"), Index("sortDate")],
)
data class MediaItemEntity(
    @PrimaryKey val assetId: String,
    val uri: String?,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateModifiedMs: Long,
    val dateTakenMs: Long?,
    /** Fecha usada para ordenar la galería (captura, o modificación si no hay captura). */
    val sortDate: Long,
    val width: Int?,
    val height: Int?,
    val isVideo: Boolean,
    val folder: String?,
    val sha256: String?,
    val hashedSizeBytes: Long?,
    val hashedModifiedMs: Long?,
    val versionId: String?,
    val remoteKey: String?,
    val backedUpVersionId: String?,
    val state: SyncState,
    val lastBackupAtMs: Long?,
    val lastError: String?,
    val lastErrorKind: ErrorKind?,
    val attempts: Int,
)

fun MediaItemEntity.toDomain() = MediaItem(
    assetId, uri, displayName, mimeType, sizeBytes, dateModifiedMs, dateTakenMs, width, height, isVideo, folder,
    sha256, hashedSizeBytes, hashedModifiedMs, versionId, remoteKey, backedUpVersionId, state,
    lastBackupAtMs, lastError, lastErrorKind, attempts,
)

fun MediaItem.toEntity() = MediaItemEntity(
    assetId, uri, displayName, mimeType, sizeBytes, dateModifiedMs, dateTakenMs,
    sortDate = dateTakenMs?.takeIf { it > 0 } ?: dateModifiedMs,
    width, height, isVideo, folder, sha256, hashedSizeBytes, hashedModifiedMs, versionId, remoteKey,
    backedUpVersionId, state, lastBackupAtMs, lastError, lastErrorKind, attempts,
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)

/** Pertenencia a un álbum virtual. Quitar una fila nunca borra el archivo. */
@Entity(
    tableName = "album_items",
    primaryKeys = ["albumId", "assetId"],
    foreignKeys = [
        ForeignKey(AlbumEntity::class, ["id"], ["albumId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaItemEntity::class, ["assetId"], ["assetId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("assetId")],
)
data class AlbumItemEntity(
    val albumId: Long,
    val assetId: String,
    val addedAtMs: Long,
)

enum class RestoreStatus { IDLE, QUEUED, RUNNING, FAILED }

/** Caché del último LIST de S3 (catálogo derivado de los objetos; no es una base de datos remota). */
@Entity(tableName = "remote_objects", indices = [Index("versionId"), Index("assetId")])
data class RemoteObjectEntity(
    @PrimaryKey val key: String,
    val assetId: String,
    val versionId: String,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long?,
    val etag: String?,
    val refreshedAtMs: Long,
    @ColumnInfo(defaultValue = "IDLE") val restoreStatus: RestoreStatus = RestoreStatus.IDLE,
    val restoreError: String? = null,
)

@Entity(tableName = "transfer_log", indices = [Index("timestampMs")])
data class TransferLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val assetId: String?,
    val kind: String,
    val success: Boolean,
    val errorKind: ErrorKind?,
    val message: String?,
)

data class StateCount(val state: SyncState, val count: Int)

data class AlbumSummary(
    val id: Long,
    val name: String,
    val createdAtMs: Long,
    val itemCount: Int,
    val coverUri: String?,
)

data class RemoteLink(val remoteKey: String, val state: SyncState, val hasLocalFile: Boolean)
