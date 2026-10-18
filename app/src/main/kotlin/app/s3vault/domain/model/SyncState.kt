package app.s3vault.domain.model

/**
 * Estado de sincronización de un elemento. Es una máquina de estados explícita
 * (ver [app.s3vault.domain.sync.SyncStateMachine]); nunca se deduce de un booleano.
 */
enum class SyncState {
    /** Solo existe en el dispositivo (o su última versión aún no se respalda). */
    LOCAL_ONLY,

    /** En la cola persistente, pendiente de que WorkManager lo procese. */
    QUEUED,

    /** Transferencia hacia S3 en curso. */
    UPLOADING,

    /** Subida terminada; falta confirmar tamaño y checksum remotos. */
    VERIFYING,

    /** Copia remota verificada. Solo este estado significa "respaldado". */
    BACKED_UP,

    /** Error permanente de este elemento; requiere acción del usuario. */
    FAILED,

    /** Existe en S3 pero ya no en el dispositivo. */
    REMOTE_ONLY,

    /** Descarga/restauración desde S3 en curso o encolada. */
    RESTORING;

    /** Estados en los que hay una operación viva (el proceso puede morir en medio). */
    val isInFlight: Boolean get() = this == UPLOADING || this == VERIFYING || this == RESTORING
}
