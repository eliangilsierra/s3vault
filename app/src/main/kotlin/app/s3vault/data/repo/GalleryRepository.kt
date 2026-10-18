package app.s3vault.data.repo

import android.content.Context
import app.s3vault.data.local.AppDatabase
import app.s3vault.data.local.toDomain
import app.s3vault.data.local.toEntity
import app.s3vault.data.local.media.MediaStoreScanner
import app.s3vault.domain.model.MediaItem
import app.s3vault.settings.SettingsRepository
import app.s3vault.sync.MediaReconciler
import app.s3vault.sync.ReconcileResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Galería local: Room es la fuente de la UI; MediaStore solo se consulta para reindexar. */
class GalleryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val scanner: MediaStoreScanner,
    @Suppress("unused") private val settings: SettingsRepository,
) {
    private val scanMutex = Mutex()

    val items: Flow<List<MediaItem>> = db.media().observeGallery().map { list -> list.map { it.toDomain() } }

    fun item(assetId: String): Flow<MediaItem?> = db.media().observeOne(assetId).map { it?.toDomain() }

    /**
     * Reindexa MediaStore. Con acceso parcial (Android 14) la ausencia de un archivo no significa que
     * se haya borrado, así que no se marca ninguno como desaparecido. Sin ningún permiso no se hace nada.
     * Funciona sin conexión: no usa la red.
     */
    suspend fun refreshIfPermitted(): ReconcileResult? {
        if (!MediaPermissions.hasAnyAccess(context)) return null
        return refresh(trustAbsence = MediaPermissions.hasFullAccess(context))
    }

    suspend fun refresh(trustAbsence: Boolean): ReconcileResult = scanMutex.withLock {
        val scanned = scanner.scan()
        val existing = db.media().getAll().map { it.toDomain() }
        val result = MediaReconciler.reconcile(
            existing = existing,
            scanned = scanned,
            newAssetId = { UUID.randomUUID().toString().replace("-", "") },
            trustAbsence = trustAbsence,
        )
        db.media().applyScan(result.upserts.map { it.toEntity() }, result.deletes)
        result
    }
}
