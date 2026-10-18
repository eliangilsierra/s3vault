package app.s3vault.sync

import app.s3vault.domain.model.MediaItem
import app.s3vault.domain.model.SyncState
import app.s3vault.domain.sync.SyncEvent
import app.s3vault.domain.sync.SyncStateMachine

/** Foto/vídeo tal como lo devuelve MediaStore en un escaneo. */
data class ScannedMedia(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateModifiedMs: Long,
    val dateTakenMs: Long?,
    val width: Int?,
    val height: Int?,
    val isVideo: Boolean,
    val folder: String?,
)

data class ReconcileResult(
    val upserts: List<MediaItem>,
    /** Filas que ya no tienen archivo ni copia remota: no queda nada que conservar. */
    val deletes: List<String>,
    val added: Int,
    val changed: Int,
    val gone: Int,
)

/**
 * Compara el índice (Room) con un escaneo de MediaStore. Es pura: la huella barata
 * (tamaño + fecha de modificación) decide si hay que invalidar el hash; nunca se calcula
 * un hash aquí. No genera ni propone borrados remotos: solo actualiza el índice local.
 */
object MediaReconciler {

    fun reconcile(
        existing: List<MediaItem>,
        scanned: List<ScannedMedia>,
        newAssetId: () -> String,
        /** Si es false, el escaneo es parcial/incierto y no se marca ningún elemento como desaparecido. */
        trustAbsence: Boolean = true,
    ): ReconcileResult {
        val byUri = existing.filter { it.uri != null }.associateBy { it.uri!! }
        val seen = HashSet<String>()
        val upserts = ArrayList<MediaItem>()
        var added = 0
        var changed = 0

        for (s in scanned) {
            seen += s.uri
            val old = byUri[s.uri]
            if (old == null) {
                upserts += MediaItem.newLocal(
                    assetId = newAssetId(), uri = s.uri, displayName = s.displayName, mimeType = s.mimeType,
                    sizeBytes = s.sizeBytes, dateModifiedMs = s.dateModifiedMs, dateTakenMs = s.dateTakenMs,
                    width = s.width, height = s.height, isVideo = s.isVideo, folder = s.folder,
                )
                added++
                continue
            }
            val contentChanged = old.sizeBytes != s.sizeBytes || old.dateModifiedMs != s.dateModifiedMs
            var updated = old.copy(
                displayName = s.displayName, mimeType = s.mimeType, dateTakenMs = s.dateTakenMs,
                width = s.width, height = s.height, isVideo = s.isVideo, folder = s.folder,
                sizeBytes = s.sizeBytes, dateModifiedMs = s.dateModifiedMs,
            )
            if (old.state == SyncState.REMOTE_ONLY) {
                val event = if (contentChanged) SyncEvent.LOCAL_MEDIA_RETURNED_CHANGED else SyncEvent.LOCAL_MEDIA_RETURNED_SAME
                updated = updated.copy(state = SyncStateMachine.require(old.state, event))
            } else if (contentChanged) {
                val next = SyncStateMachine.next(old.state, SyncEvent.CONTENT_CHANGED)
                if (next != null) updated = updated.copy(state = next)
                changed++
            }
            // El hash deja de ser válido por sí solo (isHashCurrent compara huellas); no se borra la
            // referencia remota anterior: sigue siendo una copia válida de la versión previa.
            if (updated != old) upserts += updated
        }

        val deletes = ArrayList<String>()
        var gone = 0
        if (trustAbsence) {
            for (old in existing) {
                val uri = old.uri
                if (uri != null && uri in seen) continue
                if (old.state == SyncState.REMOTE_ONLY || old.state == SyncState.RESTORING) continue
                val hasRemoteCopy = old.remoteKey != null && old.backedUpVersionId != null
                if (hasRemoteCopy) {
                    upserts += old.copy(state = SyncState.REMOTE_ONLY)
                    gone++
                } else {
                    deletes += old.assetId // nunca se respaldó: no hay nada que conservar
                    gone++
                }
            }
        }
        return ReconcileResult(upserts, deletes, added, changed, gone)
    }
}
