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
            // A resume re-reads the whole library, and in the common case nothing changed. So the table is first read as a
            // thin projection (the columns a scan compares); full rows are only loaded for the few that really change.
            val rows = dao.indexRows()
            val existing = HashMap<Long, IndexRow>(rows.size * 2)
            rows.forEach { existing[it.mediaStoreId] = it }
            val seen = HashSet<Long>(scanned.size * 2)
            val inserts = ArrayList<MediaItemEntity>()
            val changed = ArrayList<ScannedMedia>()

            for (s in scanned) {
                seen += s.mediaStoreId
                val old = existing[s.mediaStoreId]
                if (old == null) {
                    inserts += MediaItemEntity(
                        mediaStoreId = s.mediaStoreId, uri = s.uri, kind = s.kind, displayName = s.displayName,
                        mimeType = s.mimeType, sizeBytes = s.sizeBytes, dateModifiedSec = s.dateModifiedSec,
                        dateTakenMs = s.dateTakenMs, width = s.width, height = s.height, folderName = s.folderName,
                        assetId = idGenerator(), dayKey = s.dayKey, dateSource = s.dateSource,
                        isFavorite = s.isFavorite, dateAddedSec = s.dateAddedSec,
                    )
                } else if (differs(old, s)) {
                    changed += s
                }
            }

            val updates = ArrayList<MediaItemEntity>()
            var modified = 0
            if (changed.isNotEmpty()) {
                val full = HashMap<Long, MediaItemEntity>(changed.size * 2)
                changed.map { it.mediaStoreId }.chunked(CHUNK).forEach { ids -> dao.getAll(ids).forEach { full[it.mediaStoreId] = it } }
                for (s in changed) {
                    val old = full[s.mediaStoreId] ?: continue
                    var next = old.copy(
                        uri = s.uri, displayName = s.displayName, mimeType = s.mimeType, sizeBytes = s.sizeBytes,
                        dateModifiedSec = s.dateModifiedSec, dateTakenMs = s.dateTakenMs, width = s.width,
                        height = s.height, folderName = s.folderName, present = true, dayKey = s.dayKey, dateSource = s.dateSource,
                        isFavorite = s.isFavorite, dateAddedSec = s.dateAddedSec,
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
            }

            var missing = 0
            if (fullAccess) {
                val toDrop = ArrayList<Long>()
                val toPoint = ArrayList<Long>()
                for ((id, old) in existing) {
                    if (id in seen || !old.present && old.syncState == SyncState.REMOTE_ONLY) continue
                    if (old.syncState in SyncStateMachine.inFlight) continue // worker will fail it cleanly
                    missing++
                    if (old.remoteKey != null) {
                        SyncStateMachine.require(old.syncState, SyncState.REMOTE_ONLY)
                        toPoint += id
                    } else {
                        toDrop += id
                    }
                }
                toPoint.chunked(CHUNK).forEach { ids -> dao.getAll(ids).forEach { updates += it.copy(present = false, syncState = SyncState.REMOTE_ONLY, queuedAtMs = null) } }
                toDrop.chunked(CHUNK).forEach {
                    dao.deleteLocalRows(it)
                    db.albums().removeMediaEverywhere(it)
                }
            }

            if (inserts.isNotEmpty()) dao.insertAll(inserts)
            if (updates.isNotEmpty()) dao.updateAll(updates)
            IndexResult(inserts.size, updates.size, modified, missing)
        }
    }

    /** True when applying [s] to the indexed row would change anything (the same rules the full-row path applies). */
    private fun differs(old: IndexRow, s: ScannedMedia): Boolean =
        old.uri != s.uri || old.displayName != s.displayName || old.mimeType != s.mimeType || old.sizeBytes != s.sizeBytes ||
            old.dateModifiedSec != s.dateModifiedSec || old.dateTakenMs != s.dateTakenMs || old.width != s.width ||
            old.height != s.height || old.folderName != s.folderName || !old.present || old.dayKey != s.dayKey ||
            old.dateSource != s.dateSource || old.isFavorite != s.isFavorite || old.dateAddedSec != s.dateAddedSec ||
            old.syncState == SyncState.REMOTE_ONLY ||
            (old.syncState == SyncState.BACKED_UP && old.backedUpSizeBytes != null &&
                (old.backedUpSizeBytes != s.sizeBytes || old.backedUpDateModifiedSec != s.dateModifiedSec))

    private companion object {
        /** Older SQLite builds (Android 10/11) allow 999 bound variables per statement. */
        const val CHUNK = 400
    }
}
