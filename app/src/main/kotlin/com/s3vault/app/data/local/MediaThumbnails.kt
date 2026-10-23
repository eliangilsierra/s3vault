package com.s3vault.app.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Size
import com.s3vault.app.domain.EmbeddedThumbnailReader
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.PREVIEW_MAX_EDGE_PX
import com.s3vault.app.domain.ThumbnailMaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Builds the S3 preview with the platform's own thumbnail pipeline (`ContentResolver.loadThumbnail`, API 29+): it
 * handles JPEG/HEIC/PNG/WebP, EXIF orientation and video frames, and decodes at the requested size instead of loading
 * the original into memory. Output: JPEG, longest edge at most [PREVIEW_MAX_EDGE_PX], typically 40-90 KB.
 */
class MediaStoreThumbnailMaker(private val context: Context) : ThumbnailMaker {
    override suspend fun create(uri: String, kind: MediaKind): ByteArray? = withContext(Dispatchers.IO) {
        val bitmap = try {
            context.contentResolver.loadThumbnail(Uri.parse(uri), Size(PREVIEW_MAX_EDGE_PX, PREVIEW_MAX_EDGE_PX), null)
        } catch (e: IOException) {
            return@withContext null
        } catch (e: SecurityException) {
            return@withContext null
        } catch (e: RuntimeException) {
            return@withContext null
        }
        encode(downscale(bitmap))
    }

    private fun downscale(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= PREVIEW_MAX_EDGE_PX) return src
        val f = PREVIEW_MAX_EDGE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun encode(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()

    companion object {
        const val JPEG_QUALITY = 78
    }
}

/**
 * Reads the thumbnail inside a JPEG's EXIF block from a partial download. The embedded thumbnail is stored
 * unrotated, so the EXIF orientation is applied here and the result displays upright.
 */
class ExifThumbnailReader : EmbeddedThumbnailReader {
    override fun read(head: ByteArray): ByteArray? {
        val exif = try {
            ExifInterface(ByteArrayInputStream(head))
        } catch (e: IOException) {
            return null
        }
        if (!exif.hasThumbnail()) return null
        val bytes = exif.thumbnailBytes ?: return null
        val degrees = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (degrees == 0) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        return ByteArrayOutputStream().also { rotated.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}
