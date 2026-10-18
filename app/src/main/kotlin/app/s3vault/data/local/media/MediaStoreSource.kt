package app.s3vault.data.local.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.s3vault.domain.model.MediaItem
import app.s3vault.domain.repo.MediaSource
import app.s3vault.domain.repo.MediaStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

/** Acceso al contenido de los medios locales mediante ContentResolver. */
class MediaStoreSource(context: Context) : MediaSource {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override suspend fun stat(item: MediaItem): MediaStat? = withContext(Dispatchers.IO) {
        val uri = item.uri?.let(Uri::parse) ?: return@withContext null
        try {
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) null
                else MediaStat(c.getLong(0), c.getLong(1) * 1000L)
            }
        } catch (_: SecurityException) {
            null
        }
    }

    override fun open(item: MediaItem): InputStream {
        val uri = Uri.parse(item.uri ?: throw FileNotFoundException("Sin URI"))
        // Con "Ubicación de medios" concedida se lee el original (incluye EXIF GPS); sin ella, Android
        // entrega la imagen con la ubicación eliminada.
        val source = if (!item.isVideo && hasMediaLocation()) MediaStore.setRequireOriginal(uri) else uri
        return resolver.openInputStream(source) ?: throw FileNotFoundException("No se pudo abrir el archivo")
    }

    private fun hasMediaLocation() = ContextCompat.checkSelfPermission(
        appContext, android.Manifest.permission.ACCESS_MEDIA_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
