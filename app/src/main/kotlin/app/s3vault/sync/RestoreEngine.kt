package app.s3vault.sync

import app.s3vault.data.remote.ObjectStore
import app.s3vault.domain.model.ErrorKind
import app.s3vault.domain.model.MediaItem
import app.s3vault.domain.model.SyncState
import app.s3vault.domain.remote.FailureType
import app.s3vault.domain.remote.ObjectMetadata
import app.s3vault.domain.remote.RemoteException
import app.s3vault.domain.remote.RemoteFailure
import app.s3vault.domain.remote.RemoteKeys
import app.s3vault.domain.repo.BackupStore
import app.s3vault.domain.repo.RestoreDestination
import app.s3vault.domain.repo.TransferKind
import app.s3vault.domain.repo.TransferLog
import app.s3vault.logging.Redactor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Clock

sealed interface RestoreOutcome {
    data class Restored(val item: MediaItem, val verified: Boolean) : RestoreOutcome
    data class Deferred(val failure: RemoteFailure) : RestoreOutcome
    data class Blocked(val failure: RemoteFailure) : RestoreOutcome
    data class Failed(val kind: ErrorKind, val detail: String?) : RestoreOutcome
}

/**
 * Descarga un objeto de S3, lo verifica y lo publica como archivo nuevo del dispositivo.
 *
 * Reglas: nunca sobrescribe archivos locales (el destino elige un nombre libre); la descarga se
 * hace a un archivo `.part` reanudable con `Range` + `If-Match` (si el objeto cambió en S3 se
 * descarta lo parcial); y solo se publica si el SHA-256 coincide con el metadato del objeto.
 */
class RestoreEngine(
    private val remote: ObjectStore,
    private val keys: RemoteKeys,
    private val store: BackupStore,
    private val destination: RestoreDestination,
    private val workDir: File,
    private val log: TransferLog,
    private val clock: Clock = Clock.systemUTC(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onProgress: (key: String, done: Long, total: Long) -> Unit = { _, _, _ -> },
) {
    suspend fun restore(key: String): RestoreOutcome {
        val parsed = keys.parse(key) ?: return RestoreOutcome.Failed(ErrorKind.UNKNOWN, "Clave no reconocida")
        try {
            val head = remote.head(key)
                ?: return RestoreOutcome.Failed(ErrorKind.UNKNOWN, "El objeto ya no existe en S3")
            val meta = ObjectMetadata.fromMap(head.userMetadata)
            val part = partFile(key)
            val etagFile = File(part.path + ".etag")
            workDir.mkdirs()

            // Reanudar solo si lo parcial pertenece exactamente a esta versión del objeto.
            var offset = 0L
            if (part.exists() && etagFile.exists() && etagFile.readText() == head.etag && part.length() in 1 until head.sizeBytes) {
                offset = part.length()
            } else {
                part.delete(); etagFile.delete()
            }
            head.etag?.let { etagFile.writeText(it) }

            try {
                withContext(io) {
                    val ctx = currentCoroutineContext()
                    FileOutputStream(part, offset > 0).use { out ->
                        remote.get(key, out, offset, head.etag) { written ->
                            ctx.ensureActive()
                            onProgress(key, offset + written, head.sizeBytes)
                        }
                        out.fd.sync()
                    }
                }
            } catch (e: RemoteException) {
                if (e.failure.type == FailureType.OBJECT_CHANGED) { part.delete(); etagFile.delete() }
                throw e
            }

            // Verificación de integridad antes de tocar la galería.
            if (part.length() != head.sizeBytes) {
                part.delete(); etagFile.delete()
                throw RemoteException(RemoteFailure(FailureType.CHECKSUM_MISMATCH, "tamaño descargado distinto"))
            }
            val hash = withContext(io) {
                val ctx = currentCoroutineContext()
                Hashing.sha256(part.inputStream()) { ctx.ensureActive() }
            }
            val expected = meta?.sha256Hex
            val ok = if (expected != null) hash.sha256Hex.equals(expected, true)
            else hash.sha256Hex.startsWith(parsed.versionId) // objetos de otras herramientas: solo el id de versión
            if (!ok) {
                part.delete(); etagFile.delete()
                throw RemoteException(RemoteFailure(FailureType.CHECKSUM_MISMATCH, "SHA-256 no coincide"))
            }

            val displayName = meta?.originalName?.takeIf { it.isNotBlank() } ?: parsed.name
            val mime = head.contentType?.takeIf { it.isNotBlank() } ?: guessMime(displayName)
            val published = destination.publish(part, displayName, mime, meta?.capturedAtMs)
            part.delete(); etagFile.delete()

            val existing = store.get(parsed.assetId)
            val restored = (existing ?: MediaItem.newLocal(
                assetId = parsed.assetId, uri = published.uri, displayName = published.displayName,
                mimeType = mime, sizeBytes = published.sizeBytes, dateModifiedMs = published.dateModifiedMs,
                dateTakenMs = meta?.capturedAtMs, width = meta?.width, height = meta?.height,
                isVideo = mime.startsWith("video/"), folder = null,
            )).copy(
                uri = published.uri,
                displayName = published.displayName,
                sizeBytes = published.sizeBytes,
                dateModifiedMs = published.dateModifiedMs,
                sha256 = hash.sha256Hex,
                hashedSizeBytes = published.sizeBytes,
                hashedModifiedMs = published.dateModifiedMs,
                versionId = hash.versionId,
                remoteKey = key,
                backedUpVersionId = hash.versionId,
                state = SyncState.BACKED_UP,
                lastBackupAtMs = existing?.lastBackupAtMs ?: clock.millis(),
                lastError = null,
                lastErrorKind = null,
                attempts = 0,
            )
            store.upsert(restored)
            log.record(parsed.assetId, TransferKind.RESTORE, true, null, if (expected == null) "sin checksum propio" else null)
            return RestoreOutcome.Restored(restored, verified = expected != null)
        } catch (e: RemoteException) {
            val f = e.failure
            log.record(parsed.assetId, TransferKind.RESTORE, false, f.type.errorKind, f.detail?.let { Redactor.redact(it).take(200) })
            return when {
                f.type.blocksQueue -> RestoreOutcome.Blocked(f)
                f.type.isTransient -> RestoreOutcome.Deferred(f)
                else -> RestoreOutcome.Failed(f.type.errorKind, f.detail?.let { Redactor.redact(it).take(200) })
            }
        } catch (e: IOException) {
            log.record(parsed.assetId, TransferKind.RESTORE, false, ErrorKind.STORAGE, e.javaClass.simpleName)
            return RestoreOutcome.Failed(ErrorKind.STORAGE, "No se pudo escribir en el dispositivo")
        }
    }

    private fun partFile(key: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return File(workDir, digest.take(12).joinToString("") { "%02x".format(it) } + ".part")
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }
}
