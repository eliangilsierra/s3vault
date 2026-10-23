package com.s3vault.app.sync

import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.RemoteObjectEntity
import com.s3vault.app.data.local.TransferEventEntity
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.RemoteStorage
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.TransferEventType

class RemoteContext(val storage: RemoteStorage, val keys: RemoteKeys)

fun interface RemoteContextProvider {
    /** Null when S3 is not configured. */
    suspend fun get(): RemoteContext?
}

/**
 * Remote catalog derived straight from the bucket: the key layout already carries assetId, versionId and
 * file name, so a plain ListObjectsV2 is enough. There is no manifest to corrupt or race on. Room only
 * caches the listing; pruning the cache never deletes objects in S3.
 */
class RemoteCatalog(
    private val db: AppDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Returns the number of cached remote objects after refresh. */
    suspend fun refresh(ctx: RemoteContext): Int {
        val listed = ctx.storage.listPhotos()
        val dao = db.remote()
        val previous = dao.getAll().associateBy { it.key }
        val entities = listed.mapNotNull { l ->
            val parsed = ctx.keys.parse(l.key) ?: return@mapNotNull null
            val old = previous[l.key]
            RemoteObjectEntity(
                key = l.key,
                assetId = parsed.assetId,
                versionId = parsed.versionId,
                displayName = parsed.fileName,
                sizeBytes = l.sizeBytes,
                lastModifiedMs = l.lastModifiedMs,
                state = old?.state ?: SyncState.REMOTE_ONLY,
                lastError = old?.lastError,
                restoredUri = old?.restoredUri,
            )
        }
        dao.upsertAll(entities)
        val gone = previous.keys - entities.map { it.key }.toSet()
        if (gone.isNotEmpty()) gone.chunked(500).forEach { dao.forget(it) } // cache rows only
        return entities.size
    }
}

class EventLog(private val db: AppDatabase, private val nowMs: () -> Long = System::currentTimeMillis) {
    suspend fun log(type: TransferEventType, name: String, message: String? = null) {
        db.events().log(TransferEventEntity(timestampMs = nowMs(), type = type, name = name, message = message?.take(300)))
    }
}
