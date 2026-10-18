package app.s3vault.data.local.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.s3vault.domain.repo.PublishedMedia
import app.s3vault.domain.repo.RestoreDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Publica lo restaurado en `Pictures/S3Vault` (o `Movies/S3Vault`) mediante MediaStore. No hace
 * falta ningún permiso de almacenamiento y la foto aparece en la galería. Nunca sobrescribe: si
 * el nombre existe, elige uno libre ("nombre (1).jpg").
 */
class MediaStoreRestoreDestination(context: Context) : RestoreDestination {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun publish(file: File, displayName: String, mimeType: String, capturedAtMs: Long?): PublishedMedia =
        withContext(Dispatchers.IO) {
            val isVideo = mimeType.startsWith("video/")
            val collection: Uri = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val relativePath = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/S3Vault"
            val finalName = freeName(collection, relativePath, displayName)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                capturedAtMs?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore rechazó la inserción")
            try {
                resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("No se pudo abrir el destino")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null) // solo el archivo a medio escribir que acabamos de crear
                throw if (e is IOException) e else IOException("Error al publicar la foto", e)
            }
            val actualName = resolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getLong(1) * 1000L else null }
            PublishedMedia(uri.toString(), actualName?.first ?: finalName, file.length(), actualName?.second ?: System.currentTimeMillis())
        }

    private fun freeName(collection: Uri, relativePath: String, wanted: String): String {
        fun exists(name: String): Boolean = resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, "$relativePath/"), null,
        )?.use { it.count > 0 } ?: false

        if (!exists(wanted)) return wanted
        val stem = wanted.substringBeforeLast('.', wanted)
        val ext = wanted.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (n < 10_000) {
            val candidate = "$stem ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
        return "$stem (${System.currentTimeMillis()})$ext"
    }
}
