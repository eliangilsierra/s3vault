package com.s3vault.app.data.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import androidx.core.net.toUri
import android.util.LruCache
import com.s3vault.app.data.local.FacePlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Cuts the face out of the photo each time one is drawn, instead of keeping a copy of it: no face crop is ever written to disk. A
 * few dozen small crops live in memory (about 6 MB at most) so scrolling a grid does not decode the same photo again, and are
 * dropped with the process. The photo is read at the size that makes the face about [SIZE] pixels, never at full size.
 */
class FaceCrops(private val context: Context) {
    private val cache = object : LruCache<Long, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val gate = Semaphore(2)

    /** The crop for [place], or null when the photo cannot be read now. Square, [SIZE] pixels. */
    suspend fun get(place: FacePlace): Bitmap? {
        cache.get(place.faceId)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                cache.get(place.faceId) ?: decode(place)?.also { cache.put(place.faceId, it) }
            }
        }
    }

    fun clear() = cache.evictAll()

    private fun decode(p: FacePlace): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, p.uri.toUri())) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val r = squareAround(p, w, h)
            val side = (r.right - r.left).coerceAtLeast(1)
            // Reduce the whole picture so the square around the face comes out at about SIZE pixels.
            val scale = (SIZE.toFloat() / side).coerceAtMost(1f)
            val tw = (w * scale).toInt().coerceAtLeast(1); val th = (h * scale).toInt().coerceAtLeast(1)
            decoder.setTargetSize(tw, th)
            decoder.setCrop(Rect((r.left * scale).toInt().coerceIn(0, tw - 1), (r.top * scale).toInt().coerceIn(0, th - 1),
                (r.right * scale).toInt().coerceIn(1, tw), (r.bottom * scale).toInt().coerceIn(1, th)))
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val SIZE = 192

        /** The square around the face with some room around it (a tight crop looks like a mugshot), in the picture's own pixels. */
        fun squareAround(p: FacePlace, w: Int, h: Int, margin: Float = 0.45f): Rect {
            val cx = (p.boxLeft + p.boxRight) / 2 * w; val cy = (p.boxTop + p.boxBottom) / 2 * h
            val side = (maxOf((p.boxRight - p.boxLeft) * w, (p.boxBottom - p.boxTop) * h) * (1 + margin)).coerceIn(8f, minOf(w, h).toFloat())
            val left = (cx - side / 2).coerceIn(0f, w - side); val top = (cy - side / 2).coerceIn(0f, h - side)
            return Rect(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt())
        }
    }
}
