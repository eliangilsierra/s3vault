package com.s3vault.app.data.remote

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.ChecksumAlgorithm
import aws.sdk.kotlin.services.s3.model.ChecksumMode
import aws.sdk.kotlin.services.s3.model.Delete
import aws.sdk.kotlin.services.s3.model.DeleteObjectsRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.content.toInputStream
import aws.smithy.kotlin.runtime.io.source
import aws.smithy.kotlin.runtime.net.url.Url
import aws.smithy.kotlin.runtime.time.Instant as SdkInstant
import com.s3vault.app.domain.AwsCredentialsProvider
import com.s3vault.app.domain.DeleteReport
import com.s3vault.app.domain.PagedListing
import com.s3vault.app.domain.PermissionCheck
import com.s3vault.app.domain.PermissionReport
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.RemoteListing
import com.s3vault.app.domain.RemoteObjectMetadata
import com.s3vault.app.domain.RemoteStorage
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.UploadRequest
import com.s3vault.app.domain.UploadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/** Adapts our credentials abstraction to the SDK. Called by the SDK for each signed request. */
internal class SdkCredentialsAdapter(private val provider: AwsCredentialsProvider) : CredentialsProvider {
    override suspend fun resolve(attributes: Attributes): Credentials {
        val c = provider.getCredentials()
        return Credentials(
            accessKeyId = c.accessKeyId,
            secretAccessKey = c.secretAccessKey,
            sessionToken = c.sessionToken,
            expiration = c.expiration?.let { SdkInstant.fromEpochSeconds(it.epochSecond) },
        )
    }
}

/**
 * Amazon S3 access through the AWS SDK for Kotlin (official Android support, SigV4 signing done by the SDK,
 * HTTPS only, platform TLS validation).
 *
 * Every key is checked against the configured prefix before use, independently of the UI. The only destructive
 * operation is [delete], restricted to photo and thumbnail keys.
 *
 * [endpointOverride] exists solely for tests against a local mock server; production code never sets it.
 */
