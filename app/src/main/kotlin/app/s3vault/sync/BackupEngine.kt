package app.s3vault.sync

import app.s3vault.data.remote.ObjectStore
import app.s3vault.data.remote.RemoteHead
import app.s3vault.data.remote.UploadSource
import app.s3vault.domain.model.ErrorKind
import app.s3vault.domain.model.MediaItem
import app.s3vault.domain.model.SyncState
import app.s3vault.domain.remote.FailureType
import app.s3vault.domain.remote.ObjectMetadata
import app.s3vault.domain.remote.RemoteException
import app.s3vault.domain.remote.RemoteFailure
import app.s3vault.domain.remote.RemoteKeys
import app.s3vault.domain.repo.BackupStore
import app.s3vault.domain.repo.MediaSource
import app.s3vault.domain.repo.ProgressSink
import app.s3vault.domain.repo.RemoteIndex
import app.s3vault.domain.repo.TransferKind
import app.s3vault.domain.repo.TransferLog
import app.s3vault.domain.sync.SyncEvent
import app.s3vault.domain.sync.SyncStateMachine
import app.s3vault.logging.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.Clock

/** Resultado de procesar un elemento de la cola. */
sealed interface ItemOutcome {
    /** No había nada que hacer (otro worker lo tomó o ya no está en cola). */
    data object Skipped : ItemOutcome

    /** Respaldo verificado. [uploaded] es false si ya existía una copia válida (sin transferencia). */
    data class BackedUp(val uploaded: Boolean) : ItemOutcome

    /** Fallo temporal: el elemento vuelve a la cola y conviene reintentar con backoff. */
    data class Deferred(val failure: RemoteFailure) : ItemOutcome

    /** Fallo que afecta a toda la cola (credenciales, permisos, bucket, región). */
    data class Blocked(val failure: RemoteFailure) : ItemOutcome

    /** Fallo permanente de este elemento. */
    data class Failed(val kind: ErrorKind, val detail: String?) : ItemOutcome
}

/**
 * Sube un elemento a S3 siguiendo la máquina de estados. Garantías:
 *  * un elemento solo pasa a BACKED_UP tras verificar tamaño y checksum remotos;
 *  * los errores temporales, la falta de credenciales o la cancelación devuelven el elemento a
 *    la cola (no se pierde trabajo) en lugar de marcarlo como respaldado o fallido;
 *  * si ya existe una copia válida (mismo contenido), no se transfiere nada.
 */
