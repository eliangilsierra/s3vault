package app.s3vault.data.local

import app.s3vault.data.remote.RemoteCatalogStore
import app.s3vault.data.remote.RemoteEntry
import app.s3vault.domain.model.ErrorKind
import app.s3vault.domain.model.MediaItem
import app.s3vault.domain.model.SyncState
import app.s3vault.domain.repo.BackupStore
import app.s3vault.domain.repo.TransferKind
import app.s3vault.domain.repo.TransferLog
import java.time.Clock

/** Implementación de [BackupStore] sobre Room: la fuente de verdad de los estados. */
class RoomBackupStore(private val db: AppDatabase) : BackupStore {
    private val dao = db.media()
    override suspend fun get(assetId: String): MediaItem? = dao.get(assetId)?.toDomain()
    override suspend fun queued(limit: Int): List<MediaItem> = dao.queued(limit).map { it.toDomain() }
    override suspend fun compareAndSet(expected: SyncState, updated: MediaItem): Boolean =
        dao.compareAndSet(expected, updated.toEntity())

    override suspend fun recoverInterrupted(): Int = dao.recoverInterrupted()
    override suspend fun upsert(item: MediaItem) = dao.upsert(item.toEntity())
}

class RoomTransferLog(private val db: AppDatabase, private val clock: Clock = Clock.systemUTC()) : TransferLog {
    override suspend fun record(assetId: String?, kind: TransferKind, success: Boolean, errorKind: ErrorKind?, message: String?) {
        val dao = db.transferLog()
        dao.insert(TransferLogEntity(timestampMs = clock.millis(), assetId = assetId, kind = kind.name, success = success, errorKind = errorKind, message = message))
        dao.trim(KEEP)
    }

    private companion object { const val KEEP = 500 }
}

/** Caché del catálogo remoto. También implementa el índice de deduplicación por versionId. */
class RoomRemoteCatalogStore(private val db: AppDatabase) : RemoteCatalogStore {
    private val dao = db.remoteObjects()

    override suspend fun replaceAll(entries: List<RemoteEntry>, refreshedAtMs: Long) {
        val previous = dao.getAll().associateBy { it.key }
        val rows = entries.map {
            val old = previous[it.key]
            RemoteObjectEntity(
                key = it.key, assetId = it.assetId, versionId = it.versionId, name = it.name,
                sizeBytes = it.sizeBytes, lastModifiedMs = it.lastModifiedMs, etag = it.etag, refreshedAtMs = refreshedAtMs,
                restoreStatus = old?.restoreStatus ?: RestoreStatus.IDLE, restoreError = old?.restoreError,
            )
        }
        // Solo se actualiza la caché local; jamás se borra nada en S3.
        dao.replaceAll(rows)
    }

    override suspend fun lastRefreshMs(): Long? = dao.lastRefresh()
    override suspend fun findKeyByVersion(versionId: String): String? = dao.findKeyByVersion(versionId)

    override suspend fun remember(key: String, sizeBytes: Long, lastModifiedMs: Long?, etag: String?) {
        val parsed = key.split('/').takeLast(3)
        if (parsed.size < 3) return
        val old = dao.get(key)
        dao.upsert(
            RemoteObjectEntity(
                key = key, assetId = parsed[0], versionId = parsed[1], name = parsed[2], sizeBytes = sizeBytes,
                lastModifiedMs = lastModifiedMs, etag = etag, refreshedAtMs = old?.refreshedAtMs ?: 0L,
                restoreStatus = old?.restoreStatus ?: RestoreStatus.IDLE, restoreError = old?.restoreError,
            ),
        )
    }
}
