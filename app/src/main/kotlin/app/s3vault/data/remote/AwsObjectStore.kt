package app.s3vault.data.remote

import app.s3vault.auth.AwsCredentials
import app.s3vault.auth.AwsCredentialsProvider
import app.s3vault.auth.CredentialsUnavailableException
import app.s3vault.auth.UnavailableReason
import app.s3vault.domain.model.S3Config
import app.s3vault.domain.model.ServerSideEncryption
import app.s3vault.domain.remote.FailureType
import app.s3vault.domain.remote.RemoteException
import app.s3vault.domain.remote.RemoteFailure
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.putObject
import aws.sdk.kotlin.services.s3.model.ChecksumMode
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.sdk.kotlin.services.s3.model.ServerSideEncryption as SdkSse
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.writeToOutputStream
import aws.smithy.kotlin.runtime.content.asByteStream
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.time.Instant as SdkInstant
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import aws.smithy.kotlin.runtime.time.fromEpochMilliseconds
import kotlinx.coroutines.CancellationException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Error de E/S del almacenamiento local durante una descarga (disco lleno, etc.). */
class LocalStorageException(cause: Throwable) : IOException("Error de almacenamiento local", cause)

/** Adaptador: el SDK pide credenciales a nuestra abstracción en cada solicitud. */
internal class SdkCredentialsAdapter(private val provider: AwsCredentialsProvider) : CredentialsProvider {
    override suspend fun resolve(attributes: Attributes): Credentials {
        val c: AwsCredentials = provider.credentials()
        return Credentials(
            accessKeyId = c.accessKeyId,
            secretAccessKey = c.secretAccessKey,
            sessionToken = c.sessionToken,
            expiration = c.expiration?.let { SdkInstant.fromEpochMilliseconds(it.toEpochMilli()) },
        )
    }
}

/** Crea el cliente S3. Solo Amazon S3: no hay endpoint configurable. */
object S3ClientFactory {
    fun create(config: S3Config, provider: AwsCredentialsProvider): S3Client = S3Client {
        region = config.region
        credentialsProvider = SdkCredentialsAdapter(provider)
    }
}

/** Implementación de [ObjectStore] sobre el AWS SDK for Kotlin (Android API 24+ / JVM). */
class AwsObjectStore(
    private val client: S3Client,
    private val config: S3Config,
) : ObjectStore {

    override suspend fun list(prefix: String, continuationToken: String?, maxKeys: Int): RemotePage = call(isHead = false) {
        val response = client.listObjectsV2 {
            bucket = config.bucket
            this.prefix = prefix
            this.continuationToken = continuationToken
            this.maxKeys = maxKeys
        }
        RemotePage(
            objects = response.contents.orEmpty().mapNotNull { o ->
                val key = o.key ?: return@mapNotNull null
                RemoteObjectInfo(key, o.size ?: 0L, o.lastModified?.epochMilliseconds, o.eTag)
            },
            nextToken = if (response.isTruncated == true) response.nextContinuationToken else null,
        )
    }

    override suspend fun head(key: String): RemoteHead? = try {
        call(isHead = true) {
            val r = client.headObject {
                bucket = config.bucket
                this.key = key
                checksumMode = ChecksumMode.Enabled
            }
            RemoteHead(
                key = key,
                sizeBytes = r.contentLength ?: 0L,
                lastModifiedMs = r.lastModified?.epochMilliseconds,
                etag = r.eTag,
                checksumSha256Base64 = r.checksumSha256,
                userMetadata = r.metadata.orEmpty(),
                contentType = r.contentType,
            )
        }
    } catch (e: RemoteException) {
        if (e.failure.type == FailureType.OBJECT_NOT_FOUND) null else throw e
    }

    override suspend fun put(
        key: String,
        source: UploadSource,
        contentType: String,
        metadata: Map<String, String>,
        sha256Base64: String,
        onProgress: (Long) -> Unit,
    ) {
        if (source.length > MAX_SINGLE_PUT_BYTES) {
            throw RemoteException(RemoteFailure(FailureType.TOO_LARGE, "Más de 5 GiB; multipart no está soportado"))
        }
        call(isHead = false) {
            client.putObject {
                bucket = config.bucket
                this.key = key
                this.contentType = contentType
                this.metadata = metadata
                checksumSha256 = sha256Base64
                config.sse.toSdk()?.let { serverSideEncryption = it }
                body = ReopenableByteStream(source, onProgress)
            }
        }
    }

    override suspend fun get(
        key: String,
        sink: OutputStream,
        offset: Long,
        ifMatchEtag: String?,
        onProgress: (Long) -> Unit,
    ): DownloadResult = call(isHead = false) {
        val request = GetObjectRequest {
            bucket = config.bucket
            this.key = key
            if (offset > 0) range = "bytes=$offset-"
            if (ifMatchEtag != null) ifMatch = ifMatchEtag
        }
        client.getObject(request) { response ->
            val body = response.body ?: throw IOException("Respuesta sin cuerpo")
            val counting = CountingOutputStream(LocalErrorsOutputStream(sink), onProgress)
            body.writeToOutputStream(counting)
            val total = response.contentRange?.substringAfterLast('/')?.toLongOrNull()
                ?: ((response.contentLength ?: 0L) + offset)
            DownloadResult(counting.count, total, response.eTag)
        }
    }

    override fun close() = client.close()

    private suspend fun <T> call(isHead: Boolean, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        throw e
    } catch (e: Throwable) {
        throw RemoteException(SdkErrors.classify(e, isHead), e)
    }

    private fun ServerSideEncryption.toSdk(): SdkSse? = headerValue?.let { SdkSse.fromValue(it) }

    companion object {
        /** Límite de un único PutObject. El MVP no usa multipart (evita cargas incompletas facturables). */
        const val MAX_SINGLE_PUT_BYTES = 5L * 1024 * 1024 * 1024
    }
}

