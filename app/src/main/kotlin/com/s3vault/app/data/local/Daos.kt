package com.s3vault.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import com.s3vault.app.domain.SyncState
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class HashedItem(val mediaStoreId: Long, val contentSha256: String?, val remoteVersionId: String?)

data class RemoteDateRow(val key: String, val effectiveDateMs: Long, val lastModifiedMs: Long, val dateSource: com.s3vault.app.domain.library.DateSource)

data class LocalDateRow(val mediaStoreId: Long, val dateTakenMs: Long)

/** What a library scan needs to know about an indexed row to decide whether it changed. */
data class IndexRow(
    val mediaStoreId: Long,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val dateTakenMs: Long,
    val width: Int,
    val height: Int,
    val folderName: String,
    val present: Boolean,
    val dayKey: Int,
    val dateSource: com.s3vault.app.domain.library.DateSource,
    val syncState: SyncState,
    val remoteKey: String?,
    val backedUpSizeBytes: Long?,
    val backedUpDateModifiedSec: Long?,
    val isFavorite: Boolean,
    val dateAddedSec: Long,
)

@Dao
interface MediaDao {
    /** Local files whose content is known (hashed before an upload, or linked to a remote version). */
    @Query("SELECT mediaStoreId, contentSha256, remoteVersionId FROM media_items WHERE present = 1 AND (contentSha256 IS NOT NULL OR remoteVersionId IS NOT NULL)")
    suspend fun hashedItems(): List<HashedItem>

    // ---- Smart gallery: dynamic queries built by LocalLibrarySql (every value bound, never concatenated).

    @RawQuery(observedEntities = [MediaItemEntity::class])
    fun observeDayCounts(query: SupportSQLiteQuery): Flow<List<DayCount>>