class BackupEngine(
    private val store: BackupStore,
    private val remote: ObjectStore,
    private val keys: RemoteKeys,
    private val media: MediaSource,
    private val index: RemoteIndex,
    private val log: TransferLog,
    private val progress: ProgressSink,
    private val clock: Clock = Clock.systemUTC(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    suspend fun process(assetId: String): ItemOutcome {
        val queued = store.get(assetId) ?: return ItemOutcome.Skipped
        if (queued.state != SyncState.QUEUED) return ItemOutcome.Skipped
        val started = queued.copy(
            state = SyncStateMachine.require(queued.state, SyncEvent.START_UPLOAD),
            attempts = queued.attempts + 1,
        )
        if (!store.compareAndSet(SyncState.QUEUED, started)) return ItemOutcome.Skipped

        val run = Run(started)
        return try {
            run.execute()
        } catch (e: CancellationException) {
            // Pausa/cancelación/restricciones perdidas: conservar el trabajo pendiente.
            withContext(NonCancellable) { run.requeue(null) }
            throw e
        }
    }

    private class ConcurrentChange : Exception()

    private inner class Run(start: MediaItem) {
        var item: MediaItem = start

        suspend fun execute(): ItemOutcome = try {
            upload()
        } catch (e: ConcurrentChange) {
            // El usuario canceló o el contenido cambió mientras trabajábamos: no pisar su decisión.
            ItemOutcome.Skipped
        } catch (e: RemoteException) {
            onRemoteFailure(e.failure)
        } catch (e: FileNotFoundException) {
            fail(ErrorKind.SOURCE_MISSING, "El archivo local ya no está disponible")
        } catch (e: SecurityException) {
            fail(ErrorKind.SOURCE_MISSING, "Sin permiso para leer el archivo local")
        } catch (e: IOException) {
            onRemoteFailure(RemoteFailure(FailureType.UNKNOWN, "E/S local: ${e.javaClass.simpleName}"))
        }

        private suspend fun upload(): ItemOutcome {
            val stat = media.stat(item) ?: return fail(ErrorKind.SOURCE_MISSING, "El archivo local ya no existe")

            // Huella barata distinta => el contenido cambió: el hash anterior ya no vale.
            if (stat.sizeBytes != item.sizeBytes || stat.dateModifiedMs != item.dateModifiedMs) {
                item = item.copy(sizeBytes = stat.sizeBytes, dateModifiedMs = stat.dateModifiedMs)
            }
            if (!item.isHashCurrent) {
                val hash = withContext(io) {
                    val ctx = currentCoroutineContext()
                    Hashing.sha256(media.open(item)) { ctx.ensureActive() }
                }
                item = item.copy(
                    sha256 = hash.sha256Hex,
                    sizeBytes = hash.bytes,
                    hashedSizeBytes = hash.bytes,
                    hashedModifiedMs = stat.dateModifiedMs,
                    versionId = hash.versionId,
                )
                persist(SyncState.UPLOADING)
            }
            val sha = item.sha256!!
            val versionId = item.versionId!!
            val base64 = Hashing.hexToBase64(sha)
            val size = item.sizeBytes
            val key = keys.objectKey(item.assetId, versionId, item.displayName)

            // 1) ¿Ya existe una copia válida de este contenido (p. ej. tras reinstalar)?
            index.findKeyByVersion(versionId)?.let { known ->
                val head = remote.head(known)
                if (head != null && matches(head, sha, base64, size)) return backedUp(known, uploaded = false)
            }
            // 2) ¿Quedó subido por un intento anterior que no llegó a registrarse?
            remote.head(key)?.let { head ->
                if (matches(head, sha, base64, size)) return backedUp(key, uploaded = false)
            }

            // 3) Subida: el checksum viaja en la solicitud y S3 rechaza un cuerpo distinto.
            val source = object : UploadSource {
                override val length: Long = size
                override fun open(): InputStream = media.open(item)
            }
            remote.put(
                key = key,
                source = source,
                contentType = item.mimeType.ifBlank { "application/octet-stream" },
                metadata = ObjectMetadata(
                    sha256Hex = sha,
                    originalName = item.displayName,
                    capturedAtMs = item.dateTakenMs,
                    width = item.width,
                    height = item.height,
                    assetId = item.assetId,
                    versionId = versionId,
                ).toMap(),
                sha256Base64 = base64,
                onProgress = { sent -> progress.onProgress(item.assetId, sent, size) },
            )

            // 4) Verificación antes de declarar nada como respaldado.
            transition(SyncEvent.UPLOAD_COMPLETED)
            val head = remote.head(key)
                ?: throw RemoteException(RemoteFailure(FailureType.CHECKSUM_MISMATCH, "objeto no visible tras la subida"))
            if (!matches(head, sha, base64, size)) {
                throw RemoteException(RemoteFailure(FailureType.CHECKSUM_MISMATCH, "tamaño o checksum distintos"))
            }
            index.remember(key, head.sizeBytes, head.lastModifiedMs, head.etag)
            return backedUp(key, uploaded = true)
        }

        private fun matches(head: RemoteHead, sha: String, base64: String, size: Long): Boolean =
            head.sizeBytes == size &&
                (head.checksumSha256Base64 == base64 ||
                    head.userMetadata.entries.any { it.key.equals(ObjectMetadata.SHA256, true) && it.value.equals(sha, true) })

        private suspend fun persist(expected: SyncState) {
            if (!store.compareAndSet(expected, item)) throw ConcurrentChange()
        }

        private suspend fun transition(event: SyncEvent) {
            val from = item.state
            item = item.copy(state = SyncStateMachine.require(from, event))
            persist(from)
        }

        private suspend fun backedUp(key: String, uploaded: Boolean): ItemOutcome {
            // Si no hubo subida (copia ya existente) también pasamos por VERIFYING: la comprobación
            // de tamaño y checksum remotos ya se hizo en `matches`.
            if (item.state == SyncState.UPLOADING) transition(SyncEvent.UPLOAD_COMPLETED)
            transition(SyncEvent.VERIFIED)
            item = item.copy(
                remoteKey = key,
                backedUpVersionId = item.versionId,
                lastBackupAtMs = clock.millis(),
                lastError = null,
                lastErrorKind = null,
                attempts = 0,
            )
            persist(SyncState.BACKED_UP)
            log.record(item.assetId, TransferKind.UPLOAD, true, null, if (uploaded) null else "ya existía")
            progress.onProgress(item.assetId, item.sizeBytes, item.sizeBytes)
            return ItemOutcome.BackedUp(uploaded)
        }

        private suspend fun onRemoteFailure(f: RemoteFailure): ItemOutcome {
            val kind = f.type.errorKind
            return when {
                f.type.blocksQueue -> {
                    requeue(f)
                    ItemOutcome.Blocked(f)
                }

                f.type == FailureType.TOO_LARGE -> fail(kind, f.detail)
                item.attempts >= maxAttempts -> fail(kind, f.detail ?: "Demasiados reintentos")
                else -> {
                    requeue(f)
                    ItemOutcome.Deferred(f)
                }
            }
        }

        /** Devuelve el elemento a la cola sin perder el trabajo. Nunca lo marca como respaldado. */
        suspend fun requeue(failure: RemoteFailure?) {
            val from = item.state
            val to = SyncStateMachine.next(from, SyncEvent.DEFER) ?: return
            item = item.copy(
                state = to,
                lastError = (failure?.detail ?: failure?.type?.name)?.let(::safeText),
                lastErrorKind = failure?.type?.errorKind ?: item.lastErrorKind,
            )
            store.compareAndSet(from, item)
            if (failure != null) {
                log.record(item.assetId, TransferKind.UPLOAD, false, failure.type.errorKind, failure.detail?.let(::safeText))
            }
        }

        private suspend fun fail(kind: ErrorKind, detail: String?): ItemOutcome {
            val from = item.state
            val to = SyncStateMachine.next(from, SyncEvent.FAIL) ?: SyncState.FAILED
            item = item.copy(state = to, lastError = safeText(detail ?: kind.name), lastErrorKind = kind)
            store.compareAndSet(from, item)
            log.record(item.assetId, TransferKind.UPLOAD, false, kind, detail?.let(::safeText))
            return ItemOutcome.Failed(kind, detail)
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 8

        /** Lo que llega a Room y al registro de transferencias nunca puede contener secretos. */
        internal fun safeText(text: String): String = Redactor.redact(text).take(200)
    }
}
