package com.s3vault.app.data.local

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.s3vault.app.domain.LocalFileReader
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.DateResolver
import com.s3vault.app.domain.library.DateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.ZoneId

/** One row read from MediaStore. */
data class ScannedMedia(
    val mediaStoreId: Long,
    val uri: String,
    val kind: MediaKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val dateTakenMs: Long,
    val width: Int,
    val height: Int,
    val folderName: String,
    /** Where [dateTakenMs] comes from (see DateResolver). */
    val dateSource: DateSource = DateSource.CAPTURE,
    /** `yyyyMMdd` of [dateTakenMs] in the zone of the scan. */
    val dayKey: Int = DateKeys.dayKey(dateTakenMs, ZoneId.systemDefault()),
)

interface MediaScanSource {
    suspend fun scan(): List<ScannedMedia>
}

/** Reads images and videos through MediaStore (no file paths, scoped-storage friendly). Runs on IO. */
class MediaStoreSource(private val context: Context) : MediaScanSource, LocalFileReader {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun scan(): List<ScannedMedia> = withContext(Dispatchers.IO) {
        buildList {
            addAll(query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaKind.IMAGE))
            addAll(query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaKind.VIDEO))
        }
    }

    private fun query(collection: Uri, kind: MediaKind): List<ScannedMedia> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        val out = ArrayList<ScannedMedia>()
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        resolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val taken = if (c.isNull(iTaken)) 0L else c.getLong(iTaken)
                val name = c.getString(iName) ?: "media_$id"
                // Capture date when MediaStore has a believable one; else the date in the file name; else the file's own dates.
                val date = DateResolver.local(taken, c.getLong(iMod), c.getLong(iAdded), name, nowMs, zone)
                out += ScannedMedia(
                    mediaStoreId = idFor(kind, id),
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    kind = kind,
                    displayName = name,
                    mimeType = c.getString(iMime) ?: if (kind == MediaKind.IMAGE) "image/jpeg" else "video/mp4",
                    sizeBytes = c.getLong(iSize),
                    dateModifiedSec = c.getLong(iMod),
                    dateTakenMs = date.ms,
                    width = if (c.isNull(iW)) 0 else c.getInt(iW),
                    height = if (c.isNull(iH)) 0 else c.getInt(iH),
                    folderName = c.getString(iBucket) ?: "",
                    dateSource = date.source,
                    dayKey = DateKeys.dayKey(date.ms, zone),
                )
            }
        }
        return out
    }

    override fun open(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw java.io.FileNotFoundException("Unavailable")

    companion object {
        /**
         * Images and videos live in different MediaStore tables whose _ID ranges can overlap, so videos are
         * offset to keep a single stable primary key in Room.
         */
        private const val VIDEO_ID_OFFSET = 1L shl 40
        fun idFor(kind: MediaKind, rawId: Long): Long = if (kind == MediaKind.VIDEO) rawId + VIDEO_ID_OFFSET else rawId
    }
}
