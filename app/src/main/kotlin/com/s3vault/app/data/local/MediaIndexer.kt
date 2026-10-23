package com.s3vault.app.data.local

import androidx.room.withTransaction
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.SyncStateMachine
import java.util.UUID

data class IndexResult(val added: Int, val updated: Int, val modifiedSinceBackup: Int, val missing: Int)

/**
 * Reconciles a MediaStore scan with the Room index. Rules (see docs/synchronization.md):
 *
 * - New file -> LOCAL_ONLY with a fresh assetId.
 * - Changed (size/mtime) file that had a verified backup -> back to LOCAL_ONLY so a NEW version gets uploaded;
 *   the previous remote object stays untouched.
 * - File no longer in MediaStore: never-backed-up rows are dropped from the index; rows with a remote copy
 *   become REMOTE_ONLY. Nothing is ever deleted remotely.
 * - Missing-file handling only runs when [fullAccess] is true; with partial media access (Android 14+
 *   "selected photos") absence from the scan says nothing about deletion.
 */
class MediaIndexer(
    private val db: AppDatabase,
    private val idGenerator: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    suspend fun reconcile(scanned: List<ScannedMedia>, fullAccess: Boolean, nowMs: Long = System.currentTimeMillis()): IndexResult {
        val dao = db.media()
        return db.withTransaction {
            val existing = dao.getEverything().associateBy { it.mediaStoreId }
            val seen = HashSet<Long>(scanned.size)
            val inserts = ArrayList<MediaItemEntity>()
            val updates = ArrayList<MediaItemEntity>()
            var modified = 0

            for (s in scanned) {
                seen += s.mediaStoreId
                val old = existing[s.mediaStoreId]
                if (old == null) {
                    inserts += MediaItemEntity(
                        mediaStoreId = s.mediaStoreId, uri = s.uri, kind = s.kind, displayName = s.displayName,
                        mimeType = s.mimeType, sizeBytes = s.sizeBytes, dateModifiedSec = s.dateModifiedSec,
                        dateTakenMs = s.dateTakenMs, width = s.width, height = s.height, folderName = s.folderName,
                        assetId = idGenerator(),
                    )
                    continue
                }
                var next = old.copy(
                    uri = s.uri, displayName = s.displayName, mimeType = s.mimeType, sizeBytes = s.sizeBytes,
                    dateModifiedSec = s.dateModifiedSec, dateTakenMs = s.dateTakenMs, width = s.width,
                    height = s.height, folderName = s.folderName, present = true,
                )
                // File reappeared after being REMOTE_ONLY.
                if (old.syncState == SyncState.REMOTE_ONLY) {
                    next = next.copy(syncState = if (old.remoteKey != null) SyncState.BACKED_UP else SyncState.LOCAL_ONLY)
                }
                if (next.syncState == SyncState.BACKED_UP && next.modifiedSinceBackup()) {
                    SyncStateMachine.require(SyncState.BACKED_UP, SyncState.LOCAL_ONLY)
                    next = next.copy(syncState = SyncState.LOCAL_ONLY, lastError = null, lastErrorPermanent = false, attempts = 0)
                    modified++
                }
                if (next != old) updates += next
            }

            var missing = 0
            if (fullAccess) {
                val toDrop = ArrayList<Long>()
                for ((id, old) in existing) {
                    if (id in seen || !old.present && old.syncState == SyncState.REMOTE_ONLY) continue
                    if (old.syncState in SyncStateMachine.inFlight) continue // worker will fail it cleanly
                    missing++
                    if (old.remoteKey != null) {
                        SyncStateMachine.require(old.syncState, SyncState.REMOTE_ONLY)
                        updates += old.copy(present = false, syncState = SyncState.REMOTE_ONLY, queuedAtMs = null)
                    } else {
                        toDrop += id
                    }
                }
                if (toDrop.isNotEmpty()) {
                    toDrop.chunked(500).forEach {
                        dao.deleteLocalRows(it)
                        db.albums().removeMediaEverywhere(it)
                    }
                }
            }

            if (inserts.isNotEmpty()) dao.insertAll(inserts)
            if (updates.isNotEmpty()) dao.updateAll(updates)
            IndexResult(inserts.size, updates.size, modified, missing)
        }
    }
}
