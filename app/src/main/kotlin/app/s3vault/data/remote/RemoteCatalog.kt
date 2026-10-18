package app.s3vault.data.remote

import app.s3vault.domain.remote.RemoteKeys
import app.s3vault.domain.repo.RemoteIndex

/** Objeto remoto ya interpretado (clave `{prefix}photos/{assetId}/{versionId}/{name}`). */
data class RemoteEntry(
    val key: String,
    val assetId: String,
    val versionId: String,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long?,
    val etag: String?,
)

/** Caché local del catálogo remoto. También sirve de índice para deduplicar por contenido. */
interface RemoteCatalogStore : RemoteIndex {
    /** Sustituye el catálogo con el resultado completo de un LIST. */
    suspend fun replaceAll(entries: List<RemoteEntry>, refreshedAtMs: Long)
    suspend fun lastRefreshMs(): Long?
}

/**
 * El catálogo remoto se deriva de los propios objetos (sin manifiesto, sin base de datos remota,
 * sin problemas de concurrencia ni de escrituras interrumpidas). Costo: una solicitud LIST por
 * cada 1000 objetos, solo cuando el usuario actualiza la biblioteca remota.
 */
class RemoteCatalog(
    private val store: ObjectStore,
    private val keys: RemoteKeys,
    private val cache: RemoteCatalogStore,
    private val nowMs: () -> Long,
    private val maxPages: Int = 1000,
) {
    suspend fun refresh(onPage: (Int) -> Unit = {}): List<RemoteEntry> {
        val entries = ArrayList<RemoteEntry>()
        var token: String? = null
        var pages = 0
        do {
            val page = store.list(keys.photosRoot, token, 1000)
            pages++
            for (o in page.objects) {
                val p = keys.parse(o.key) ?: continue // ignora cualquier objeto ajeno a la app
                entries += RemoteEntry(o.key, p.assetId, p.versionId, p.name, o.sizeBytes, o.lastModifiedMs, o.etag)
            }
            onPage(pages)
            token = page.nextToken
        } while (token != null && pages < maxPages)
        cache.replaceAll(entries, nowMs())
        return entries
    }
}
