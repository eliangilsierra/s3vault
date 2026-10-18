package app.s3vault.data.repo

import app.s3vault.auth.CredentialsManager
import app.s3vault.data.local.AppDatabase
import app.s3vault.data.local.toDomain
import app.s3vault.domain.model.MediaItem
import app.s3vault.domain.model.SyncState
import app.s3vault.settings.SettingsRepository
import app.s3vault.sync.work.WorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class BackupCounts(
    val backedUp: Int = 0,
    val pending: Int = 0, // LOCAL_ONLY
    val queued: Int = 0,
    val inFlight: Int = 0,
    val failed: Int = 0,
)

class BackupRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val scheduler: WorkScheduler,
    @Suppress("unused") private val credentials: CredentialsManager,
) {
    private val dao = db.media()

    val counts: Flow<BackupCounts> = dao.observeCounts().map { rows ->
        val m = rows.associate { it.state to it.count }
        BackupCounts(
            backedUp = m[SyncState.BACKED_UP] ?: 0,
            pending = m[SyncState.LOCAL_ONLY] ?: 0,
            queued = m[SyncState.QUEUED] ?: 0,
            inFlight = (m[SyncState.UPLOADING] ?: 0) + (m[SyncState.VERIFYING] ?: 0),
            failed = m[SyncState.FAILED] ?: 0,
        )
    }
    val inFlight: Flow<List<MediaItem>> = dao.observeInFlight().map { l -> l.map { it.toDomain() } }
    val queued: Flow<List<MediaItem>> = dao.observeQueued(50).map { l -> l.map { it.toDomain() } }
    val failed: Flow<List<MediaItem>> = dao.observeFailed(100).map { l -> l.map { it.toDomain() } }
    val lastBackupMs: Flow<Long?> = dao.observeLastBackup()
    val recentLog = db.transferLog().observeRecent(30)

    /** Mete en la cola los elementos indicados (excluye vídeos si el ajuste lo pide). */
    suspend fun enqueue(assetIds: List<String>): Int {
        val s = settings.settings.first()
        val ids = if (s.excludeVideos) {
            assetIds.filter { id -> dao.get(id)?.isVideo == false }
        } else assetIds
        val n = ids.chunked(500).sumOf { dao.enqueue(it) }
        if (n > 0) kick(s.wifiOnly)
        return n
    }

    /** Encola todo lo que aún no tiene respaldo. */
    suspend fun enqueuePending(): Int {
        val s = settings.settings.first()
        val ids = dao.pendingAssetIds(includeVideos = !s.excludeVideos)
        return ids.chunked(500).sumOf { dao.enqueue(it) }
    }

    suspend fun hasQueued(): Boolean = dao.queued(1).isNotEmpty()

    suspend fun backupAllPending(): Int {
        val n = enqueuePending()
        kick(settings.settings.first().wifiOnly)
        return n
    }

    suspend fun retryFailed(): Int {
        val n = dao.retryAllFailed()
        kick(settings.settings.first().wifiOnly)
        return n
    }

    /** El usuario actuó (nuevas credenciales, permisos corregidos…): se levanta el bloqueo y se reanuda. */
    suspend fun resumeAfterUserAction() {
        settings.setQueueBlock(null)
        kick(settings.settings.first().wifiOnly)
    }

    /** Quita de la cola lo pendiente; nada se borra ni en el dispositivo ni en S3. */
    suspend fun cancelQueued() {
        scheduler.cancelBackup()
        dao.cancelQueued()
    }

    suspend fun pause() {
        settings.setPaused(true)
        scheduler.cancelBackup()
    }

    suspend fun resume() {
        settings.setPaused(false)
        settings.setQueueBlock(null)
        kick(settings.settings.first().wifiOnly)
    }

    private suspend fun kick(wifiOnly: Boolean) {
        if (!settings.settings.first().paused) scheduler.enqueueBackup(wifiOnly)
    }
}
