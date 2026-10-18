package app.s3vault.data.repo

import app.s3vault.data.local.AlbumEntity
import app.s3vault.data.local.AlbumItemEntity
import app.s3vault.data.local.AlbumSummary
import app.s3vault.data.local.AppDatabase
import app.s3vault.data.local.toDomain
import app.s3vault.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Álbumes virtuales: solo agrupan referencias. Quitar o borrar un álbum nunca borra archivos. */
class AlbumRepository(private val db: AppDatabase) {
    val albums: Flow<List<AlbumSummary>> = db.albums().observeSummaries()

    fun album(id: Long): Flow<AlbumEntity?> = db.albums().observe(id)

    fun items(albumId: Long): Flow<List<MediaItem>> = db.media().observeAlbum(albumId).map { l -> l.map { it.toDomain() } }

    fun albumIdsOf(assetId: String): Flow<List<Long>> = db.albums().observeAlbumIdsOf(assetId)

    suspend fun create(name: String): Long =
        db.albums().insert(AlbumEntity(name = name.trim().take(MAX_NAME), createdAtMs = System.currentTimeMillis()))

    suspend fun rename(id: Long, name: String) = db.albums().rename(id, name.trim().take(MAX_NAME))

    suspend fun delete(id: Long) = db.albums().delete(id)

    suspend fun add(albumId: Long, assetIds: List<String>) {
        val now = System.currentTimeMillis()
        db.albums().addItems(assetIds.map { AlbumItemEntity(albumId, it, now) })
    }

    suspend fun remove(albumId: Long, assetIds: List<String>) = db.albums().removeItems(albumId, assetIds)

    private companion object { const val MAX_NAME = 80 }
}