    @RawQuery(observedEntities = [MediaItemEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery
    suspend fun rows(query: SupportSQLiteQuery): List<MediaItemEntity>

    @RawQuery
    suspend fun idList(query: SupportSQLiteQuery): List<Long>

    @RawQuery
    suspend fun countOnce(query: SupportSQLiteQuery): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE present = 1")
    fun observePresentCount(): Flow<Int>

    /** Photos that sit on the timeline by their file date because no capture date exists. */
    @Query("SELECT COUNT(*) FROM media_items WHERE present = 1 AND dateSource = 'FALLBACK'")
    fun observeUndatedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE mediaStoreId IN (:ids) AND remoteKey IS NOT NULL")
    suspend fun countWithRemote(ids: List<Long>): Int

    @Query(LocalLibrarySql.FOLDERS)
    fun observeFolders(): Flow<List<FolderCount>>

    @Query("SELECT * FROM media_items WHERE mediaStoreId = :id")
    suspend fun get(id: Long): MediaItemEntity?

    @Query("SELECT mediaStoreId, dateTakenMs FROM media_items")
    suspend fun datesForZoneChange(): List<LocalDateRow>

    @Query("UPDATE media_items SET dayKey = :day WHERE mediaStoreId = :id")
    suspend fun setDayKey(id: Long, day: Int)

    @Query("SELECT * FROM media_items WHERE mediaStoreId = :id")
    fun observe(id: Long): Flow<MediaItemEntity?>

    @Query("SELECT * FROM media_items WHERE mediaStoreId IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<MediaItemEntity>

    @Query("SELECT * FROM media_items")
    suspend fun getEverything(): List<MediaItemEntity>

    /**
     * Only the columns a library scan compares, so reconciling a large library does not materialise every full row
     * (see [com.s3vault.app.data.local.MediaIndexer]).
     */
    @Query(
        "SELECT mediaStoreId, uri, displayName, mimeType, sizeBytes, dateModifiedSec, dateTakenMs, width, height, folderName, present, " +
            "dayKey, dateSource, syncState, remoteKey, backedUpSizeBytes, backedUpDateModifiedSec, isFavorite, dateAddedSec FROM media_items",
    )
    suspend fun indexRows(): List<IndexRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Update
    suspend fun updateAll(items: List<MediaItemEntity>)

    @Update
    suspend fun update(item: MediaItemEntity)

    @Query("DELETE FROM media_items WHERE mediaStoreId IN (:ids)")
    suspend fun deleteLocalRows(ids: List<Long>)

    @Query("SELECT syncState, COUNT(*) AS n FROM media_items WHERE present = 1 OR syncState != 'LOCAL_ONLY' GROUP BY syncState")
    fun observeCounts(): Flow<List<StateCount>>

    @Query("SELECT * FROM media_items WHERE syncState = 'QUEUED' AND present = 1 AND mediaStoreId NOT IN (:excluded) ORDER BY queuedAtMs ASC, mediaStoreId ASC LIMIT :limit")
    suspend fun nextQueuedExcluding(excluded: List<Long>, limit: Int): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE contentSha256 = :sha AND present = 1")
    suspend fun countLocalWithHash(sha: String): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE syncState = 'QUEUED' AND present = 1")
    suspend fun queuedCount(): Int

    @Query("SELECT * FROM media_items WHERE syncState = 'LOCAL_ONLY' AND present = 1 AND backupExcluded = 0 AND (:includeVideos = 1 OR kind = 'IMAGE')")
    suspend fun pendingLocalOnly(includeVideos: Boolean): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE syncState = 'FAILED' AND present = 1")
    suspend fun failed(): List<MediaItemEntity>

    /**
     * Optimistic state change: succeeds only if the row is still in [from]. Callers validate the pair with
     * SyncStateMachine first.
     */
    @Query(
        "UPDATE media_items SET syncState = :to, lastError = :error, lastErrorPermanent = :permanent, " +
            "queuedAtMs = CASE WHEN :to = 'QUEUED' THEN COALESCE(queuedAtMs, :nowMs) ELSE NULL END " +
            "WHERE mediaStoreId = :id AND syncState = :from",
    )
    suspend fun transition(id: Long, from: SyncState, to: SyncState, error: String?, permanent: Boolean, nowMs: Long): Int

    @Query("UPDATE media_items SET attempts = attempts + 1 WHERE mediaStoreId = :id")
    suspend fun incrementAttempts(id: Long)

    @Query("UPDATE media_items SET contentSha256 = :sha, hashSizeBytes = :size, hashDateModifiedSec = :mtime WHERE mediaStoreId = :id")
    suspend fun saveHash(id: Long, sha: String, size: Long, mtime: Long)

    @Query(
        "UPDATE media_items SET syncState = 'BACKED_UP', remoteKey = :key, remoteVersionId = :version, lastBackupAtMs = :nowMs, " +
            "backedUpSizeBytes = :size, backedUpDateModifiedSec = :mtime, lastError = NULL, lastErrorPermanent = 0, attempts = 0, queuedAtMs = NULL, " +
            "thumbnailUploaded = 0, backupExcluded = 0, remoteEtag = :etag " +
            "WHERE mediaStoreId = :id AND syncState = 'VERIFYING'",
    )
    suspend fun markBackedUp(id: Long, key: String, version: String, nowMs: Long, size: Long, mtime: Long, etag: String? = null): Int

    @Query("UPDATE media_items SET syncState = 'QUEUED', queuedAtMs = :nowMs, lastError = NULL, lastErrorPermanent = 0, attempts = 0, backupExcluded = 0 WHERE mediaStoreId = :id AND syncState IN ('LOCAL_ONLY', 'FAILED')")
    suspend fun enqueue(id: Long, nowMs: Long): Int

    /** [enqueue] for many rows in one statement (and so one Room invalidation instead of one per photo). Returns how many moved. */
    @Query("UPDATE media_items SET syncState = 'QUEUED', queuedAtMs = :nowMs, lastError = NULL, lastErrorPermanent = 0, attempts = 0, backupExcluded = 0 WHERE mediaStoreId IN (:ids) AND syncState IN ('LOCAL_ONLY', 'FAILED')")
    suspend fun enqueueAll(ids: List<Long>, nowMs: Long): Int

    @Query("UPDATE media_items SET thumbnailUploaded = 1 WHERE mediaStoreId = :id AND remoteVersionId = :versionId")
    suspend fun markThumbnailUploaded(id: Long, versionId: String)

    /** Backed-up files on this device whose S3 preview has not been created yet (older backups, or a failed preview upload). */
    @Query(
        "SELECT * FROM media_items WHERE syncState = 'BACKED_UP' AND present = 1 AND thumbnailUploaded = 0 " +
            "AND remoteVersionId IS NOT NULL ORDER BY dateTakenMs DESC LIMIT :limit",
    )
    suspend fun thumbnailBacklog(limit: Int): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE syncState = 'BACKED_UP' AND present = 1 AND thumbnailUploaded = 0 AND remoteVersionId IS NOT NULL")
    fun observeThumbnailBacklog(): Flow<Int>

    @Query("SELECT * FROM media_items WHERE remoteKey IN (:keys)")
    suspend fun withRemoteKeys(keys: List<String>): List<MediaItemEntity>

    /**
     * The S3 copy of these files was deleted on purpose: forget the link, flag them so automatic backup does not
     * upload them again, and show them as "only on this device". In-flight rows keep their state (a worker owns them).
     */
    @Query(
        "UPDATE media_items SET remoteKey = NULL, remoteVersionId = NULL, lastBackupAtMs = NULL, backedUpSizeBytes = NULL, " +
            "backedUpDateModifiedSec = NULL, thumbnailUploaded = 0, backupExcluded = 1, lastError = NULL, lastErrorPermanent = 0, attempts = 0, " +
            "syncState = CASE WHEN syncState IN ('QUEUED', 'UPLOADING', 'VERIFYING') THEN syncState ELSE 'LOCAL_ONLY' END " +
            "WHERE present = 1 AND remoteKey IN (:keys)",
    )
    suspend fun detachRemote(keys: List<String>): Int

    /** Rows that existed only as a pointer to an S3 copy (the file is gone from the device). */
    @Query("SELECT mediaStoreId FROM media_items WHERE present = 0 AND remoteKey IN (:keys)")
    suspend fun orphanIdsOfRemoteKeys(keys: List<String>): List<Long>

    /** Files that left the device through a confirmed deletion: keep a pointer to the S3 copy, if there is one. */
    @Query("UPDATE media_items SET present = 0, syncState = 'REMOTE_ONLY', queuedAtMs = NULL WHERE mediaStoreId IN (:ids) AND remoteKey IS NOT NULL AND syncState NOT IN ('UPLOADING', 'VERIFYING')")
    suspend fun markGoneKeepingRemote(ids: List<Long>): Int

    /** Process-restart recovery: nothing can be mid-transfer when no worker is running. */
    @Query("UPDATE media_items SET syncState = 'QUEUED' WHERE syncState IN ('UPLOADING', 'VERIFYING')")
    suspend fun recoverInterrupted(): Int

    @Query("UPDATE media_items SET syncState = 'LOCAL_ONLY', queuedAtMs = NULL WHERE syncState = 'QUEUED'")
    suspend fun dequeueAll(): Int

    // ---- Smart albums: see SmartAlbumSql. The tables a clause may read are all observed, so an album follows every change.

    @RawQuery(observedEntities = [MediaItemEntity::class, IntegrityResultEntity::class, AlbumItemEntity::class])
    fun observeSmartCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery(observedEntities = [MediaItemEntity::class, IntegrityResultEntity::class, AlbumItemEntity::class])
    fun observeSmartDays(query: SupportSQLiteQuery): Flow<List<DayCount>>

    // ---- Space recovery: see SpaceRecoverySql. Observed tables include the audit results and album membership the buckets read.

    @RawQuery(observedEntities = [MediaItemEntity::class, IntegrityResultEntity::class, AlbumItemEntity::class])
    fun observeSpaceBuckets(query: SupportSQLiteQuery): Flow<List<SpaceBucketRow>>

    @RawQuery(observedEntities = [MediaItemEntity::class, IntegrityResultEntity::class, AlbumItemEntity::class])
    fun observeSpaceGroups(query: SupportSQLiteQuery): Flow<List<SpaceGroupRow>>

    @RawQuery(observedEntities = [MediaItemEntity::class, IntegrityResultEntity::class, AlbumItemEntity::class])
    fun observeSpaceRows(query: SupportSQLiteQuery): Flow<List<MediaItemEntity>>

    // ---- Integrity auditor.

    /** Every S3 object a backup record points to, once per key (several files can share one object). */
    @Query(
        "SELECT remoteKey AS `key`, MIN(remoteVersionId) AS versionId, MAX(COALESCE(backedUpSizeBytes, sizeBytes)) AS expectedSize, " +
            "MIN(remoteEtag) AS etag, MAX(lastBackupAtMs) AS backedUpAtMs, MAX(present) AS anyPresent " +
            "FROM media_items WHERE remoteKey IS NOT NULL AND syncState IN ('BACKED_UP', 'REMOTE_ONLY') GROUP BY remoteKey",
    )
    suspend fun auditTargets(): List<AuditTarget>

    /** First audit of an older backup: its current version marker becomes the starting point for later comparisons. */
    @Query("UPDATE media_items SET remoteEtag = :etag WHERE remoteKey = :key AND remoteEtag IS NULL")
    suspend fun adoptEtag(key: String, etag: String): Int

    /**
     * Repair by re-upload, chosen by the user: the file on this device goes back to "not backed up" with no link to the bad S3
     * object, so the normal queue uploads it again and verifies it before it counts as backed up. Only verified-backed-up
     * files that are still on the device qualify.
     */
    @Query(
        "UPDATE media_items SET syncState = 'LOCAL_ONLY', remoteKey = NULL, remoteVersionId = NULL, remoteEtag = NULL, lastBackupAtMs = NULL, " +
            "backedUpSizeBytes = NULL, backedUpDateModifiedSec = NULL, thumbnailUploaded = 0, backupExcluded = 0, lastError = NULL, " +
            "lastErrorPermanent = 0, attempts = 0, queuedAtMs = NULL WHERE present = 1 AND syncState = 'BACKED_UP' AND remoteKey IN (:keys)",
    )
    suspend fun resetForReupload(keys: List<String>): Int

    @Query("SELECT mediaStoreId FROM media_items WHERE present = 1 AND syncState = 'BACKED_UP' AND remoteKey IN (:keys)")
    suspend fun idsBackedUpAs(keys: List<String>): List<Long>

    // ---- Sync center: every number and row comes from the same sync state the engines write; nothing is a second queue.

    /** Sync state of the files on this device (rows whose file is gone are S3 objects, counted from the S3 side). */
    @Query("SELECT syncState, COUNT(*) AS n FROM media_items WHERE present = 1 GROUP BY syncState")
    fun observeLocalStateCounts(): Flow<List<StateCount>>

    /**
     * The first [limit] files in the given states, in-flight work first, then the queue in queue order, then errors, then the
     * rest newest first. The list grows by raising [limit]; it is never read whole.
     */
    @Query(
        "SELECT * FROM media_items WHERE present = 1 AND syncState IN (:states) ORDER BY " +
            "CASE syncState WHEN 'UPLOADING' THEN 0 WHEN 'VERIFYING' THEN 1 WHEN 'QUEUED' THEN 2 WHEN 'FAILED' THEN 3 ELSE 4 END, " +
            "COALESCE(queuedAtMs, 0) ASC, dateTakenMs DESC, mediaStoreId DESC LIMIT :limit",
    )
    fun observeLocalByStates(states: List<SyncState>, limit: Int): Flow<List<MediaItemEntity>>

    /**
     * The user cancelled these queued uploads on purpose: they go back to "only on this device" and are flagged so the
     * automatic backup does not queue them again behind their back (choosing them and pressing "Respaldar" clears the flag).
     * Only rows still waiting are touched; one that already started keeps going.
     */
    @Query("UPDATE media_items SET syncState = 'LOCAL_ONLY', queuedAtMs = NULL, backupExcluded = 1 WHERE mediaStoreId IN (:ids) AND syncState = 'QUEUED'")
    suspend fun cancelQueued(ids: List<Long>): Int
}

@Dao
interface RemoteDao {
    @Query("SELECT * FROM remote_objects ORDER BY lastModifiedMs DESC")
    fun observeAll(): Flow<List<RemoteObjectEntity>>

    @Query("SELECT * FROM remote_objects WHERE key = :key")
    suspend fun get(key: String): RemoteObjectEntity?

    // ---- Smart gallery over the cached listing (nothing here talks to S3).

    @RawQuery(observedEntities = [RemoteObjectEntity::class, MediaItemEntity::class])
    fun observeDayCounts(query: SupportSQLiteQuery): Flow<List<DayCount>>

    @RawQuery(observedEntities = [RemoteObjectEntity::class, MediaItemEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery
    suspend fun rows(query: SupportSQLiteQuery): List<RemoteObjectEntity>

    @RawQuery
    suspend fun keyList(query: SupportSQLiteQuery): List<String>

    @RawQuery
    suspend fun countOnce(query: SupportSQLiteQuery): Int

    @Query("SELECT COUNT(*) FROM remote_objects")
    fun observeObjectCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM remote_objects")
    suspend fun objectCount(): Int

    /** Objects listed apart because they have no capture date. */
    @Query("SELECT COUNT(*) FROM remote_objects WHERE dayKey = 0")
    fun observeUndatedCount(): Flow<Int>

    @Query("SELECT * FROM remote_objects")
    suspend fun getAll(): List<RemoteObjectEntity>

    @Query("SELECT `key` FROM remote_objects")
    suspend fun allKeys(): List<String>

    /**
     * Objects backed by a local file that has a real capture date take it from there: free, exact and without a request.
     * Objects whose date is already a capture date are left alone.
     */
    @Query(
        "UPDATE remote_objects SET " +
            "effectiveDateMs = (SELECT m.dateTakenMs FROM media_items m WHERE m.remoteKey = remote_objects.`key` AND m.dateSource = 'CAPTURE' LIMIT 1), " +
            "dayKey = (SELECT m.dayKey FROM media_items m WHERE m.remoteKey = remote_objects.`key` AND m.dateSource = 'CAPTURE' LIMIT 1), " +
            "dateSource = 'CAPTURE', metaChecked = 1 " +
            "WHERE dateSource != 'CAPTURE' AND EXISTS (SELECT 1 FROM media_items m WHERE m.remoteKey = remote_objects.`key` AND m.dateSource = 'CAPTURE')",
    )
    suspend fun applyLocalCaptureDates(): Int

    /** Objects whose date is not a capture date and whose metadata was never asked for: the candidates of the opt-in HEAD pass. */
    @Query("SELECT * FROM remote_objects WHERE dateSource != 'CAPTURE' AND metaChecked = 0 LIMIT :limit")
    suspend fun pendingMeta(limit: Int): List<RemoteObjectEntity>

    @Query("SELECT COUNT(*) FROM remote_objects WHERE dateSource != 'CAPTURE' AND metaChecked = 0")
    fun observePendingMetaCount(): Flow<Int>

    @Query("UPDATE remote_objects SET effectiveDateMs = :ms, dateSource = 'CAPTURE', dayKey = :day, metaChecked = 1 WHERE `key` = :key")
    suspend fun applyCapture(key: String, ms: Long, day: Int)

    @Query("UPDATE remote_objects SET metaChecked = 1 WHERE `key` = :key")
    suspend fun markMetaChecked(key: String)

    /** Rewrites the day keys after the device time zone changed. Capture dates only: an unknown date has no key. */
    @Query("UPDATE remote_objects SET dayKey = :day WHERE `key` = :key")
    suspend fun setDayKey(key: String, day: Int)

    @Query("UPDATE remote_objects SET uploadDayKey = :day WHERE `key` = :key")
    suspend fun setUploadDayKey(key: String, day: Int)

    @Query("SELECT `key`, effectiveDateMs, lastModifiedMs, dateSource FROM remote_objects")
    suspend fun datesForZoneChange(): List<RemoteDateRow>

    @Query("SELECT * FROM remote_objects WHERE key IN (:keys)")
    suspend fun getByKeys(keys: List<String>): List<RemoteObjectEntity>

    /** Which of these versions (content hashes) have an object in the cached listing. */
    @Query("SELECT DISTINCT versionId FROM remote_objects WHERE versionId IN (:versionIds)")
    suspend fun versionsListed(versionIds: List<String>): List<String>

    @Query("SELECT * FROM remote_objects WHERE versionId = :versionId AND sizeBytes = :size LIMIT 1")
    suspend fun findByContent(versionId: String, size: Long): RemoteObjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RemoteObjectEntity>)

    @Query("DELETE FROM remote_objects WHERE key IN (:keys)")
    suspend fun forget(keys: List<String>)

    @Query("UPDATE remote_objects SET state = :to, lastError = :error WHERE key = :key AND state = :from")
    suspend fun transition(key: String, from: SyncState, to: SyncState, error: String?): Int

    /** [transition] for many objects in one statement. */
    @Query("UPDATE remote_objects SET state = :to, lastError = :error WHERE key IN (:keys) AND state = :from")
    suspend fun transitionAll(keys: List<String>, from: SyncState, to: SyncState, error: String?): Int

    /**
     * How many of these objects also exist on this device: a present local file linked to the key, or a restored copy.
     * Answers the selection bar with one COUNT instead of reading every selected row.
     */
    @Query(
        "SELECT COUNT(*) FROM remote_objects r WHERE r.`key` IN (:keys) AND (r.restoredUri IS NOT NULL OR " +
            "EXISTS (SELECT 1 FROM media_items m WHERE m.remoteKey = r.`key` AND m.present = 1))",
    )
    suspend fun countAvailableLocally(keys: List<String>): Int

    @Query("UPDATE remote_objects SET restoredUri = :uri, state = 'REMOTE_ONLY', lastError = NULL WHERE key = :key")
    suspend fun markRestored(key: String, uri: String)

    @Query("UPDATE remote_objects SET state = 'REMOTE_ONLY' WHERE state = 'RESTORING'")
    suspend fun recoverInterrupted(): Int

    @Query("DELETE FROM remote_objects")
    suspend fun clear()

    // ---- Sync center (S3 side of the picture, from the cached listing).

    @Query(
        "SELECT COALESCE(SUM(CASE WHEN state = 'RESTORING' THEN 1 ELSE 0 END), 0) AS restoring, " +
            "COALESCE(SUM(CASE WHEN state = 'REMOTE_ONLY' AND lastError IS NOT NULL THEN 1 ELSE 0 END), 0) AS restoreFailed, " +
            "COALESCE(SUM(CASE WHEN state = 'REMOTE_ONLY' AND restoredUri IS NULL AND NOT EXISTS " +
            "(SELECT 1 FROM media_items m WHERE m.remoteKey = remote_objects.`key` AND m.present = 1) THEN 1 ELSE 0 END), 0) AS cloudOnly " +
            "FROM remote_objects",
    )
    fun observeSyncCounts(): Flow<RemoteSyncCounts>

    @Query("SELECT * FROM remote_objects WHERE state = 'RESTORING' ORDER BY lastModifiedMs DESC, `key` LIMIT :limit")
    fun observeRestoring(limit: Int): Flow<List<RemoteObjectEntity>>

    /** Objects that exist in S3 and have no copy on this device (failed restores first: they need a decision). */
    @Query(
        "SELECT * FROM remote_objects WHERE state = 'REMOTE_ONLY' AND restoredUri IS NULL AND NOT EXISTS " +
            "(SELECT 1 FROM media_items m WHERE m.remoteKey = remote_objects.`key` AND m.present = 1) " +
            "ORDER BY (lastError IS NULL) ASC, effectiveDateMs DESC, `key` LIMIT :limit",
    )
    fun observeCloudOnly(limit: Int): Flow<List<RemoteObjectEntity>>
}

/** S3 objects by what the sync center says about them. */
data class RemoteSyncCounts(val restoring: Int, val restoreFailed: Int, val cloudOnly: Int)

/** How many transfer events of one kind the log holds. */
data class EventCount(val type: com.s3vault.app.domain.TransferEventType, val n: Int)

@Dao
interface JobDao {
    @Query("SELECT * FROM background_jobs WHERE name = :name")
    fun observe(name: String): Flow<JobEntity?>

    @Query("SELECT * FROM background_jobs")
    fun observeAll(): Flow<List<JobEntity>>

    @Query("SELECT * FROM background_jobs WHERE name = :name")
    suspend fun get(name: String): JobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: JobEntity)

    /**
     * A job still RUNNING that was last touched before this process started belongs to a process that died: it is shown as
     * interrupted instead of running forever. Work started by this process is newer than [processStartMs] and is left alone.
     */
    @Query("UPDATE background_jobs SET state = 'INTERRUPTED' WHERE state = 'RUNNING' AND updatedAtMs < :processStartMs")
    suspend fun markInterrupted(processStartMs: Long): Int
}

/**
 * S3-only members of an album that count and show: the photo is in the cached listing, and the album has no copy of it on
 * this device (that copy is the member then, so the photo is never listed twice).
 */
private const val VISIBLE_CLOUD_MEMBERS =
    "FROM album_remote_items ri WHERE ri.albumId = a.id " +
        "AND EXISTS (SELECT 1 FROM remote_objects r WHERE r.versionId = ri.versionId) " +
        "AND NOT EXISTS (SELECT 1 FROM album_items li JOIN media_items lm ON lm.mediaStoreId = li.mediaStoreId " +
        "WHERE li.albumId = ri.albumId AND lm.present = 1 AND (lm.contentSha256 = ri.versionId OR lm.remoteVersionId = ri.versionId))"

private const val ALBUM_ITEM_COUNT =
    "((SELECT COUNT(*) FROM album_items ai JOIN media_items m ON m.mediaStoreId = ai.mediaStoreId " +
        "WHERE ai.albumId = a.id AND m.present = 1) + (SELECT COUNT(*) " + VISIBLE_CLOUD_MEMBERS + "))"

@Dao
interface AlbumDao {
    @Query(
        "SELECT a.id AS id, a.name AS name, " + ALBUM_ITEM_COUNT + " AS itemCount, " +
            "(SELECT m2.mediaStoreId FROM album_items ai2 JOIN media_items m2 ON m2.mediaStoreId = ai2.mediaStoreId " +
            " WHERE ai2.albumId = a.id AND m2.present = 1 ORDER BY ai2.addedAtMs DESC LIMIT 1) AS coverMediaStoreId, " +
            "(SELECT m3.uri FROM album_items ai3 JOIN media_items m3 ON m3.mediaStoreId = ai3.mediaStoreId " +
            " WHERE ai3.albumId = a.id AND m3.present = 1 ORDER BY ai3.addedAtMs DESC LIMIT 1) AS coverUri, " +
            "(SELECT MIN(r3.`key`) FROM remote_objects r3 WHERE r3.versionId = " +
            " (SELECT ri3.versionId FROM album_remote_items ri3 WHERE ri3.albumId = a.id " +
            "  AND EXISTS (SELECT 1 FROM remote_objects r4 WHERE r4.versionId = ri3.versionId) " +
            "  ORDER BY ri3.addedAtMs DESC, ri3.versionId LIMIT 1)) AS coverRemoteKey " +
            "FROM albums a ORDER BY a.name COLLATE NOCASE",
    )
    fun observeAlbums(): Flow<List<AlbumRow>>

    @Query("SELECT * FROM albums WHERE id = :id")
    fun observeAlbum(id: Long): Flow<AlbumEntity?>

    @Query(
        "SELECT m.* FROM media_items m JOIN album_items ai ON ai.mediaStoreId = m.mediaStoreId " +
            "WHERE ai.albumId = :albumId AND m.present = 1 ORDER BY m.dateTakenMs DESC",
    )
    fun observeAlbumMedia(albumId: Long): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM albums")
    suspend fun all(): List<AlbumEntity>

    @Query("SELECT mediaStoreId FROM album_items WHERE albumId = :albumId")
    suspend fun memberIds(albumId: Long): List<Long>

    @Insert
    suspend fun insert(album: AlbumEntity): Long

    @Query("UPDATE albums SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /** Deletes the album definition only; media files are untouched. */
    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItems(items: List<AlbumItemEntity>)

    @Query("DELETE FROM album_items WHERE albumId = :albumId AND mediaStoreId IN (:ids)")
    suspend fun removeItems(albumId: Long, ids: List<Long>)

    @Query("DELETE FROM album_items WHERE mediaStoreId IN (:ids)")
    suspend fun removeMediaEverywhere(ids: List<Long>)

    // ---- Photos that exist only in S3.

    /** One object per version, newest first, for the S3-only part of an album (same rule as [VISIBLE_CLOUD_MEMBERS]). */
    @Query(
        "SELECT r.* FROM remote_objects r JOIN album_remote_items ri ON ri.versionId = r.versionId " +
            "WHERE ri.albumId = :albumId " +
            "AND r.`key` = (SELECT MIN(r2.`key`) FROM remote_objects r2 WHERE r2.versionId = r.versionId) " +
            "AND NOT EXISTS (SELECT 1 FROM album_items li JOIN media_items lm ON lm.mediaStoreId = li.mediaStoreId " +
            "WHERE li.albumId = ri.albumId AND lm.present = 1 AND (lm.contentSha256 = ri.versionId OR lm.remoteVersionId = ri.versionId)) " +
            "ORDER BY r.effectiveDateMs DESC, r.`key`",
    )
    fun observeAlbumCloud(albumId: Long): Flow<List<RemoteObjectEntity>>

    @Query("SELECT versionId FROM album_remote_items WHERE albumId = :albumId")
    suspend fun cloudMemberVersions(albumId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRemoteItems(items: List<AlbumRemoteItemEntity>)

    @Query("DELETE FROM album_remote_items WHERE albumId = :albumId AND versionId IN (:versionIds)")
    suspend fun removeRemoteItems(albumId: Long, versionIds: List<String>)

    /** The S3 photo was deleted on purpose: no album keeps a pointer to it. */
    @Query("DELETE FROM album_remote_items WHERE versionId IN (:versionIds)")
    suspend fun removeRemoteItemsEverywhere(versionIds: List<String>)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM transfer_events ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransferEventEntity>>

    @Insert
    suspend fun insert(e: TransferEventEntity)

    @Query("SELECT COUNT(*) FROM transfer_events WHERE type = 'UPLOADED' AND timestampMs > :sinceMs")
    fun observeUploadsSince(sinceMs: Long): Flow<Int>

    /** The most recent failure the user may need to act on (an upload or restore that failed, or credentials that ran out). */
    @Query("SELECT * FROM transfer_events WHERE type IN ('UPLOAD_FAILED', 'RESTORE_FAILED', 'CREDENTIALS_NEEDED') ORDER BY timestampMs DESC, id DESC LIMIT 1")
    fun observeLastFailure(): Flow<TransferEventEntity?>

    /** Outcomes since a moment, by kind. The log keeps the latest 200 entries, so on a very busy day this is a lower bound. */
    @Query("SELECT type, COUNT(*) AS n FROM transfer_events WHERE timestampMs > :sinceMs GROUP BY type")
    fun observeCountsSince(sinceMs: Long): Flow<List<EventCount>>

    /** Objects deleted from S3 since [sinceMs]; each event is named "N elementos", and SQLite reads the leading number. */
    @Query("SELECT COALESCE(SUM(CAST(name AS INTEGER)), 0) FROM transfer_events WHERE type = 'CLOUD_DELETED' AND timestampMs > :sinceMs")
    fun observeCloudDeletionsSince(sinceMs: Long): Flow<Int>

    @Query("DELETE FROM transfer_events WHERE id NOT IN (SELECT id FROM transfer_events ORDER BY timestampMs DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Transaction
    suspend fun log(e: TransferEventEntity, keep: Int = 200) {
        insert(e)
        trim(keep)
    }

    @Query("DELETE FROM transfer_events")
    suspend fun clear()
}

/** A stored verdict with enough about the file to show it in a list. */
data class IntegrityRow(
    @androidx.room.Embedded val result: IntegrityResultEntity,
    val name: String?,
    val mediaStoreId: Long?,
    val present: Boolean?,
)

data class VerdictCount(val verdict: IntegrityVerdict, val n: Int)

@Dao
interface IntegrityDao {
    @Insert
    suspend fun insertAudit(a: IntegrityAuditEntity): Long

    @Update
    suspend fun updateAudit(a: IntegrityAuditEntity)

    @Query("SELECT * FROM integrity_audits WHERE id = :id")
    suspend fun audit(id: Long): IntegrityAuditEntity?

    @Query("SELECT * FROM integrity_audits ORDER BY id DESC LIMIT 1")
    fun observeLatestAudit(): Flow<IntegrityAuditEntity?>

    @Query("SELECT * FROM integrity_audits ORDER BY id DESC LIMIT 1")
    suspend fun latestAudit(): IntegrityAuditEntity?

    /** A run still marked RUNNING by a process that died. */
    @Query("UPDATE integrity_audits SET status = 'INTERRUPTED' WHERE status = 'RUNNING' AND startedAtMs < :processStartMs")
    suspend fun markInterrupted(processStartMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResults(rows: List<IntegrityResultEntity>)

    @Query("SELECT * FROM integrity_results")
    suspend fun allResults(): List<IntegrityResultEntity>

    @Query("SELECT * FROM integrity_results WHERE remoteKey IN (:keys)")
    suspend fun resultsFor(keys: List<String>): List<IntegrityResultEntity>

    @Query("SELECT verdict, COUNT(*) AS n FROM integrity_results GROUP BY verdict")
    fun observeCounts(): Flow<List<VerdictCount>>

    /** Objects with a backup record but no verdict yet. */
    @Query(
        "SELECT COUNT(DISTINCT remoteKey) FROM media_items WHERE remoteKey IS NOT NULL AND syncState IN ('BACKED_UP', 'REMOTE_ONLY') " +
            "AND remoteKey NOT IN (SELECT remoteKey FROM integrity_results)",
    )
    fun observeUnchecked(): Flow<Int>

    @Query(
        "SELECT r.*, " +
            "(SELECT m.displayName FROM media_items m WHERE m.remoteKey = r.remoteKey ORDER BY m.present DESC LIMIT 1) AS name, " +
            "(SELECT m.mediaStoreId FROM media_items m WHERE m.remoteKey = r.remoteKey ORDER BY m.present DESC LIMIT 1) AS mediaStoreId, " +
            "(SELECT MAX(m.present) FROM media_items m WHERE m.remoteKey = r.remoteKey) AS present " +
            "FROM integrity_results r WHERE r.verdict IN (:verdicts) ORDER BY r.checkedAtMs DESC, r.remoteKey LIMIT :limit",
    )
    fun observeRows(verdicts: List<IntegrityVerdict>, limit: Int): Flow<List<IntegrityRow>>

    /** Verdicts of objects no backup record points to any more mean nothing. */
    @Query("DELETE FROM integrity_results WHERE remoteKey NOT IN (SELECT remoteKey FROM media_items WHERE remoteKey IS NOT NULL)")
    suspend fun dropOrphans(): Int

    @Query("DELETE FROM integrity_results WHERE remoteKey IN (:keys)")
    suspend fun forget(keys: List<String>)
}

@Dao
interface DuplicateDao {
    // ---- What still needs work. Both lists skip anything already known and unchanged, so a rescan costs nothing.

    /**
     * Files that share their size with another file and have no valid content hash. Only equal sizes can be equal files, so every
     * other file is never read.
     */
    @Query(
        "SELECT mediaStoreId, uri, sizeBytes, dateModifiedSec FROM media_items WHERE present = 1 AND sizeBytes > 0 " +
            "AND (contentSha256 IS NULL OR hashSizeBytes != sizeBytes OR hashDateModifiedSec != dateModifiedSec) " +
            "AND sizeBytes IN (SELECT sizeBytes FROM media_items WHERE present = 1 AND sizeBytes > 0 GROUP BY sizeBytes HAVING COUNT(*) > 1) " +
            "ORDER BY dateTakenMs DESC, mediaStoreId LIMIT :limit",
    )
    suspend fun needHash(limit: Int): List<ScanCandidate>

    @Query(
        "SELECT COUNT(*) FROM media_items WHERE present = 1 AND sizeBytes > 0 " +
            "AND (contentSha256 IS NULL OR hashSizeBytes != sizeBytes OR hashDateModifiedSec != dateModifiedSec) " +
            "AND sizeBytes IN (SELECT sizeBytes FROM media_items WHERE present = 1 AND sizeBytes > 0 GROUP BY sizeBytes HAVING COUNT(*) > 1)",
    )
    suspend fun needHashCount(): Int

    /** Pictures with no fingerprint (or an outdated one), newest first so recent photos are analysed first. */
    @Query(
        "SELECT m.mediaStoreId, m.uri, m.sizeBytes, m.dateModifiedSec FROM media_items m LEFT JOIN media_analysis a ON a.mediaStoreId = m.mediaStoreId " +
            "WHERE m.present = 1 AND m.kind = 'IMAGE' AND (a.mediaStoreId IS NULL OR a.sizeBytes != m.sizeBytes OR a.dateModifiedSec != m.dateModifiedSec) " +
            "ORDER BY m.dateTakenMs DESC, m.mediaStoreId LIMIT :limit",
    )
    suspend fun needAnalysis(limit: Int): List<ScanCandidate>

    @Query(
        "SELECT COUNT(*) FROM media_items m LEFT JOIN media_analysis a ON a.mediaStoreId = m.mediaStoreId " +
            "WHERE m.present = 1 AND m.kind = 'IMAGE' AND (a.mediaStoreId IS NULL OR a.sizeBytes != m.sizeBytes OR a.dateModifiedSec != m.dateModifiedSec)",
    )
    fun observeNeedAnalysis(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM media_items m LEFT JOIN media_analysis a ON a.mediaStoreId = m.mediaStoreId " +
            "WHERE m.present = 1 AND m.kind = 'IMAGE' AND (a.mediaStoreId IS NULL OR a.sizeBytes != m.sizeBytes OR a.dateModifiedSec != m.dateModifiedSec)",
    )
    suspend fun needAnalysisCount(): Int

    @Query(
        "SELECT COUNT(*) FROM media_items WHERE present = 1 AND sizeBytes > 0 " +
            "AND (contentSha256 IS NULL OR hashSizeBytes != sizeBytes OR hashDateModifiedSec != dateModifiedSec) " +
            "AND sizeBytes IN (SELECT sizeBytes FROM media_items WHERE present = 1 AND sizeBytes > 0 GROUP BY sizeBytes HAVING COUNT(*) > 1)",
    )
    fun observeNeedHash(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnalysis(rows: List<MediaAnalysisEntity>)

    /** Fingerprints of present pictures whose analysis is still valid; the input of the similarity pass. */
    @Query(
        "SELECT a.mediaStoreId AS id, a.phash AS hash, m.width AS width, m.height AS height, m.dateTakenMs AS takenMs, m.contentSha256 AS sha " +
            "FROM media_analysis a JOIN media_items m ON m.mediaStoreId = a.mediaStoreId " +
            "WHERE m.present = 1 AND m.kind = 'IMAGE' AND a.status = 'OK' AND a.phash IS NOT NULL AND a.sizeBytes = m.sizeBytes AND a.dateModifiedSec = m.dateModifiedSec",
    )
    suspend fun fingerprints(): List<FingerprintRow>

    /** Identical-content sets: valid hashes shared by two or more present files. */
    @Query(
        "SELECT contentSha256 AS sha, mediaStoreId AS id FROM media_items WHERE present = 1 AND contentSha256 IS NOT NULL " +
            "AND hashSizeBytes = sizeBytes AND hashDateModifiedSec = dateModifiedSec AND contentSha256 IN (" +
            "SELECT contentSha256 FROM media_items WHERE present = 1 AND contentSha256 IS NOT NULL AND hashSizeBytes = sizeBytes " +
            "AND hashDateModifiedSec = dateModifiedSec GROUP BY contentSha256 HAVING COUNT(*) > 1) ORDER BY contentSha256, mediaStoreId",
    )
    suspend fun exactMembers(): List<ExactMemberRow>

    // ---- Groups

    @Query("DELETE FROM duplicate_groups")
    suspend fun clearGroups()

    @Insert
    suspend fun insertGroup(g: DuplicateGroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(rows: List<DuplicateMemberEntity>)

    @Query(
        "SELECT g.id AS id, g.kind AS kind, g.signature AS signature, COUNT(m.mediaStoreId) AS n, COALESCE(SUM(m.sizeBytes), 0) AS bytes, COALESCE(MAX(m.sizeBytes), 0) AS biggest " +
            "FROM duplicate_groups g JOIN duplicate_members dm ON dm.groupId = g.id JOIN media_items m ON m.mediaStoreId = dm.mediaStoreId AND m.present = 1 " +
            "WHERE g.kind = :kind AND g.signature NOT IN (SELECT signature FROM duplicate_ignored) " +
            "GROUP BY g.id HAVING COUNT(m.mediaStoreId) >= 2 ORDER BY (COALESCE(SUM(m.sizeBytes), 0) - COALESCE(MAX(m.sizeBytes), 0)) DESC, g.id LIMIT :limit",
    )
    fun observeGroups(kind: DuplicateKind, limit: Int): Flow<List<GroupSummaryRow>>

    @Query(
        "SELECT dm.groupId AS groupId, dm.distance AS distance, m.* FROM duplicate_members dm JOIN media_items m ON m.mediaStoreId = dm.mediaStoreId " +
            "WHERE dm.groupId IN (:groupIds) AND m.present = 1 ORDER BY dm.groupId, m.width * m.height DESC, m.sizeBytes DESC, m.mediaStoreId",
    )
    fun observeMembers(groupIds: List<Long>): Flow<List<GroupMemberRow>>

    @Query(
        "SELECT COUNT(*) AS groupCount, COALESCE(SUM(n), 0) AS items, COALESCE(SUM(bytes - biggest), 0) AS recoverableBytes FROM (" +
            "SELECT COUNT(m.mediaStoreId) AS n, SUM(m.sizeBytes) AS bytes, MAX(m.sizeBytes) AS biggest FROM duplicate_groups g " +
            "JOIN duplicate_members dm ON dm.groupId = g.id JOIN media_items m ON m.mediaStoreId = dm.mediaStoreId AND m.present = 1 " +
            "WHERE g.kind = :kind AND g.signature NOT IN (SELECT signature FROM duplicate_ignored) GROUP BY g.id HAVING COUNT(m.mediaStoreId) >= 2)",
    )
    fun observeTotals(kind: DuplicateKind): Flow<GroupTotals>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun ignore(row: DuplicateIgnoredEntity)

    @Query("DELETE FROM duplicate_ignored")
    suspend fun clearIgnored()

    @Query("SELECT COUNT(*) FROM duplicate_ignored")
    fun observeIgnoredCount(): Flow<Int>

    @Query("DELETE FROM media_analysis WHERE mediaStoreId NOT IN (SELECT mediaStoreId FROM media_items)")
    suspend fun dropOrphanAnalysis(): Int
}

data class FingerprintRow(val id: Long, val hash: Long, val width: Int, val height: Int, val takenMs: Long, val sha: String?)

data class ExactMemberRow(val sha: String, val id: Long)

@Dao
interface SmartAlbumDao {
    @Query("SELECT * FROM smart_albums ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<SmartAlbumEntity>>

    @Query("SELECT * FROM smart_albums WHERE id = :id")
    suspend fun get(id: Long): SmartAlbumEntity?

    @Insert
    suspend fun insert(a: SmartAlbumEntity): Long

    @Query("UPDATE smart_albums SET name = :name, filterJson = :json WHERE id = :id")
    suspend fun update(id: Long, name: String, json: String)

    /** Deletes the definition only; photos are not part of it. */
    @Query("DELETE FROM smart_albums WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT dayKey / 10000 AS year, COUNT(*) AS n FROM media_items WHERE present = 1 AND dayKey > 0 GROUP BY year ORDER BY year DESC")
    fun observeYears(): Flow<List<YearCount>>
}

data class EmbeddingRow(val mediaStoreId: Long, val scale: Float, val vector: ByteArray)

@Dao
interface SemanticDao {
    /** Pictures with no valid vector for [modelId] (never embedded, or changed since), newest first. */
    @Query(
        "SELECT m.mediaStoreId, m.uri, m.sizeBytes, m.dateModifiedSec FROM media_items m LEFT JOIN semantic_embeddings e ON e.mediaStoreId = m.mediaStoreId " +
            "AND e.modelId = :modelId AND e.sizeBytes = m.sizeBytes AND e.dateModifiedSec = m.dateModifiedSec " +
            "WHERE m.present = 1 AND m.kind = 'IMAGE' AND e.mediaStoreId IS NULL ORDER BY m.dateTakenMs DESC, m.mediaStoreId LIMIT :limit",
    )
    suspend fun needIndex(modelId: String, limit: Int): List<ScanCandidate>

    @Query(
        "SELECT COUNT(*) FROM media_items m LEFT JOIN semantic_embeddings e ON e.mediaStoreId = m.mediaStoreId " +
            "AND e.modelId = :modelId AND e.sizeBytes = m.sizeBytes AND e.dateModifiedSec = m.dateModifiedSec " +
            "WHERE m.present = 1 AND m.kind = 'IMAGE' AND e.mediaStoreId IS NULL",
    )
    fun observeNeedIndex(modelId: String): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM media_items m LEFT JOIN semantic_embeddings e ON e.mediaStoreId = m.mediaStoreId " +
            "AND e.modelId = :modelId AND e.sizeBytes = m.sizeBytes AND e.dateModifiedSec = m.dateModifiedSec " +
            "WHERE m.present = 1 AND m.kind = 'IMAGE' AND e.mediaStoreId IS NULL",
    )
    suspend fun needIndexCount(modelId: String): Int

    /** Searchable pictures (decoded, present, unchanged) for [modelId]. */
    @Query(
        "SELECT COUNT(*) FROM semantic_embeddings e JOIN media_items m ON m.mediaStoreId = e.mediaStoreId " +
            "WHERE e.modelId = :modelId AND e.ok = 1 AND m.present = 1 AND e.sizeBytes = m.sizeBytes AND e.dateModifiedSec = m.dateModifiedSec",
    )
    fun observeSearchable(modelId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE present = 1 AND kind = 'IMAGE'")
    fun observeImages(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<SemanticEmbeddingEntity>)

    /** One page of vectors, by id, so a search streams the library instead of holding it. */
    @Query(
        "SELECT e.mediaStoreId AS mediaStoreId, e.scale AS scale, e.vector AS vector FROM semantic_embeddings e JOIN media_items m ON m.mediaStoreId = e.mediaStoreId " +
            "WHERE e.modelId = :modelId AND e.ok = 1 AND m.present = 1 AND e.sizeBytes = m.sizeBytes AND e.dateModifiedSec = m.dateModifiedSec " +
            "AND e.mediaStoreId > :after ORDER BY e.mediaStoreId LIMIT :limit",
    )
    suspend fun page(modelId: String, after: Long, limit: Int): List<EmbeddingRow>

    @Query("SELECT COALESCE(SUM(LENGTH(vector)), 0) + COUNT(*) * 32 FROM semantic_embeddings")
    fun observeBytes(): Flow<Long>

    @Query("DELETE FROM semantic_embeddings")
    suspend fun clear()

    @Query("DELETE FROM semantic_embeddings WHERE modelId != :modelId")
    suspend fun dropOtherModels(modelId: String)

    @Query("DELETE FROM semantic_embeddings WHERE mediaStoreId NOT IN (SELECT mediaStoreId FROM media_items)")
    suspend fun dropOrphans(): Int
}

@Dao
interface PrivateDao {
    @Query("SELECT * FROM private_vault WHERE id = 1")
    suspend fun vault(): PrivateVaultEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVault(v: PrivateVaultEntity)

    @Query("DELETE FROM private_vault")
    suspend fun deleteVault()

    @Query("SELECT * FROM private_albums ORDER BY createdAtMs, id")
    fun observeAlbums(): Flow<List<PrivateAlbumEntity>>

    @Query("SELECT * FROM private_albums ORDER BY createdAtMs, id")
    suspend fun albums(): List<PrivateAlbumEntity>

    @Query("SELECT * FROM private_albums WHERE id = :id")
    suspend fun album(id: Long): PrivateAlbumEntity?

    @Insert
    suspend fun insertAlbum(a: PrivateAlbumEntity): Long

    @Query("UPDATE private_albums SET nameSealed = :name WHERE id = :id")
    suspend fun renameAlbum(id: Long, name: ByteArray)

    @Query("DELETE FROM private_albums WHERE id = :id")
    suspend fun deleteAlbum(id: Long)

    @Query("SELECT albumId, COUNT(*) AS items, COALESCE(SUM(sealedBytes), 0) AS bytes FROM private_items GROUP BY albumId")
    fun observeCounts(): Flow<List<PrivateAlbumCount>>

    @Query("SELECT * FROM private_items WHERE albumId = :albumId ORDER BY addedAtMs, id")
    fun observeItems(albumId: Long): Flow<List<PrivateItemEntity>>

    @Query("SELECT * FROM private_items WHERE albumId = :albumId ORDER BY addedAtMs, id")
    suspend fun items(albumId: Long): List<PrivateItemEntity>

    @Query("SELECT * FROM private_items WHERE id = :id")
    suspend fun item(id: Long): PrivateItemEntity?

    @Query("SELECT * FROM private_items WHERE id IN (:ids)")
    suspend fun itemsById(ids: List<Long>): List<PrivateItemEntity>

    @Insert
    suspend fun insertItem(i: PrivateItemEntity): Long

    @Query("UPDATE private_items SET albumId = :albumId WHERE id IN (:ids)")
    suspend fun moveItems(ids: List<Long>, albumId: Long)

    @Query("DELETE FROM private_items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<Long>)

    @Query("SELECT fileName FROM private_items")
    suspend fun allFileNames(): List<String>

    @Query("SELECT COUNT(*) FROM private_items")
    suspend fun itemCount(): Int
}
