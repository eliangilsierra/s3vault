package com.s3vault.app.sync

import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.RemoteObjectEntity
import com.s3vault.app.data.local.TransferEventEntity
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.RemoteStorage
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.TransferEventType
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.DateResolver
import com.s3vault.app.domain.library.DateSource
import com.s3vault.app.domain.library.MediaKinds
import java.time.ZoneId

class RemoteContext(val storage: RemoteStorage, val keys: RemoteKeys)

fun interface RemoteContextProvider {
    /** Null when S3 is not configured. */
    suspend fun get(): RemoteContext?
}

/** How the last pass over the bucket ended. Kept so the UI never presents a partial list as a complete inventory. */
data class ListingResult(val atMs: Long, val complete: Boolean, val count: Int)

/**
 * Remote catalog derived straight from the bucket: the key layout already carries assetId, versionId and
 * file name, so a plain ListObjectsV2 is enough. There is no manifest to corrupt or race on. Room only
 * caches the listing; pruning the cache never deletes objects in S3.
 *
 * The listing is streamed page by page into Room (1000 keys per request), so the first objects appear while the rest is
 * still arriving and nothing larger than a page is held in memory. Objects missing from the bucket are pruned only after a
 * pass that reached the end, never after a failed or cancelled one.
 */
class RemoteCatalog(
    private val db: AppDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val onListed: suspend (ListingResult) -> Unit = {},
    /** The last pass that reached the end of the bucket, if known (lets a backup run skip a pointless second listing). */
    private val lastCompleteListing: suspend () -> ListingResult? = { null },
) {
    /** Returns the number of cached remote objects after refresh. */
    suspend fun refresh(ctx: RemoteContext): Int {
        val dao = db.remote()
        val seen = HashSet<String>()
        val z = zone()
        var pages = 0
        try {
            ctx.storage.listPhotosPaged { page ->
                pages++
                val previous = page.map { it.key }.chunked(500).flatMap { dao.getByKeys(it) }.associateBy { it.key }
                val now = nowMs()
                val entities = page.mapNotNull { l ->
                    val parsed = ctx.keys.parse(l.key) ?: return@mapNotNull null
                    seen += l.key
                    entityFor(l.key, parsed, l.sizeBytes, l.lastModifiedMs, previous[l.key], now, z)
                }
                dao.upsertAll(entities)
            }
        } catch (e: Throwable) {
            // Earlier pages stay (they are valid), nothing is pruned, and the UI is told the inventory is incomplete.
            onListed(ListingResult(nowMs(), complete = false, count = seen.size))
            throw e
        }
        val gone = dao.allKeys().filter { it !in seen }
        if (gone.isNotEmpty()) gone.chunked(500).forEach { dao.forget(it) } // cache rows only
        dao.applyLocalCaptureDates()
        onListed(ListingResult(nowMs(), complete = true, count = seen.size))
        return seen.size
    }

    /**
     * Lists only when the cached listing is older than [maxAgeMs]. Used by backup runs, which need the listing just to avoid
     * re-uploading content that is already in S3: a few minutes of staleness costs nothing, a full pass costs one request per
     * 1000 objects.
     */
    suspend fun refreshIfStale(ctx: RemoteContext, maxAgeMs: Long): Boolean {
        val last = lastCompleteListing()
        if (last != null && last.complete && nowMs() - last.atMs in 0 until maxAgeMs) return false
        refresh(ctx)
        return true
    }

    /**
     * An object that was just uploaded by this device is known without listing the bucket again: its row appears at once
     * (with the capture date of the local file) instead of after the next full listing.
     */
    suspend fun recordUpload(ctx: RemoteContext, key: String, sizeBytes: Long, item: MediaItemEntity) {
        val parsed = ctx.keys.parse(key) ?: return
        val dao = db.remote()
        val old = dao.get(key)
        val now = nowMs()
        val base = entityFor(key, parsed, old?.sizeBytes ?: sizeBytes, old?.lastModifiedMs ?: now, old, now, zone())
        // The local file carries the capture time; a date taken from the file name or the file system is not better than the name's.
        val withDate = if (item.dateSource == DateSource.CAPTURE && base.dateSource != DateSource.CAPTURE) {
            base.copy(effectiveDateMs = item.dateTakenMs, dateSource = DateSource.CAPTURE, dayKey = DateKeys.dayKey(item.dateTakenMs, zone()), metaChecked = true)
        } else {
            base
        }
        dao.upsertAll(listOf(withDate))
    }

    /** A date learned from the object's metadata (a HEAD that was made anyway, or the opt-in completion pass). */
    suspend fun recordCaptureDate(key: String, capturedMs: Long?) {
        val dao = db.remote()
        val old = dao.get(key) ?: return
        if (capturedMs != null && old.dateSource != DateSource.CAPTURE && DateKeys.isPlausible(capturedMs, nowMs())) {
            dao.applyCapture(key, capturedMs, DateKeys.dayKey(capturedMs, zone()))
        } else {
            dao.markMetaChecked(key)
        }
    }

    private fun entityFor(
        key: String,
        parsed: RemoteKeys.ParsedKey,
        sizeBytes: Long,
        uploadedMs: Long,
        old: RemoteObjectEntity?,
        now: Long,
        zone: ZoneId,
    ): RemoteObjectEntity {
        // A capture date already learned is never downgraded by a later listing.
        val date = if (old != null && old.dateSource == DateSource.CAPTURE) {
            com.s3vault.app.domain.library.ResolvedDate(old.effectiveDateMs, DateSource.CAPTURE)
        } else {
            DateResolver.remote(null, parsed.fileName, uploadedMs, now, zone)
        }
        return RemoteObjectEntity(
            key = key,
            assetId = parsed.assetId,
            versionId = parsed.versionId,
            displayName = parsed.fileName,
            sizeBytes = sizeBytes,
            lastModifiedMs = uploadedMs,
            state = old?.state ?: SyncState.REMOTE_ONLY,
            lastError = old?.lastError,
            restoredUri = old?.restoredUri,
            effectiveDateMs = date.ms,
            dateSource = date.source,
            dayKey = DateResolver.remoteDayKey(date, zone),
            uploadDayKey = DateKeys.dayKey(uploadedMs, zone),
            mediaKind = MediaKinds.fromName(parsed.fileName),
            metaChecked = old?.metaChecked ?: false,
        )
    }
}

class EventLog(private val db: AppDatabase, private val nowMs: () -> Long = System::currentTimeMillis) {
    suspend fun log(type: TransferEventType, name: String, message: String? = null) {
        db.events().log(TransferEventEntity(timestampMs = nowMs(), type = type, name = name, message = message?.take(300)))
    }
}
