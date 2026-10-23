package com.s3vault.app.sync

import com.s3vault.app.domain.EmbeddedThumbnailReader
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

enum class PreviewQuality {
    /** The ~720 px JPEG uploaded at backup time. Good for the grid and for looking at the photo on a phone. */
    PREVIEW,

    /** The ~160 px thumbnail embedded in the original JPEG's EXIF block, read with a Range request. Very small. */
    EMBEDDED,
}

sealed interface PreviewResult {
    data class Ready(val file: File, val quality: PreviewQuality) : PreviewResult

    /** Neither a preview object nor an embedded thumbnail exists; only downloading the original would show it. */
    data object Unavailable : PreviewResult
}

/**
 * Previews of S3 objects without downloading the originals.
 *
 * Lookup order, cheapest first:
 *  1. On-device cache (no request).
 *  2. `{prefix}.s3vault/thumbs/{versionId}.jpg`: one small GET (tens of KB), written at backup time.
 *  3. For JPEG originals without a preview object: a Range GET of the first [HEAD_BYTES] of the original, from which the
 *     EXIF thumbnail is extracted. Works only when the JPEG carries one (most camera JPEGs do; PNG, HEIC, screenshots
 *     and videos usually do not).
 *  4. Nothing: [PreviewResult.Unavailable]. The caller offers the explicit, size-labelled "Restaurar".
 *
 * Misses are remembered for [MISS_TTL_MS] so a scrolling list does not repeat failing requests. Transient problems
 * (network, credentials) are thrown, never cached.
 */
class RemotePreviewRepository(
    private val dir: File,
    private val remote: RemoteContextProvider,
    private val embedded: EmbeddedThumbnailReader,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxCacheBytes: Long = DEFAULT_CACHE_BYTES,
) {
    private val gate = Semaphore(4)
    private val locks = Array(16) { Mutex() }
    private var writes = 0

    suspend fun load(obj: RemoteObject): PreviewResult {
        val v = obj.versionId
        return locks[(v.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cached(v) ?: gate.withPermit { fetch(obj) }
        }
    }

    /** Drops the cached files of deleted objects, so no trace of a deleted photo stays in the cache. */
    fun evict(versionIds: Collection<String>) {
        versionIds.forEach { v -> listOf(preview(v), embeddedFile(v), miss(v)).forEach { it.delete() } }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun preview(v: String) = File(dir, "$v.jpg")
    private fun embeddedFile(v: String) = File(dir, "$v.exif.jpg")
    private fun miss(v: String) = File(dir, "$v.none")

    private fun fresh(f: File): Boolean = f.isFile && nowMs() - f.lastModified() < MISS_TTL_MS

    private suspend fun cached(v: String): PreviewResult? = withContext(Dispatchers.IO) {
        val p = preview(v)
        when {
            p.isFile && p.length() > 0 -> { p.setLastModified(nowMs()); PreviewResult.Ready(p, PreviewQuality.PREVIEW) }
            fresh(embeddedFile(v)) && embeddedFile(v).length() > 0 -> PreviewResult.Ready(embeddedFile(v), PreviewQuality.EMBEDDED)
            fresh(miss(v)) -> PreviewResult.Unavailable
            else -> null
        }
    }

    private suspend fun fetch(obj: RemoteObject): PreviewResult {
        val ctx = remote.get() ?: throw RemoteException(RemoteErrorKind.NOT_CONFIGURED)
        val v = obj.versionId

        // A missing key answers 403 (not 404) when the policy has no ListBucket on this folder, so 403 means "no preview" here.
        val thumb = try {
            ctx.storage.readBytes(ctx.keys.thumbnailKey(v))
        } catch (e: RemoteException) {
            if (e.kind == RemoteErrorKind.ACCESS_DENIED) null else throw e
        }
        if (thumb != null && thumb.isNotEmpty()) return store(preview(v), thumb, PreviewQuality.PREVIEW)

        // Past this point the original is the only source, so this is also where a stale listing shows up: an object that
        // was deleted from another device answers "not found" (ListBucket on photos/ makes S3 report 404, not 403).
        if (isJpeg(obj.displayName)) {
            val head = ctx.storage.readBytes(obj.key, HEAD_BYTES) ?: throw RemoteException(RemoteErrorKind.OBJECT_NOT_FOUND)
            val exif = runCatching { embedded.read(head) }.getOrNull()
            if (exif != null && exif.isNotEmpty()) return store(embeddedFile(v), exif, PreviewQuality.EMBEDDED)
        } else if (ctx.storage.head(obj.key) == null) {
            throw RemoteException(RemoteErrorKind.OBJECT_NOT_FOUND)
        }
        withContext(Dispatchers.IO) { dir.mkdirs(); miss(v).also { it.writeBytes(ByteArray(0)); it.setLastModified(nowMs()) } }
        return PreviewResult.Unavailable
    }

    private suspend fun store(target: File, bytes: ByteArray, quality: PreviewQuality): PreviewResult = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        target.setLastModified(nowMs()) // the injected clock drives both expiry and least-recently-used order
        if (++writes % TRIM_EVERY == 0) trim()
        PreviewResult.Ready(target, quality)
    }

    /** Least recently used files go first once the cache exceeds its budget. */
    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxCacheBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxCacheBytes * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    private fun isJpeg(name: String) = name.substringAfterLast('.', "").lowercase().let { it == "jpg" || it == "jpeg" }

    companion object {
        /** An EXIF block is at most 64 KiB and sits at the very start of a JPEG; 128 KiB leaves room for other APP segments. */
        const val HEAD_BYTES = 128L * 1024
        const val DEFAULT_CACHE_BYTES = 96L * 1024 * 1024
        const val MISS_TTL_MS = 24L * 60 * 60 * 1000
        private const val TRIM_EVERY = 20
    }
}
