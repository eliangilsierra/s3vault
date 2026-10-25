package com.s3vault.app.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.s3vault.app.domain.duplicates.PerceptualHash
import com.s3vault.app.sync.ImageSignatureSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Decodes a picture to a 32x32 greyscale thumbnail with the platform decoder, which reads JPEG, PNG, WebP, HEIF and AVIF, applies the
 * EXIF orientation, and (with a target sample size) decodes at about 128 px instead of the full resolution: a 12 MP photo costs
 * a few hundred KB of memory, not 48 MB. Runs on the IO dispatcher; nothing leaves the device.
 */
class AndroidImageSignature(private val context: Context) : ImageSignatureSource {
    override suspend fun luma(uri: String): FloatArray? = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                decoder.setTargetSampleSize((longest / DECODE_EDGE).coerceAtLeast(1))
            }
            val small = Bitmap.createScaledBitmap(decoded, PerceptualHash.SIZE, PerceptualHash.SIZE, true)
            val pixels = IntArray(PerceptualHash.SIZE * PerceptualHash.SIZE)
            small.getPixels(pixels, 0, PerceptualHash.SIZE, 0, 0, PerceptualHash.SIZE, PerceptualHash.SIZE)
            if (small !== decoded) small.recycle()
            decoded.recycle()
            FloatArray(pixels.size) { i ->
                val p = pixels[i]
                PerceptualHash.luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: RuntimeException) {
            null // a format the device cannot decode
        }
    }

    private companion object { const val DECODE_EDGE = 128 }
}
