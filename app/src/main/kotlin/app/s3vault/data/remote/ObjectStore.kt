package app.s3vault.data.remote

import java.io.InputStream
import java.io.OutputStream

/** Resultado de listar un prefijo (una página). */
data class RemotePage(val objects: List<RemoteObjectInfo>, val nextToken: String?)

data class RemoteObjectInfo(
    val key: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long?,
    val etag: String?,
)

/** Respuesta de un HEAD: metadatos y checksum almacenado por S3. */
data class RemoteHead(
    val key: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long?,
    val etag: String?,
    /** Checksum SHA-256 (base64) calculado/validado por S3, si el objeto lo tiene. */
    val checksumSha256Base64: String?,
    val userMetadata: Map<String, String>,
    val contentType: String?,
)

/** Origen de bytes reabrible: los reintentos del SDK necesitan releer el contenido. */
interface UploadSource {
    val length: Long
    fun open(): InputStream
}

/** Resultado de una descarga. [totalSizeBytes] es el tamaño total del objeto (no del tramo). */
data class DownloadResult(val bytesWritten: Long, val totalSizeBytes: Long, val etag: String?)

/**
 * Acceso mínimo a Amazon S3 que necesita la app. Todos los métodos lanzan
 * [app.s3vault.domain.remote.RemoteException] con un fallo ya clasificado y saneado.
 * No existe operación de borrado: el MVP nunca elimina objetos remotos.
 */
interface ObjectStore {
    /** Lista una página bajo [prefix]. Requiere `s3:ListBucket` con ese prefijo. */
    suspend fun list(prefix: String, continuationToken: String? = null, maxKeys: Int = 1000): RemotePage

    /** HEAD de un objeto; `null` solo si no existe. Requiere `s3:GetObject`. */
    suspend fun head(key: String): RemoteHead?

    /** PUT con checksum SHA-256 verificado por S3. Requiere `s3:PutObject`. */
    suspend fun put(
        key: String,
        source: UploadSource,
        contentType: String,
        metadata: Map<String, String>,
        sha256Base64: String,
        onProgress: (bytesSent: Long) -> Unit = {},
    )

    /**
     * Descarga el objeto (o desde [offset]) escribiendo en [sink]. Con [ifMatchEtag] S3 responde
     * 412 si el objeto cambió, lo que se informa como `OBJECT_CHANGED`.
     */
    suspend fun get(
        key: String,
        sink: OutputStream,
        offset: Long = 0,
        ifMatchEtag: String? = null,
        onProgress: (bytesWritten: Long) -> Unit = {},
    ): DownloadResult

    fun close()
}