class S3RemoteStorage(
    private val config: S3Config,
    credentials: AwsCredentialsProvider,
    endpointOverride: String? = null,
) : RemoteStorage {

    private val keys = RemoteKeys(config.prefix)

    // Built lazily so construction (user-agent discovery etc.) happens on the IO dispatcher, never on the caller's (main) thread.
    private val clientLazy = lazy { buildClient(config, credentials, endpointOverride) }
    private val client: S3Client get() = clientLazy.value

    private fun buildClient(config: S3Config, credentials: AwsCredentialsProvider, endpointOverride: String?): S3Client = S3Client {
        region = config.region
        credentialsProvider = SdkCredentialsAdapter(credentials)
        if (endpointOverride != null) {
            endpointUrl = Url.parse(endpointOverride)
            forcePathStyle = true
        }
        retryStrategy {
            maxAttempts = 3
        }
    }

    /** Closing sockets is network work too: never do it on the caller's (possibly main) thread. */
    fun close() {
        if (!clientLazy.isInitialized()) return
        val c = client
        closeScope.launch { runCatching { c.close() } }
    }

    private fun checkKey(key: String) {
        require(keys.isWithinPrefix(key)) { "Key outside configured prefix" }
    }

    override suspend fun checkConnection() {
        guarded(OperationContext.LIST) {
            client.listObjectsV2(
                ListObjectsV2Request {
                    bucket = config.bucket
                    // Same prefix as the real listing, so a policy condition on s3:prefix behaves identically.
                    prefix = keys.photosPrefix
                    maxKeys = 1
                },
            )
        }
    }

    override suspend fun testPermissions(): PermissionReport {
        val checks = mutableListOf<PermissionCheck>()
        suspend fun run(op: String, ctx: OperationContext, block: suspend () -> Unit): Boolean = try {
            guarded(ctx) { block() }
            checks += PermissionCheck(op, true, null)
            true
        } catch (e: RemoteException) {
            checks += PermissionCheck(op, false, e.kind)
            false
        }

        run("s3:ListBucket", OperationContext.LIST) {
            client.listObjectsV2(ListObjectsV2Request { bucket = config.bucket; prefix = keys.photosPrefix; maxKeys = 1 })
        }
        val token = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val body = "s3vault connection check $token\n".toByteArray()
        val key = keys.connectionCheckKey
        val put = run("s3:PutObject", OperationContext.PUT) {
            client.putObject(
                PutObjectRequest {
                    bucket = config.bucket
                    this.key = key
                    this.body = ByteStream.fromBytes(body)
                    contentType = "text/plain"
                },
            )
        }
        if (put) {
            run("s3:GetObject", OperationContext.GET) {
                val read = client.getObject(GetObjectRequest { bucket = config.bucket; this.key = key }) { it.body?.toByteArray() ?: ByteArray(0) }
                if (!read.contentEquals(body)) throw RemoteException(RemoteErrorKind.INTEGRITY_MISMATCH)
            }
        } else {
            checks += PermissionCheck("s3:GetObject", false, null)
        }
        return PermissionReport(checks)
    }

    override suspend fun listPhotos(): List<RemoteListing> {
        val out = ArrayList<RemoteListing>()
        var token: String? = null
        var requests = 0
        do {
            val resp = guarded(OperationContext.LIST) {
                client.listObjectsV2(
                    ListObjectsV2Request {
                        bucket = config.bucket
                        prefix = keys.photosPrefix
                        continuationToken = token
                    },
                )
            }
            requests++
            resp.contents.orEmpty().forEach { o ->
                val k = o.key ?: return@forEach
                out += RemoteListing(k, o.size ?: 0L, o.lastModified?.epochSeconds?.times(1000) ?: 0L, o.storageClass?.value)
            }
            token = if (resp.isTruncated == true) resp.nextContinuationToken else null
        } while (token != null)
        return PagedListing(out, requests)
    }

    override suspend fun head(key: String): RemoteObjectMetadata? {
        checkKey(key)
        return try {
            val r = guarded(OperationContext.HEAD) {
                client.headObject(HeadObjectRequest { bucket = config.bucket; this.key = key; checksumMode = ChecksumMode.Enabled })
            }
            RemoteObjectMetadata(
                key = key,
                sizeBytes = r.contentLength ?: 0L,
                lastModifiedMs = r.lastModified?.epochSeconds?.times(1000),
                etag = r.eTag,
                checksumSha256Base64 = r.checksumSha256,
                contentType = r.contentType,
                userMetadata = r.metadata.orEmpty(),
            )
        } catch (e: RemoteException) {
            if (e.kind == RemoteErrorKind.OBJECT_NOT_FOUND) null else throw e
        }
    }

    override suspend fun upload(request: UploadRequest, onProgress: (Long) -> Unit): UploadResult {
        checkKey(request.key)
        require(keys.isPhotoKey(request.key)) { "Uploads are only allowed under photos/" }
        val resp = guarded(OperationContext.PUT) {
            client.putObject(
                PutObjectRequest {
                    bucket = config.bucket
                    key = request.key
                    body = ProgressStream(request.openStream, request.sizeBytes, onProgress)
                    contentType = request.contentType
                    contentLength = request.sizeBytes
                    checksumAlgorithm = ChecksumAlgorithm.Sha256
                    checksumSha256 = request.sha256Base64
                    metadata = request.metadata
                },
            )
        }
        return UploadResult(resp.eTag, resp.checksumSha256)
    }

    override suspend fun download(
        key: String,
        sink: OutputStream,
        offset: Long,
        ifMatchEtag: String?,
        onProgress: (Long) -> Unit,
    ): Long {
        checkKey(key)
        return guarded(OperationContext.GET) {
            client.getObject(
                GetObjectRequest {
                    bucket = config.bucket
                    this.key = key
                    if (offset > 0) range = "bytes=$offset-"
                    if (ifMatchEtag != null) ifMatch = ifMatchEtag
                },
            ) { resp ->
                var written = 0L
                resp.body?.let { body ->
                    val channel = body.toInputStream()
                    channel.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            written += n
                            onProgress(offset + written)
                        }
                    }
                }
                written
            }
        }
    }

    override suspend fun readBytes(key: String, maxBytes: Long?): ByteArray? {
        checkKey(key)
        require(maxBytes == null || maxBytes > 0) { "maxBytes must be positive" }
        return try {
            guarded(OperationContext.GET) {
                client.getObject(
                    GetObjectRequest {
                        bucket = config.bucket
                        this.key = key
                        if (maxBytes != null) range = "bytes=0-${maxBytes - 1}"
                    },
                ) { resp -> resp.body?.toByteArray() ?: ByteArray(0) }
            }
        } catch (e: RemoteException) {
            if (e.kind == RemoteErrorKind.OBJECT_NOT_FOUND) null else throw e
        }
    }

    override suspend fun putThumbnail(key: String, bytes: ByteArray) {
        checkKey(key)
        require(keys.isThumbnailKey(key)) { "Thumbnails are only allowed under the thumbnails folder" }
        guarded(OperationContext.PUT) {
            client.putObject(
                PutObjectRequest {
                    bucket = config.bucket
                    this.key = key
                    body = ByteStream.fromBytes(bytes)
                    contentType = "image/jpeg"
                },
            )
        }
    }

    override suspend fun delete(keys: List<String>): DeleteReport {
        if (keys.isEmpty()) return DeleteReport(emptyList(), emptyMap())
        require(keys.size <= RemoteStorage.MAX_DELETE_BATCH) { "At most ${RemoteStorage.MAX_DELETE_BATCH} keys per request" }
        keys.forEach {
            checkKey(it)
            require(this.keys.isPhotoKey(it) || this.keys.isThumbnailKey(it)) { "Only photo and thumbnail keys can be deleted" }
        }
        val resp = guarded(OperationContext.DELETE) {
            client.deleteObjects(
                DeleteObjectsRequest {
                    bucket = config.bucket
                    delete = Delete {
                        objects = keys.map { k -> ObjectIdentifier { key = k } }
                        quiet = true // only failures are reported back
                    }
                },
            )
        }
        val failed = LinkedHashMap<String, RemoteErrorKind>()
        resp.errors.orEmpty().forEach { err ->
            val k = err.key ?: return@forEach
            failed[k] = S3ErrorClassifier.classify(S3ErrorFacts(null, err.code, err.message, null), OperationContext.DELETE).kind
        }
        return DeleteReport(keys.filter { it !in failed }, failed)
    }

    /** Runs [block] and maps every failure into a [RemoteException] without leaking SDK text. */
    private suspend fun <T> guarded(ctx: OperationContext, block: suspend () -> T): T {
        try {
            // All network and blocking work is pinned to IO, whatever dispatcher the caller (e.g. a ViewModel) uses.
            return withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            throw e
        } catch (e: Throwable) {
            throw translate(e, ctx)
        }
    }

    @OptIn(aws.smithy.kotlin.runtime.InternalApi::class)
    private fun translate(e: Throwable, ctx: OperationContext): RemoteException {
        generateSequence(e) { it.cause }.take(8).firstOrNull { it is RemoteException }?.let { return it as RemoteException }
        val svc = generateSequence(e) { it.cause }.take(8).firstOrNull { it is ServiceException }
        if (svc is ServiceException) {
            val md = svc.sdkErrorMetadata
            val protocolResponse = md.attributes.getOrNull(aws.smithy.kotlin.runtime.ServiceErrorMetadata.ProtocolResponse)
            val status = (protocolResponse as? aws.smithy.kotlin.runtime.http.response.HttpResponse)?.status?.value
            val region = (protocolResponse as? aws.smithy.kotlin.runtime.http.response.HttpResponse)?.headers?.get("x-amz-bucket-region")
            return S3ErrorClassifier.classify(
                S3ErrorFacts(status, md.errorCode, md.errorMessage ?: svc.message, region),
                ctx,
            )
        }
        if (S3ErrorClassifier.isNetworkLevel(e)) return RemoteException(RemoteErrorKind.NETWORK, detail = e::class.java.simpleName)
        return RemoteException(RemoteErrorKind.UNKNOWN, detail = e::class.java.simpleName)
    }
}

private val closeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

/** Replayable stream over a local file/URI that reports bytes read to the caller. */
internal class ProgressStream(
    private val open: () -> InputStream,
    private val size: Long,
    private val onProgress: (Long) -> Unit,
) : ByteStream.SourceStream() {
    override val contentLength: Long = size
    override val isOneShot: Boolean = false

    override fun readFrom(): aws.smithy.kotlin.runtime.io.SdkSource {
        var total = 0L
        val raw = open()
        val counting = object : InputStream() {
            override fun read(): Int = raw.read().also { if (it >= 0) { total++; onProgress(total) } }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                raw.read(b, off, len).also { if (it > 0) { total += it; onProgress(total) } }
            override fun close() = raw.close()
        }
        return counting.source()
    }
}
