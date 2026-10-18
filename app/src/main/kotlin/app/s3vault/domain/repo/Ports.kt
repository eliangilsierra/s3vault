package app.s3vault.domain.repo

import app.s3vault.domain.model.ErrorKind
import app.s3vault.domain.model.MediaItem
import app.s3vault.domain.model.SyncState
import java.io.File
import java.io.InputStream

/** Datos baratos de un archivo local, usados como huella para detectar cambios. */
data class MediaStat(val sizeBytes: Long, val dateModifiedMs: Long)

/** Acceso al contenido de los archivos locales (en Android, vía ContentResolver). */
interface MediaSource {
    /** `null` si el archivo ya no existe o no es accesible. */
    suspend fun stat(item: MediaItem): MediaStat?
    fun open(item: MediaItem): InputStream
}

/** Persistencia del índice local (Room es la fuente de verdad del estado de sincronización). */
interface BackupStore {
    suspend fun get(assetId: String): MediaItem?

    /** Elementos en [SyncState.QUEUED], los más antiguos primero. */
    suspend fun queued(limit: Int): List<MediaItem>

    /**
     * Actualiza la fila solo si su estado actual es [expected] (compare-and-set). Evita que dos
     * workers o un cambio concurrente pisen una transición.
     */
    suspend fun compareAndSet(expected: SyncState, updated: MediaItem): Boolean

    /** Tras un arranque en frío: UPLOADING/VERIFYING → QUEUED. Devuelve cuántas filas cambió. */
    suspend fun recoverInterrupted(): Int

    suspend fun upsert(item: MediaItem)
}

/** Caché local de los objetos vistos en S3 (último LIST). Permite deduplicar sin red. */
interface RemoteIndex {
    /** Clave de un objeto remoto con ese `versionId` (hash de contenido), si se conoce. */
    suspend fun findKeyByVersion(versionId: String): String?
    suspend fun remember(key: String, sizeBytes: Long, lastModifiedMs: Long?, etag: String?)
}

/** Registro local (no sensible) de lo ocurrido en cada transferencia. */
interface TransferLog {
    suspend fun record(
        assetId: String?,
        kind: TransferKind,
        success: Boolean,
        errorKind: ErrorKind?,
        message: String?,
    )
}

enum class TransferKind { UPLOAD, RESTORE }

/** Progreso en vivo por archivo (efímero: la cola vive en Room, no en memoria). */
fun interface ProgressSink {
    fun onProgress(assetId: String, doneBytes: Long, totalBytes: Long)
}

/** Dónde dejar una fotografía restaurada sin sobrescribir nada. */
interface RestoreDestination {
    /**
     * Publica [file] (ya verificado) como un archivo nuevo del dispositivo. Si el nombre ya
     * existe, debe elegir otro; nunca sobrescribir. Devuelve el URI y el nombre final.
     */
    suspend fun publish(file: File, displayName: String, mimeType: String, capturedAtMs: Long?): PublishedMedia
}

data class PublishedMedia(val uri: String, val displayName: String, val sizeBytes: Long, val dateModifiedMs: Long)
