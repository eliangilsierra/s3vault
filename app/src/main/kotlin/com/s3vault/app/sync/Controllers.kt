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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MediaAccess { NONE, PARTIAL, FULL }

/** Keeps the Room index in step with MediaStore. Serialized: scans never overlap. */
class LibraryRepository(
    private val scanner: MediaScanSource,
    private val indexer: MediaIndexer,
    private val access: () -> MediaAccess,
) {
    private val mutex = Mutex()

    /** Returns null when there is no media permission (nothing is touched in that case). */
    suspend fun refresh(): IndexResult? = mutex.withLock {
        val a = access()
        if (a == MediaAccess.NONE) return null
        indexer.reconcile(scanner.scan(), fullAccess = a == MediaAccess.FULL)
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
        var n = 0
        ids.forEach { n += media.enqueue(it, nowMs()) }
        afterEnqueue(n)
        return n
    }

    suspend fun enqueuePending(includeVideos: Boolean, kick: Boolean = true): Int {
        var n = 0
        media.pendingLocalOnly(includeVideos).forEach { n += media.enqueue(it.mediaStoreId, nowMs()) }
        if (kick) afterEnqueue(n)
        return n
    }

    suspend fun retryFailed(): Int {
        SyncStateMachine.require(SyncState.FAILED, SyncState.QUEUED)
        var n = 0
        media.failed().forEach { n += media.enqueue(it.mediaStoreId, nowMs()) }
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

    private suspend fun afterEnqueue(n: Int) {
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
        var n = 0
        keys.forEach { n += db.remote().transition(it, SyncState.REMOTE_ONLY, SyncState.RESTORING, null) }
        if (n > 0) scheduler.kickRestore(settings.currentBackup().wifiOnly)
        return n
    }

    suspend fun cancelRestore(keys: List<String>) {
        SyncStateMachine.require(SyncState.RESTORING, SyncState.REMOTE_ONLY)
        keys.forEach { db.remote().transition(it, SyncState.RESTORING, SyncState.REMOTE_ONLY, null) }
        scheduler.cancelRestore()
        if (db.remote().getAll().any { it.state == SyncState.RESTORING }) scheduler.kickRestore(settings.currentBackup().wifiOnly)
    }

    suspend fun hasLocalCopy(versionId: String): Boolean = db.media().countLocalWithHash(versionId) > 0
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
}
