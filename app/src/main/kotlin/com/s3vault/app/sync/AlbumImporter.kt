package com.s3vault.app.sync

import androidx.room.withTransaction
import com.s3vault.app.data.local.AlbumEntity
import com.s3vault.app.data.local.AlbumItemEntity
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.domain.AlbumManifest
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import java.io.ByteArrayOutputStream

/**
 * Creates local (virtual) albums from the membership file in S3.
 *
 * Albums only exist on this phone, so they cannot be "created in S3". The bucket instead carries a small
 * manifest (see [AlbumManifest]); this class reads it with a single GET and adds every local photo whose content
 * hash matches. Matching is by SHA-256, available once a photo was hashed for backup or linked to its remote copy.
 *
 * Safe to run repeatedly: albums are reused by name, memberships are only ever added, and nothing is deleted
 * locally or in S3.
 */
class AlbumImporter(
    private val db: AppDatabase,
    private val remote: RemoteContextProvider,
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
            val photosAdded: Int,
            /** Manifest entries with no matching photo on this device (yet). */
            val photosUnmatched: Int,
            /** Albums that were not created because none of their photos is on this device. */
            val albumsSkipped: Int,
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
        return apply(manifest)
    }

    suspend fun apply(manifest: AlbumManifest): Result.Imported = db.withTransaction {
        val local = HashMap<String, MutableList<Long>>()
        for (row in db.media().hashedItems()) {
            row.contentSha256?.let { local.getOrPut(it) { mutableListOf() } += row.mediaStoreId }
            row.remoteVersionId?.let { local.getOrPut(it) { mutableListOf() } += row.mediaStoreId }
        }
        val byName = db.albums().all().associateBy { it.name.lowercase() }.toMutableMap()

        var created = 0
        var updated = 0
        var added = 0
        var unmatched = 0
        var skipped = 0
        for (entry in manifest.albums) {
            val matched = entry.items.flatMap { local[it].orEmpty() }.distinct()
            unmatched += entry.items.count { local[it].isNullOrEmpty() }
            val existing = byName[entry.name.lowercase()]
            if (existing == null && matched.isEmpty()) { skipped++; continue }
            val albumId = existing?.id ?: db.albums().insert(AlbumEntity(name = entry.name, createdAtMs = nowMs())).also {
                byName[entry.name.lowercase()] = AlbumEntity(it, entry.name, nowMs())
                created++
            }
            val already = db.albums().memberIds(albumId).toSet()
            val fresh = matched.filter { it !in already }
            if (fresh.isNotEmpty()) {
                db.albums().addItems(fresh.map { AlbumItemEntity(albumId, it, nowMs()) })
                added += fresh.size
                if (existing != null) updated++
            }
        }
        Result.Imported(created, updated, added, unmatched, skipped)
    }
}
