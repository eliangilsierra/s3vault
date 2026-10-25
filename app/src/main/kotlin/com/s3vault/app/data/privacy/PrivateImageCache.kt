package com.s3vault.app.data.privacy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import com.s3vault.app.domain.privacy.PrivateItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decrypted pictures kept only in memory, for the grid and the viewer. They are never cached on disk (no Coil disk cache, no
 * files in the cache directory) and the whole cache is emptied the moment the vault locks. A decode that was running when the
 * vault locked is dropped instead of being stored.
 */
class PrivateImageCache(private val store: PrivateVaultStore) {
    private val generation = AtomicInteger(0)

    private val thumbs = object : LruCache<Long, Bitmap>(THUMB_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val fulls = object : LruCache<Long, Bitmap>(FULL_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    fun clear() {
        generation.incrementAndGet()
        thumbs.evictAll(); fulls.evictAll()
    }

    fun cachedThumbnail(id: Long): Bitmap? = thumbs.get(id)

    /** The grid preview, decoded at about [targetPx] on its longest edge. Falls back to the full picture when there is no preview. */
    suspend fun thumbnail(item: PrivateItem, targetPx: Int): Bitmap? {
        thumbs.get(item.id)?.let { return it }
        val gen = generation.get()
        val bmp = withContext(Dispatchers.IO) {
            val bytes = store.thumbnailBytes(item)
            if (bytes != null) decode(bytes, targetPx) else runCatching { decode(store.imageBytes(item), targetPx) }.getOrNull()
        } ?: return null
        if (gen == generation.get()) thumbs.put(item.id, bmp)
        return bmp
    }

    /** The whole picture for the viewer, at most [maxEdgePx] on its longest edge, upright. Null when it cannot be decrypted or decoded. */
    suspend fun full(item: PrivateItem, maxEdgePx: Int): Bitmap? {
        fulls.get(item.id)?.let { return it }
        val gen = generation.get()
        val bmp = withContext(Dispatchers.IO) {
            val bytes = try { store.imageBytes(item) } catch (e: Exception) { return@withContext null }
            try { decode(bytes, maxEdgePx) } catch (e: OutOfMemoryError) { null }
        } ?: return null
        if (gen == generation.get()) fulls.put(item.id, bmp)
        return bmp
    }

    private fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return raw
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    private companion object {
        const val THUMB_BYTES = 24 * 1024 * 1024
        const val FULL_BYTES = 40 * 1024 * 1024
    }
}
