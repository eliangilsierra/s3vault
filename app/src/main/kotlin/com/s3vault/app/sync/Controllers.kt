package com.s3vault.app.sync

import com.s3vault.app.data.local.AlbumEntity
import com.s3vault.app.data.local.AlbumItemEntity
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.IndexResult
import com.s3vault.app.data.local.MediaIndexer
import com.s3vault.app.data.local.MediaScanSource
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.SyncStateMachine
import com.s3vault.app.settings.AppSettings
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MediaAccess { NONE, PARTIAL, FULL }

/** A counter that changes whenever the thing it watches (MediaStore) may have changed. */
interface ChangeSignal {
    val version: Long
}

/** Keeps the Room index in step with MediaStore. Serialized: scans never overlap. */
class LibraryRepository(
    private val scanner: MediaScanSource,
    private val indexer: MediaIndexer,
    private val access: () -> MediaAccess,
    /** Runs before every scan (e.g. rewrite day keys after a time zone change). */
    private val beforeScan: suspend () -> Unit = {},
    /** When given, a non-forced refresh is skipped while MediaStore and the permission are as the last scan saw them. */
    private val changes: ChangeSignal? = null,
    /** Told after a scan that found new or changed files, so work that depends on them (the face index) can be woken. */
    private val onChanged: suspend (IndexResult) -> Unit = {},
) {
    private val mutex = Mutex()
    private var scannedVersion = -1L
    private var scannedAccess: MediaAccess? = null

    /** False until a scan finished in this process: the first one is worth showing as work in progress. */
    @Volatile var hasScanned: Boolean = false
        private set

    /**
     * Returns null when there is no media permission (nothing is touched in that case).
     *
     * [force] is for an explicit request (pull to refresh, retry, a worker). Without it the scan is skipped when
     * nothing changed since the last one, which makes coming back to the app free instead of a full re-read.
     */
    suspend fun refresh(force: Boolean = true): IndexResult? = mutex.withLock {
        val a = access()
        if (a == MediaAccess.NONE) { scannedAccess = null; return null }
        val version = changes?.version
        if (!force && version != null && version == scannedVersion && a == scannedAccess) return IndexResult(0, 0, 0, 0)
        beforeScan()
        val result = indexer.reconcile(scanner.scan(), fullAccess = a == MediaAccess.FULL)
        // The version seen *before* the scan: a change that lands during it makes the next refresh run again.
        scannedVersion = version ?: -1L
        scannedAccess = a
        hasScanned = true
        if (result.added > 0 || result.updated > 0) onChanged(result)
        result
    }
}

/**
 * User-facing backup operations. All durable effects are Room state changes; WorkManager is only told to wake up.
 * Nothing here can delete a local or remote file.
 */
class BackupController(
    private val db: AppDatabase,
    private val settings: AppSettings,
    private val scheduler: WorkScheduler,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val media get() = db.media()

    /** Explicitly selected items are queued even if they are videos. Already backed-up items are skipped. */
    suspend fun enqueue(ids: List<Long>): Int {
        SyncStateMachine.require(SyncState.LOCAL_ONLY, SyncState.QUEUED)
        val n = enqueueBatched(ids)
        afterEnqueue(n)
        return n
    }

    /**
     * One transaction and one statement per 400 photos instead of one write per photo: a 300-photo selection used to make
     * the screen re-query its lists about 300 times; now it does it once.
     */
    private suspend fun enqueueBatched(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val now = nowMs()
        return db.withTransaction { ids.distinct().chunked(BATCH).sumOf { media.enqueueAll(it, now) } }
    }

    suspend fun enqueuePending(includeVideos: Boolean, kick: Boolean = true): Int {
        val n = enqueueBatched(media.pendingLocalOnly(includeVideos).map { it.mediaStoreId })
        if (kick) afterEnqueue(n)
        return n
    }

    suspend fun retryFailed(): Int {
        SyncStateMachine.require(SyncState.FAILED, SyncState.QUEUED)
        val n = enqueueBatched(media.failed().map { it.mediaStoreId })
        afterEnqueue(n)
        return n
    }

    suspend fun pause() {
        settings.setPaused(true)
        scheduler.cancelBackup() // running worker is cancelled; in-flight items return to QUEUED
        scheduler.configureAuto(settings.currentBackup())
    }

    suspend fun resume() {
        settings.setPaused(false)
        scheduler.configureAuto(settings.currentBackup())
        scheduler.kickBackup(settings.currentBackup().wifiOnly)
    }

    /**
     * Cancels the chosen uploads that have not started. They go back to "only on this device" and stay out of the automatic
     * backup until the user chooses them again. Returns how many were really waiting: one a worker already took keeps going.
     */
    suspend fun cancelQueued(ids: List<Long>): Int {
        SyncStateMachine.require(SyncState.QUEUED, SyncState.LOCAL_ONLY)
        if (ids.isEmpty()) return 0
        return db.withTransaction { ids.distinct().chunked(BATCH).sumOf { media.cancelQueued(it) } }
    }

    /** Retries these failed (or never queued) files. Same rules as [enqueue]; the name says what the user is doing. */
    suspend fun retry(ids: List<Long>): Int = enqueue(ids)

    /** Moves everything not yet started back to LOCAL_ONLY. Does not touch completed backups. */
    suspend fun cancelQueued(): Int {
        scheduler.cancelBackup()
        return media.dequeueAll()
    }

    /** Called after new credentials are saved: pending work resumes by itself. */
    suspend fun onCredentialsRenewed() {
        settings.setNeedsCredentials(false)
        if (media.queuedCount() > 0 && !settings.currentBackup().paused) scheduler.kickBackup(settings.currentBackup().wifiOnly)
    }

    private companion object {
        /** Older SQLite builds (Android 10/11) allow 999 bound variables per statement. */
        const val BATCH = 400
    }

    /** Wakes the backup worker after items were queued (unless the user paused the backup). Used by the face-based rules too. */
    suspend fun afterEnqueue(n: Int) {
        val s = settings.currentBackup()
        if (n > 0 && !s.paused) scheduler.kickBackup(s.wifiOnly)
    }
}

