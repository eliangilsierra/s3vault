package app.s3vault.domain.sync

import app.s3vault.domain.model.SyncState
import app.s3vault.domain.model.SyncState.BACKED_UP
import app.s3vault.domain.model.SyncState.FAILED
import app.s3vault.domain.model.SyncState.LOCAL_ONLY
import app.s3vault.domain.model.SyncState.QUEUED
import app.s3vault.domain.model.SyncState.REMOTE_ONLY
import app.s3vault.domain.model.SyncState.RESTORING
import app.s3vault.domain.model.SyncState.UPLOADING
import app.s3vault.domain.model.SyncState.VERIFYING

/** Eventos que pueden mover un elemento entre estados. */
enum class SyncEvent {
    /** El usuario (o el respaldo automático) lo mete en la cola. También sirve para reintentar. */
    ENQUEUE,

    /** Un worker toma el elemento de la cola. */
    START_UPLOAD,

    /** El PUT terminó; falta verificar. */
    UPLOAD_COMPLETED,

    /** El objeto remoto coincide en tamaño y checksum. */
    VERIFIED,

    /** Error temporal o de configuración global: el trabajo se conserva en la cola. */
    DEFER,

    /** Error permanente de este elemento. */
    FAIL,

    /** El usuario quita el elemento de la cola. */
    CANCEL,

    /** El contenido local cambió (distinta huella tamaño/fecha). */
    CONTENT_CHANGED,

    /** El archivo desapareció del dispositivo. */
    LOCAL_MEDIA_GONE,

    /** El archivo vuelve a aparecer en el dispositivo con el mismo contenido. */
    LOCAL_MEDIA_RETURNED_SAME,

    /** El archivo vuelve a aparecer pero con contenido distinto al respaldado. */
    LOCAL_MEDIA_RETURNED_CHANGED,

    /** El usuario pide restaurar. */
    RESTORE_REQUESTED,

    /** La restauración terminó y fue verificada. */
    RESTORE_COMPLETED,

    /** La restauración falló o se interrumpió. */
    RESTORE_FAILED,

    /** Arranque tras muerte del proceso: devuelve a la cola lo que quedó a medias. */
    RECOVER,
}

/**
 * Tabla de transiciones. Es pura y determinista, de modo que el comportamiento tras
 * cierres, pérdidas de red o reinicios es el mismo que en ejecución normal.
 *
 * Reglas importantes:
 *  * Solo `VERIFIED` conduce a [BACKED_UP].
 *  * Ningún evento de error convierte un elemento en [BACKED_UP].
 *  * [DEFER] devuelve a [QUEUED]: expirar credenciales o perder la red no pierde trabajo.
 */
object SyncStateMachine {

    fun next(from: SyncState, event: SyncEvent): SyncState? = when (event) {
        SyncEvent.ENQUEUE -> when (from) {
            LOCAL_ONLY, FAILED -> QUEUED
            QUEUED -> QUEUED
            else -> null
        }

        SyncEvent.START_UPLOAD -> if (from == QUEUED) UPLOADING else null

        SyncEvent.UPLOAD_COMPLETED -> if (from == UPLOADING) VERIFYING else null

        SyncEvent.VERIFIED -> if (from == VERIFYING) BACKED_UP else null

        SyncEvent.DEFER -> when (from) {
            UPLOADING, VERIFYING, QUEUED -> QUEUED
            else -> null
        }

        SyncEvent.FAIL -> when (from) {
            QUEUED, UPLOADING, VERIFYING -> FAILED
            else -> null
        }

        SyncEvent.CANCEL -> when (from) {
            QUEUED, UPLOADING, VERIFYING, FAILED -> LOCAL_ONLY
            else -> null
        }

        // Un cambio de contenido invalida lo que se estaba haciendo con la versión anterior.
        // El estado BACKED_UP deja de ser cierto para la versión nueva. La copia remota
        // anterior no se toca (la referencia `remoteKey` se conserva en la fila).
        SyncEvent.CONTENT_CHANGED -> when (from) {
            BACKED_UP, FAILED, LOCAL_ONLY -> LOCAL_ONLY
            QUEUED, UPLOADING, VERIFYING -> QUEUED
            else -> null
        }

        SyncEvent.LOCAL_MEDIA_GONE -> when (from) {
            BACKED_UP -> REMOTE_ONLY
            else -> null // lo no respaldado que desaparece se elimina del índice, no cambia de estado
        }

        SyncEvent.LOCAL_MEDIA_RETURNED_SAME -> if (from == REMOTE_ONLY) BACKED_UP else null

        SyncEvent.LOCAL_MEDIA_RETURNED_CHANGED -> if (from == REMOTE_ONLY) LOCAL_ONLY else null

        SyncEvent.RESTORE_REQUESTED -> if (from == REMOTE_ONLY) RESTORING else null

        SyncEvent.RESTORE_COMPLETED -> if (from == RESTORING) BACKED_UP else null

        SyncEvent.RESTORE_FAILED -> if (from == RESTORING) REMOTE_ONLY else null

        SyncEvent.RECOVER -> when (from) {
            UPLOADING, VERIFYING -> QUEUED
            else -> null
        }
    }

    fun canTransition(from: SyncState, event: SyncEvent): Boolean = next(from, event) != null

    /** Como [next], pero lanza si la transición es ilegal (error de programación). */
    fun require(from: SyncState, event: SyncEvent): SyncState =
        next(from, event) ?: throw IllegalStateException("Transición ilegal: $from --$event-->")
}
