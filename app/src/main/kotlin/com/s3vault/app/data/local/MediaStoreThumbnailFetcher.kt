package com.s3vault.app.data.local

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Grid thumbnails from the platform's own thumbnail cache (`ContentResolver.loadThumbnail`) instead of decoding the original.
 *
 * MediaProvider keeps small, already decoded thumbnails of every photo and video, so a cell costs a few milliseconds even
 * when the original is a 12-megapixel JPEG, a HEIC or a video (where the alternative is pulling a frame out of the file).
 * On the emulator a 4000x3000 JPEG took a median of 74 ms to decode for a 350 px cell and 9 ms through the cache.
 *
 * Only used for small requests (grid cells). Anything larger (the full-screen viewer) and anything this fetcher cannot
 * serve falls through to Coil's normal path, so a failure here is never a missing image.
 */
class MediaStoreThumbnailFetcher(
    private val context: Context,
    private val uri: Uri,
    private val width: Int,
    private val height: Int,
) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val signal = CancellationSignal()
        val hook = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it is CancellationException) signal.cancel() }
        try {
            val bitmap = context.contentResolver.loadThumbnail(uri, Size(width, height), signal)
            ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // not served here: Coil asks the next fetcher (the regular decode of the original)
        } finally {
            hook?.dispose()
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !isMediaStoreItem(data)) return null
            val w = (options.size.width as? Dimension.Pixels)?.px ?: return null
            val h = (options.size.height as? Dimension.Pixels)?.px ?: return null
            if (w > MAX_EDGE_PX || h > MAX_EDGE_PX) return null
            return MediaStoreThumbnailFetcher(context, Uri.parse(data.toString()), w, h)
        }

        /** content://media/external/{images|video}/media/{id}: one photo or video, not a collection or a file of another app. */
        private fun isMediaStoreItem(data: coil3.Uri): Boolean {
            if (data.scheme != "content" || data.authority != "media") return false
            val segments = data.path.orEmpty().split('/').filter { it.isNotEmpty() }
            return segments.size == 4 && segments[0] == "external" && (segments[1] == "images" || segments[1] == "video") &&
                segments[2] == "media" && segments[3].toLongOrNull() != null
        }
    }

    private companion object {
        /** Cells are a few hundred pixels; anything bigger is a viewer and should be decoded from the original. */
        const val MAX_EDGE_PX = 640
    }
}
