package app.s3vault.data.repo

import app.s3vault.AppContainer
import app.s3vault.data.local.RemoteObjectEntity
import app.s3vault.data.local.RestoreStatus
import app.s3vault.data.remote.RemoteCatalog
import app.s3vault.data.remote.RemoteHead
import app.s3vault.domain.model.SyncState
import app.s3vault.domain.remote.FailureType
import app.s3vault.domain.remote.RemoteException
import app.s3vault.domain.remote.RemoteFailure
import app.s3vault.domain.remote.RemoteKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Entrada de la biblioteca remota con su estado respecto al dispositivo. */
data class RemoteListItem(
    val entry: RemoteObjectEntity,
    /** BACKED_UP (ya está en el dispositivo), REMOTE_ONLY o RESTORING. */
    val state: SyncState,
)

sealed interface RemoteRefreshResult {
    data class Success(val count: Int) : RemoteRefreshResult
    data object NotConfigured : RemoteRefreshResult
    data class Failure(val failure: RemoteFailure) : RemoteRefreshResult
}

class RemoteLibraryRepository(private val c: AppContainer) {
    private val dao get() = c.db.remoteObjects()

    val items: Flow<List<RemoteListItem>> = combine(
        c.db.remoteObjects().observeAll(),
        c.db.media().observeRemoteLinks(),
    ) { objects, links ->
        val local = links.filter { it.hasLocalFile && it.state == SyncState.BACKED_UP }.map { it.remoteKey }.toSet()
        objects.map { o ->
            val state = when {
                o.restoreStatus == RestoreStatus.QUEUED || o.restoreStatus == RestoreStatus.RUNNING -> SyncState.RESTORING
                o.key in local -> SyncState.BACKED_UP
                else -> SyncState.REMOTE_ONLY
            }
            RemoteListItem(o, state)
        }
    }

    val lastRefreshMs: Flow<Long?> = c.db.remoteObjects().observeLastRefresh()

    /** Una solicitud LIST por cada 1000 objetos; solo ocurre cuando el usuario pulsa «Actualizar». */
    suspend fun refresh(): RemoteRefreshResult {
        val settings = c.settingsSnapshot()
        val config = settings.s3Config ?: return RemoteRefreshResult.NotConfigured
        val store = c.newObjectStore(config)
        return try {
            val entries = RemoteCatalog(store, RemoteKeys(config.prefix), c.remoteCatalogStore, System::currentTimeMillis).refresh()
            c.settings.setQueueBlock(null)
            RemoteRefreshResult.Success(entries.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            if (e.failure.type.blocksQueue) c.settings.setQueueBlock(e.failure.type)
            RemoteRefreshResult.Failure(e.failure)
        } finally {
            store.close()
        }
    }

    /** Metadatos de un objeto (un HEAD por acción explícita del usuario). */
    suspend fun head(key: String): Result<RemoteHead?> {
        val config = c.settingsSnapshot().s3Config ?: return Result.failure(IllegalStateException("not configured"))
        val store = c.newObjectStore(config)
        return try {
            Result.success(store.head(key))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            Result.failure(e)
        } finally {
            store.close()
        }
    }

    /** Marca las claves como encoladas y programa su descarga. No sobrescribe nada local. */
    suspend fun restore(keys: List<String>) {
        val wifiOnly = c.settingsSnapshot().wifiOnly
        for (key in keys) {
            val row = dao.get(key) ?: continue
            if (row.restoreStatus == RestoreStatus.QUEUED || row.restoreStatus == RestoreStatus.RUNNING) continue
            dao.setRestoreStatus(key, RestoreStatus.QUEUED, null)
            c.workScheduler.enqueueRestore(key, wifiOnly)
        }
    }

    suspend fun dismissRestoreError(key: String) = dao.setRestoreStatus(key, RestoreStatus.IDLE, null)

    @Suppress("unused")
    private fun isBlocking(t: FailureType) = t.blocksQueue
}
