package app.s3vault.data.remote

import app.s3vault.domain.remote.RemoteKeys
import java.io.OutputStream

/**
 * Envoltorio que impone el prefijo configurado en TODA operación, con independencia de lo que
 * valide la interfaz. Una clave fuera del prefijo lanza [IllegalArgumentException] antes de
 * que salga ninguna solicitud de red.
 */
class ScopedObjectStore(private val delegate: ObjectStore, private val keys: RemoteKeys) : ObjectStore {

    override suspend fun list(prefix: String, continuationToken: String?, maxKeys: Int): RemotePage {
        require(prefix.startsWith(keys.prefix) && keys.isInScope(prefix.trimEnd('/') + "/")) {
            "El prefijo de listado está fuera del prefijo configurado"
        }
        return delegate.list(prefix, continuationToken, maxKeys)
    }

    override suspend fun head(key: String): RemoteHead? {
        keys.requireInScope(key)
        return delegate.head(key)
    }

    override suspend fun put(
        key: String,
        source: UploadSource,
        contentType: String,
        metadata: Map<String, String>,
        sha256Base64: String,
        onProgress: (Long) -> Unit,
    ) {
        keys.requireInScope(key)
        delegate.put(key, source, contentType, metadata, sha256Base64, onProgress)
    }

    override suspend fun get(
        key: String,
        sink: OutputStream,
        offset: Long,
        ifMatchEtag: String?,
        onProgress: (Long) -> Unit,
    ): DownloadResult {
        keys.requireInScope(key)
        return delegate.get(key, sink, offset, ifMatchEtag, onProgress)
    }

    override fun close() = delegate.close()
}
