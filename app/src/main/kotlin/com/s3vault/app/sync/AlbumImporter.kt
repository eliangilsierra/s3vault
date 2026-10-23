package com.s3vault.app.sync

import androidx.room.withTransaction
import com.s3vault.app.data.local.AlbumEntity
import com.s3vault.app.data.local.AlbumItemEntity
import com.s3vault.app.data.local.AlbumRemoteItemEntity
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.domain.AlbumManifest
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import java.io.ByteArrayOutputStream

/**
 * Creates local (virtual) albums from the membership file in S3.
 *
 * Albums only exist on this phone, so they cannot be "created in S3". The bucket instead carries a small
 * manifest (see [AlbumManifest]); this class reads it with a single GET and places every photo it names. A photo is
 * identified by its SHA-256, which is also the `versionId` of its S3 key, so there are two places to find it:
 *
 *  1. on this device, once the file was hashed for a backup or linked to its remote copy: it joins the album as a normal
 *     local member;
 *  2. in S3 only (a migrated library, or a photo already deleted from the phone): it joins as an S3 member, found in the
 *     cached listing. If the manifest names photos the cache does not know, the listing is refreshed once (the same
 *     `ListObjectsV2` pass as the S3 tab) before giving up on them.
 *
 * Safe to run repeatedly: albums are reused by name, memberships are only ever added, and nothing is deleted
 * locally or in S3.
 */
class AlbumImporter(
    private val db: AppDatabase,
    private val remote: RemoteContextProvider,
    /** Lists the bucket into the cache. Called at most once per import, and only when the manifest names unknown photos. */
    private val refreshListing: suspend () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data object NotConfigured : Result

        /** Missing, or not readable with the app's permissions (S3 answers 403 for absent keys under a prefix policy). */
        data object NoManifest : Result
        data class Invalid(val reason: String) : Result
        data class Imported(
            val albumsCreated: Int,
            val albumsUpdated: Int,
            /** Photos of this device added to albums. */
            val photosAdded: Int,
            /** Photos that live only in S3 added to albums. */
            val cloudAdded: Int,
            /** Manifest entries found neither on this device nor under `photos/` in the bucket. */
            val photosUnmatched: Int,
            /** Albums that were not created because none of their photos was found. */
            val albumsSkipped: Int,
            /** Photos the manifest names, counting each once (for the diagnostic message). */
            val manifestPhotos: Int = 0,
            /** Whether the bucket was listed again to find the photos. */
            val listedBucket: Boolean = false,
        ) : Result
    }

    suspend fun importFromRemote(): Result {
        val ctx = remote.get() ?: return Result.NotConfigured
        val key = ctx.keys.albumManifestKey
        val meta = try {
            ctx.storage.head(key)
        } catch (e: RemoteException) {
            if (e.kind == RemoteErrorKind.ACCESS_DENIED) null else throw e
        } ?: return Result.NoManifest
        if (meta.sizeBytes > AlbumManifest.MAX_BYTES) return Result.Invalid("el archivo es demasiado grande")

        val out = ByteArrayOutputStream(meta.sizeBytes.toInt().coerceAtLeast(16))
        ctx.storage.download(key, out, 0, meta.etag) { }
        val manifest = try {
            AlbumManifest.parse(out.toString(Charsets.UTF_8.name()))
        } catch (e: AlbumManifest.Invalid) {
            return Result.Invalid(e.message ?: "formato incorrecto")
        }
        return apply(manifest, listBucketIfNeeded = true)
    }

    suspend fun apply(manifest: AlbumManifest, listBucketIfNeeded: Boolean = false): Result.Imported {
        val wanted = manifest.albums.flatMap { it.items }.toSet()
        val local = localIndex()
        val needCloud = wanted.filter { local[it].isNullOrEmpty() }
        var listed = listedAmong(needCloud)
        var listedBucket = false
        if (listBucketIfNeeded && listed.size < needCloud.size) {
            refreshListing()
            listedBucket = true
            listed = listedAmong(needCloud)
        }
        return db.withTransaction { place(manifest, local, listed, wanted.size, listedBucket) }
    }

    private suspend fun localIndex(): Map<String, List<Long>> {
        val index = HashMap<String, MutableList<Long>>()
        for (row in db.media().hashedItems()) {
            row.contentSha256?.let { index.getOrPut(it) { mutableListOf() } += row.mediaStoreId }
            row.remoteVersionId?.let { index.getOrPut(it) { mutableListOf() } += row.mediaStoreId }
        }
        return index
    }

    private suspend fun listedAmong(versions: List<String>): Set<String> =
        versions.chunked(500).flatMapTo(HashSet()) { db.remote().versionsListed(it) }

    private suspend fun place(
        manifest: AlbumManifest,
        local: Map<String, List<Long>>,
        listed: Set<String>,
        manifestPhotos: Int,
        listedBucket: Boolean,
    ): Result.Imported {
        val byName = db.albums().all().associateBy { it.name.lowercase() }.toMutableMap()

        var created = 0
        var updated = 0
        var added = 0
        var cloudAdded = 0
        var skipped = 0
        val unmatched = HashSet<String>()
        for (entry in manifest.albums) {
            val onDevice = entry.items.filter { !local[it].isNullOrEmpty() }
            val inCloud = entry.items.filter { local[it].isNullOrEmpty() && it in listed }
            unmatched += entry.items.filter { local[it].isNullOrEmpty() && it !in listed }
            val existing = byName[entry.name.lowercase()]
            if (existing == null && onDevice.isEmpty() && inCloud.isEmpty()) { skipped++; continue }
            val albumId = existing?.id ?: db.albums().insert(AlbumEntity(name = entry.name, createdAtMs = nowMs())).also {
                byName[entry.name.lowercase()] = AlbumEntity(it, entry.name, nowMs())
                created++
            }

            val already = db.albums().memberIds(albumId).toSet()
            val freshLocal = onDevice.flatMap { local.getValue(it) }.distinct().filter { it !in already }
            if (freshLocal.isNotEmpty()) db.albums().addItems(freshLocal.map { AlbumItemEntity(albumId, it, nowMs()) })
            // A copy that is now on this device replaces its S3-only membership (it would be listed twice otherwise).
            if (onDevice.isNotEmpty()) db.albums().removeRemoteItems(albumId, onDevice)

            val alreadyCloud = db.albums().cloudMemberVersions(albumId).toSet()
            val freshCloud = inCloud.distinct().filter { it !in alreadyCloud }
            if (freshCloud.isNotEmpty()) db.albums().addRemoteItems(freshCloud.map { AlbumRemoteItemEntity(albumId, it, nowMs()) })

            added += freshLocal.size
            cloudAdded += freshCloud.size
            if (existing != null && (freshLocal.isNotEmpty() || freshCloud.isNotEmpty())) updated++
        }
        return Result.Imported(created, updated, added, cloudAdded, unmatched.size, skipped, manifestPhotos, listedBucket)
    }
}
