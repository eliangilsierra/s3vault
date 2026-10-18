package app.s3vault.data.local.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.s3vault.sync.ScannedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Lee fotos y vídeos de MediaStore. Solo consulta metadatos: nunca abre los originales. */
class MediaStoreScanner(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    suspend fun scan(): List<ScannedMedia> = withContext(Dispatchers.IO) {
        val out = ArrayList<ScannedMedia>()
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false, out)
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true, out)
        out
    }

    private suspend fun query(base: Uri, isVideo: Boolean, out: MutableList<ScannedMedia>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        val ctx = currentCoroutineContext()
        resolver.query(base, projection, null, null, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val w = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val h = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val bucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                ctx.ensureActive()
                val uri = ContentUris.withAppendedId(base, c.getLong(id)).toString()
                val takenMs = if (c.isNull(taken)) null else c.getLong(taken).takeIf { it > 0 }
                out += ScannedMedia(
                    uri = uri,
                    displayName = c.getString(name) ?: "sin_nombre",
                    mimeType = c.getString(mime) ?: if (isVideo) "video/*" else "image/*",
                    sizeBytes = c.getLong(size),
                    dateModifiedMs = c.getLong(mod) * 1000L,
                    dateTakenMs = takenMs,
                    width = if (c.isNull(w)) null else c.getInt(w).takeIf { it > 0 },
                    height = if (c.isNull(h)) null else c.getInt(h).takeIf { it > 0 },
                    isVideo = isVideo,
                    folder = c.getString(bucket),
                )
            }
        }
    }
}