/** Cuerpo reabrible: cada reintento del SDK vuelve a leer desde el origen. */
internal class ReopenableByteStream(
    private val source: UploadSource,
    private val onProgress: (Long) -> Unit,
) : ByteStream.SourceStream() {
    private val maxReported = java.util.concurrent.atomic.AtomicLong(0)

    override val contentLength: Long get() = source.length
    override val isOneShot: Boolean get() = false

    override fun readFrom() = ProgressInputStream(source.open()) { n ->
        // Tras un reintento el contador reinicia; solo se informa el máximo alcanzado.
        val prev = maxReported.get()
        if (n > prev && maxReported.compareAndSet(prev, n)) onProgress(n)
    }.asByteStream(source.length).readFrom()
}

internal class ProgressInputStream(
    private val inner: InputStream,
    private val onBytes: (Long) -> Unit,
) : FilterInputStream(inner) {
    private var total = 0L
    override fun read(): Int = super.read().also { if (it >= 0) { total++; onBytes(total) } }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) { total += it; onBytes(total) } }
}

private class CountingOutputStream(out: OutputStream, private val onProgress: (Long) -> Unit) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) { out.write(b); count++; onProgress(count) }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len; onProgress(count) }
}

/** Distingue fallos del disco local (no son errores de red) envolviéndolos. */
private class LocalErrorsOutputStream(private val out: OutputStream) : OutputStream() {
    override fun write(b: Int) = guard { out.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) = guard { out.write(b, off, len) }
    override fun flush() = guard { out.flush() }
    private inline fun guard(block: () -> Unit) = try { block() } catch (e: IOException) { throw LocalStorageException(e) }
}

/** Traduce excepciones del SDK (y de red/credenciales) a [RemoteFailure] sin filtrar secretos. */
internal object SdkErrors {
    fun classify(t: Throwable, isHead: Boolean): RemoteFailure {
        generateSequence(t) { it.cause }.take(8).forEach { c ->
            when (c) {
                is CredentialsUnavailableException -> return RemoteFailure(
                    when (c.reason) {
                        UnavailableReason.NOT_CONFIGURED -> FailureType.CREDENTIALS_MISSING
                        UnavailableReason.EXPIRED -> FailureType.CREDENTIALS_EXPIRED
                        UnavailableReason.RENEWAL_FAILED -> FailureType.CREDENTIALS_EXPIRED
                    },
                )

                is LocalStorageException -> return RemoteFailure(FailureType.UNKNOWN, "storage")
                is NotFound -> return RemoteFailure(FailureType.OBJECT_NOT_FOUND)
                is ServiceException -> return S3ErrorClassifier.classify(rawFrom(c, isHead))
            }
        }
        val network = generateSequence(t) { it.cause }.take(8).any { it is IOException }
        // Cualquier otro error del SDK sin respuesta del servicio es de transporte (DNS, TLS, timeout).
        return if (network || t.javaClass.name.startsWith("aws.smithy.kotlin.runtime.http") ||
            t.javaClass.name.contains("ClientException")
        ) RemoteFailure(FailureType.NETWORK) else RemoteFailure(FailureType.UNKNOWN, t.javaClass.simpleName)
    }

    private fun rawFrom(e: ServiceException, isHead: Boolean): RawS3Error {
        val meta = e.sdkErrorMetadata
        val response = meta.protocolResponse as? HttpResponse
        return RawS3Error(
            httpStatus = response?.status?.value,
            code = meta.errorCode,
            message = meta.errorMessage,
            bucketRegionHeader = response?.headers?.get("x-amz-bucket-region"),
            isHead = isHead,
        )
    }
}