class RemoteLibraryController(
    private val db: AppDatabase,
    private val remote: RemoteContextProvider,
    private val catalog: RemoteCatalog,
    private val scheduler: WorkScheduler,
    private val settings: AppSettings,
) {
    /** Returns false when S3 is not configured. Throws [com.s3vault.app.domain.RemoteException] on S3 errors. */
    suspend fun refresh(): Boolean {
        val ctx = remote.get() ?: return false
        catalog.refresh(ctx)
        return true
    }

    suspend fun restore(keys: List<String>): Int {
        SyncStateMachine.require(SyncState.REMOTE_ONLY, SyncState.RESTORING)
        val n = if (keys.isEmpty()) 0 else db.withTransaction {
            keys.distinct().chunked(400).sumOf { db.remote().transitionAll(it, SyncState.REMOTE_ONLY, SyncState.RESTORING, null) }
        }
        if (n > 0) scheduler.kickRestore(settings.currentBackup().wifiOnly)
        return n
    }

    suspend fun cancelRestore(keys: List<String>) {
        SyncStateMachine.require(SyncState.RESTORING, SyncState.REMOTE_ONLY)
        if (keys.isNotEmpty()) db.withTransaction {
            keys.distinct().chunked(400).forEach { db.remote().transitionAll(it, SyncState.RESTORING, SyncState.REMOTE_ONLY, null) }
        }
        scheduler.cancelRestore()
        if (db.remote().getAll().any { it.state == SyncState.RESTORING }) scheduler.kickRestore(settings.currentBackup().wifiOnly)
    }

    suspend fun hasLocalCopy(versionId: String): Boolean = db.media().countLocalWithHash(versionId) > 0

    /** Opt-in: reads the capture date of the objects that have none (one HEAD each, resumable, WorkManager). */
    suspend fun completeCaptureDates() = scheduler.kickCloudDates(settings.currentBackup().wifiOnly)

    fun cancelCaptureDates() = scheduler.cancelCloudDates()
}

class AlbumController(private val db: AppDatabase, private val nowMs: () -> Long = System::currentTimeMillis) {
    suspend fun create(name: String): Long = db.albums().insert(AlbumEntity(name = name.trim(), createdAtMs = nowMs()))
    suspend fun rename(id: Long, name: String) = db.albums().rename(id, name.trim())

    /** Deletes the album only; its photos stay on the device and in S3. */
    suspend fun delete(id: Long) = db.albums().delete(id)

    suspend fun add(albumId: Long, mediaIds: List<Long>) =
        db.albums().addItems(mediaIds.map { AlbumItemEntity(albumId, it, nowMs()) })

    /** Removes membership only; the photo file is untouched. */
    suspend fun remove(albumId: Long, mediaIds: List<Long>) = db.albums().removeItems(albumId, mediaIds)

    /** Removes the membership of S3-only photos (by content hash); the objects in S3 are untouched. */
    suspend fun removeCloud(albumId: Long, versionIds: List<String>) = db.albums().removeRemoteItems(albumId, versionIds)
}
