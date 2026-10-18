package app.s3vault.sync.work

import android.content.Context
import app.s3vault.AppContainer
import app.s3vault.auth.CredentialStatus
import app.s3vault.data.local.RestoreStatus
import app.s3vault.domain.remote.FailureType
import app.s3vault.logging.SecureLog
import app.s3vault.sync.BackupEngine
import app.s3vault.sync.BackupRunner
import app.s3vault.sync.RestoreEngine
import app.s3vault.sync.RestoreOutcome
import app.s3vault.domain.remote.RemoteKeys
import java.io.File
import java.time.Clock

enum class RunResult { DONE, RETRY }

/**
 * Orquesta las pasadas de respaldo/restauración que lanzan los workers: lee la configuración,
 * comprueba credenciales sin red, construye el cliente S3 y delega en los motores puros.
 */
class BackupCoordinator(private val container: AppContainer, private val context: Context) {

    suspend fun runQueue(isStopped: () -> Boolean): RunResult {
        val settings = container.settingsSnapshot()
        if (settings.paused) return RunResult.DONE
        val config = settings.s3Config ?: return RunResult.DONE

        // Sin credenciales vigentes no se abre ninguna conexión: el trabajo queda en la cola.
        container.credentials.status().let { st ->
            val block = when (st) {
                CredentialStatus.NotConfigured -> FailureType.CREDENTIALS_MISSING
                is CredentialStatus.Expired, is CredentialStatus.RenewalFailed -> FailureType.CREDENTIALS_EXPIRED
                else -> null
            }
            if (block != null) {
                container.settings.setQueueBlock(block)
                return RunResult.DONE
            }
        }

        val store = container.newObjectStore(config)
        try {
            val keys = RemoteKeys(config.prefix)
            val engine = BackupEngine(
                store = container.backupStore, remote = store, keys = keys, media = container.mediaSource,
                index = container.remoteCatalogStore, log = container.transferLog, progress = container.progress,
            )
            val summary = BackupRunner(container.backupStore, engine, settings.concurrency).run { !isStopped() }
            SecureLog.i(TAG, "Pasada: subidos=${summary.uploaded} existentes=${summary.alreadyRemote} diferidos=${summary.deferred} fallidos=${summary.failed} bloqueo=${summary.blocked?.type}")
            container.settings.setQueueBlock(summary.blocked?.type)
            if (summary.uploaded + summary.alreadyRemote > 0) container.settings.setConnectionVerified(System.currentTimeMillis())
            return if (summary.shouldRetry) RunResult.RETRY else RunResult.DONE
        } finally {
            store.close()
            container.progress.clear()
        }
    }

    /** Revisión del respaldo automático: reindexa MediaStore y encola lo nuevo. */
    suspend fun autoBackupPass() {
        val settings = container.settingsSnapshot()
        container.galleryRepository.refreshIfPermitted()
        if (!settings.autoBackup || settings.paused || settings.s3Config == null) return
        if (container.backupRepository.enqueuePending() > 0 || container.backupRepository.hasQueued()) {
            container.workScheduler.enqueueBackup(settings.wifiOnly)
        }
    }

    suspend fun runRestore(key: String): RunResult {
        val settings = container.settingsSnapshot()
        val config = settings.s3Config ?: return RunResult.DONE
        val dao = container.db.remoteObjects()
        dao.setRestoreStatus(key, RestoreStatus.RUNNING, null)

        when (container.credentials.status()) {
            CredentialStatus.NotConfigured, is CredentialStatus.Expired, is CredentialStatus.RenewalFailed -> {
                dao.setRestoreStatus(key, RestoreStatus.FAILED, FailureType.CREDENTIALS_EXPIRED.name)
                container.settings.setQueueBlock(FailureType.CREDENTIALS_EXPIRED)
                return RunResult.DONE
            }
            else -> Unit
        }

        val store = container.newObjectStore(config)
        try {
            val engine = RestoreEngine(
                remote = store, keys = RemoteKeys(config.prefix), store = container.backupStore,
                destination = container.restoreDestination, workDir = File(context.cacheDir, "restore"),
                log = container.transferLog, clock = Clock.systemUTC(),
                onProgress = { _, _, _ -> },
            )
            return when (val out = engine.restore(key)) {
                is RestoreOutcome.Restored -> { dao.setRestoreStatus(key, RestoreStatus.IDLE, null); RunResult.DONE }
                is RestoreOutcome.Deferred -> { dao.setRestoreStatus(key, RestoreStatus.QUEUED, out.failure.type.name); RunResult.RETRY }
                is RestoreOutcome.Blocked -> {
                    dao.setRestoreStatus(key, RestoreStatus.FAILED, out.failure.type.name)
                    container.settings.setQueueBlock(out.failure.type)
                    RunResult.DONE
                }
                is RestoreOutcome.Failed -> { dao.setRestoreStatus(key, RestoreStatus.FAILED, out.kind.name); RunResult.DONE }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            dao.setRestoreStatus(key, RestoreStatus.QUEUED, null)
            throw e
        } finally {
            store.close()
        }
    }

    private companion object { const val TAG = "Coordinator" }
}
