package com.s3vault.app.data.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import com.s3vault.app.domain.faces.FaceModel
import com.s3vault.app.domain.faces.WorkingImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/** Why a picture could not be turned into a [WorkingImage]. */
sealed interface ImageLoad {
    class Ok(val image: WorkingImage) : ImageLoad

    /** The file is damaged, empty or in a format this device cannot decode: retrying will not help until the file changes. */
    data object Unreadable : ImageLoad

    /** The system would not let the app read it right now (permission narrowed, file busy): not the file's fault. */
    data object Unavailable : ImageLoad
}

/** Loads a picture for the face pipeline, at the working resolution and upright. */
fun interface FaceImageSource {
    suspend fun load(uri: String): ImageLoad
}

/**
 * Decodes with the platform decoder at (at most) [FaceModel.WORKING_SIDE] on the longest side, never the full-size original: a
 * 12-megapixel photo is read at about 1.2 megapixels, which is also what keeps memory flat. ImageDecoder applies the EXIF
 * orientation itself, so the face boxes are in the picture as the user sees it. The bitmap is read into an int array and recycled
 * at once, so nothing but one working image per call is alive.
 */
class AndroidFaceImageSource(private val context: Context) : FaceImageSource {
    override suspend fun load(uri: String): ImageLoad = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri.toUri())) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > FaceModel.WORKING_SIDE) {
                    val f = FaceModel.WORKING_SIDE.toFloat() / longest
                    decoder.setTargetSize(maxOf(1, Math.round(info.size.width * f)), maxOf(1, Math.round(info.size.height * f)))
                }
            }
            val w = bitmap.width; val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            ImageLoad.Ok(WorkingImage(pixels, w, h))
        } catch (e: SecurityException) {
            ImageLoad.Unavailable
        } catch (e: FileNotFoundException) {
            ImageLoad.Unavailable
        } catch (e: IOException) {
            ImageLoad.Unreadable
        } catch (e: RuntimeException) {
            ImageLoad.Unreadable
        } catch (e: OutOfMemoryError) {
            ImageLoad.Unavailable // a transient lack of memory says nothing about the file
        } finally {
            bitmap?.recycle()
        }
    }
}
