package app.s3vault.domain.model

/**
 * Fila del índice local (Room es la fuente de verdad del estado de sincronización).
 *
 * [assetId] es un identificador estable generado por la app; no es el `_ID` de MediaStore,
 * que puede cambiar al reinstalar o al restaurar el archivo.
 */
data class MediaItem(
    val assetId: String,
    val uri: String?,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateModifiedMs: Long,
    val dateTakenMs: Long?,
    val width: Int?,
    val height: Int?,
    val isVideo: Boolean,
    val folder: String?,
    /** SHA-256 hexadecimal del contenido; solo válido mientras [isHashCurrent] sea true. */
    val sha256: String?,
    val hashedSizeBytes: Long?,
    val hashedModifiedMs: Long?,
    /** Identificador de la versión del contenido (prefijo del SHA-256). */
    val versionId: String?,
    /** Clave del último objeto remoto verificado de este activo. */
    val remoteKey: String?,
    val backedUpVersionId: String?,
    val state: SyncState,
    val lastBackupAtMs: Long?,
    val lastError: String?,
    val lastErrorKind: ErrorKind?,
    val attempts: Int,
) {
    /**
     * El hash se invalida cuando cambia la huella barata (tamaño + fecha de modificación)
     * respecto a la que tenía el archivo cuando se calculó. Así nunca se re-hashea sin motivo.
     */
    val isHashCurrent: Boolean
        get() = sha256 != null && hashedSizeBytes == sizeBytes && hashedModifiedMs == dateModifiedMs

    /** El contenido actual ya está respaldado y verificado. */
    val isCurrentVersionBackedUp: Boolean
        get() = state == SyncState.BACKED_UP && versionId != null && versionId == backedUpVersionId

    companion object {
        fun newLocal(
            assetId: String,
            uri: String,
            displayName: String,
            mimeType: String,
            sizeBytes: Long,
            dateModifiedMs: Long,
            dateTakenMs: Long?,
            width: Int?,
            height: Int?,
            isVideo: Boolean,
            folder: String?,
        ) = MediaItem(
            assetId = assetId, uri = uri, displayName = displayName, mimeType = mimeType,
            sizeBytes = sizeBytes, dateModifiedMs = dateModifiedMs, dateTakenMs = dateTakenMs,
            width = width, height = height, isVideo = isVideo, folder = folder,
            sha256 = null, hashedSizeBytes = null, hashedModifiedMs = null, versionId = null,
            remoteKey = null, backedUpVersionId = null, state = SyncState.LOCAL_ONLY,
            lastBackupAtMs = null, lastError = null, lastErrorKind = null, attempts = 0,
        )
    }
}
