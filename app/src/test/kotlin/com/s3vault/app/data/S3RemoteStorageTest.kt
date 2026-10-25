package com.s3vault.app.data

import com.s3vault.app.data.remote.OperationContext
import com.s3vault.app.data.remote.S3ErrorClassifier
import com.s3vault.app.data.remote.S3ErrorFacts
import com.s3vault.app.data.remote.S3RemoteStorage
import com.s3vault.app.domain.AwsCredentials
import com.s3vault.app.domain.AwsCredentialsProvider
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.UploadRequest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

private class FakeCreds(var failWith: RemoteException? = null) : AwsCredentialsProvider {
    override val canRenewAutomatically = false
    override suspend fun getCredentials(): AwsCredentials {
        failWith?.let { throw it }
        return AwsCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", null, null)
    }
}

private const val XML = "application/xml"

class S3ErrorClassifierTest {
    private fun c(status: Int?, code: String?, msg: String? = null, region: String? = null, ctx: OperationContext = OperationContext.LIST) =
        S3ErrorClassifier.classify(S3ErrorFacts(status, code, msg, region), ctx)

    @Test fun `distinguishes the required error classes`() {
        assertEquals(RemoteErrorKind.BUCKET_NOT_FOUND, c(404, "NoSuchBucket").kind)
        assertEquals(RemoteErrorKind.WRONG_REGION, c(301, "PermanentRedirect", region = "eu-west-1").kind)
        assertEquals("eu-west-1", c(301, "PermanentRedirect", region = "eu-west-1").expectedRegion)
        assertEquals(RemoteErrorKind.WRONG_REGION, c(400, "AuthorizationHeaderMalformed", "the region 'us-east-1' is wrong; expecting 'sa-east-1'").kind)
        assertEquals("sa-east-1", c(400, "AuthorizationHeaderMalformed", "the region 'us-east-1' is wrong; expecting 'sa-east-1'").expectedRegion)
        assertEquals(RemoteErrorKind.CREDENTIALS_INVALID, c(403, "InvalidAccessKeyId").kind)
        assertEquals(RemoteErrorKind.CREDENTIALS_INVALID, c(403, "SignatureDoesNotMatch").kind)
        assertEquals(RemoteErrorKind.CREDENTIALS_EXPIRED, c(400, "ExpiredToken").kind)
        assertEquals(RemoteErrorKind.ACCESS_DENIED, c(403, "AccessDenied").kind)
        assertEquals(RemoteErrorKind.THROTTLED, c(503, "SlowDown").kind)
        assertEquals(RemoteErrorKind.QUOTA_EXCEEDED, c(400, "TooManyBuckets").kind)
        assertEquals(RemoteErrorKind.NETWORK, c(500, "InternalError").kind)
        assertEquals(RemoteErrorKind.OBJECT_NOT_FOUND, c(404, null, ctx = OperationContext.HEAD).kind)
        assertEquals(RemoteErrorKind.ACCESS_DENIED, c(403, null, ctx = OperationContext.HEAD).kind)
    }

    @Test fun `retryability`() {
        assertTrue(c(503, "SlowDown").retryable)
        assertTrue(c(500, "InternalError").retryable)
        assertFalse(c(403, "AccessDenied").retryable)
        assertFalse(c(404, "NoSuchBucket").retryable)
        assertFalse(c(400, "ExpiredToken").retryable)
    }

    @Test fun `messages never contain raw SDK text`() {
        val e = c(403, "AccessDenied", "secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        assertFalse(e.message!!.contains("wJalr"))
    }
}

// Robolectric provides real android.os.Build values, which the AWS SDK reads for its user-agent.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class S3RemoteStorageTest {
    private lateinit var server: MockWebServer
    private val creds = FakeCreds()
    private val assetId = "a".repeat(32)

    @Before fun start() {
        // The SDK builds a user-agent from JVM properties that Oracle's JDK may leave unset (Android always sets them).
        listOf("java.vendor.version", "java.vm.name", "java.vm.version", "java.vm.vendor", "java.runtime.version", "java.version")
            .forEach { if (System.getProperty(it) == null) System.setProperty(it, "test") }
        server = MockWebServer().also { it.start() }
    }
    @After fun stop() { server.close() }

    private fun storage(prefix: String = "mi/prefijo/", region: String = "eu-west-1") = S3RemoteStorage(
        S3Config(region, "my-bucket", prefix, AuthMethod.STATIC), creds, endpointOverride = server.url("/").toString().trimEnd('/'),
    )

    private fun error(status: Int, code: String, msg: String = "m", headers: Map<String, String> = emptyMap()): MockResponse {
        val b = MockResponse.Builder().code(status).addHeader("Content-Type", XML)
            .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>$code</Code><Message>$msg</Message><RequestId>R</RequestId></Error>")
        headers.forEach { (k, v) -> b.addHeader(k, v) }
        return b.build()
    }

    private fun listResponse(vararg keys: String, truncatedToken: String? = null, storageClass: String = "STANDARD") = MockResponse.Builder().code(200).addHeader("Content-Type", XML).body(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>my-bucket</Name><Prefix>p</Prefix>" +
            "<KeyCount>${keys.size}</KeyCount><MaxKeys>1000</MaxKeys><IsTruncated>${truncatedToken != null}</IsTruncated>" +
            (truncatedToken?.let { "<NextContinuationToken>$it</NextContinuationToken>" } ?: "") +
            keys.joinToString("") { "<Contents><Key>$it</Key><LastModified>2026-10-01T10:00:00.000Z</LastModified><ETag>&quot;e&quot;</ETag><Size>10</Size><StorageClass>$storageClass</StorageClass></Contents>" } +
            "</ListBucketResult>",
    ).build()

    private fun failure(block: suspend () -> Unit): RemoteException =
        runBlocking { runCatching { block() }.exceptionOrNull() as RemoteException }

    @Test fun `connection check lists under the photos folder of the configured prefix with max-keys 1`() = runBlocking {
        server.enqueue(listResponse())
        storage().checkConnection()
        val r = server.takeRequest()
        assertTrue(r.url.toString(), r.url.toString().contains("prefix=mi%2Fprefijo%2Fphotos%2F") || r.url.toString().contains("prefix=mi/prefijo/photos/"))
        assertTrue(r.url.toString().contains("max-keys=1"))
        assertNotNull(r.headers["Authorization"])
        assertTrue(r.headers["Authorization"]!!.startsWith("AWS4-HMAC-SHA256"))
    }

    @Test fun `bucket not found`() {
        server.enqueue(error(404, "NoSuchBucket"))
        assertEquals(RemoteErrorKind.BUCKET_NOT_FOUND, failure { storage().checkConnection() }.kind)
    }

    @Test fun `wrong region is detected with the expected region`() {
        server.enqueue(error(301, "PermanentRedirect", headers = mapOf("x-amz-bucket-region" to "eu-central-1")))
        val e = failure { storage(region = "us-east-1").checkConnection() }
        assertEquals(RemoteErrorKind.WRONG_REGION, e.kind)
        assertEquals("eu-central-1", e.expectedRegion)
    }

    @Test fun `invalid credentials`() {
        server.enqueue(error(403, "InvalidAccessKeyId"))
        assertEquals(RemoteErrorKind.CREDENTIALS_INVALID, failure { storage().checkConnection() }.kind)
    }

    @Test fun `expired credentials reported by S3`() {
        server.enqueue(error(400, "ExpiredToken"))
        assertEquals(RemoteErrorKind.CREDENTIALS_EXPIRED, failure { storage().checkConnection() }.kind)
    }

    @Test fun `expired credentials stop the request before it is sent`() {
        creds.failWith = RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED)
        val e = failure { storage().checkConnection() }
        assertEquals(RemoteErrorKind.CREDENTIALS_EXPIRED, e.kind)
        assertEquals(0, server.requestCount)
        creds.failWith = null
    }

    @Test fun `missing permissions`() {
        server.enqueue(error(403, "AccessDenied"))
        assertEquals(RemoteErrorKind.ACCESS_DENIED, failure { storage().checkConnection() }.kind)
    }

    @Test fun `network failure is retryable`() {
        val s = storage()
        server.close()
        val e = failure { s.checkConnection() }
        assertEquals(RemoteErrorKind.NETWORK, e.kind)
        assertTrue(e.retryable)
    }

    @Test fun `permission test reports each operation separately`() = runBlocking {
        server.enqueue(listResponse())                    // List ok
        server.enqueue(error(403, "AccessDenied"))        // Put denied
        val report = storage().testPermissions()
        assertEquals(listOf("s3:ListBucket", "s3:PutObject", "s3:GetObject"), report.checks.map { it.operation })
        assertEquals(listOf(true, false, false), report.checks.map { it.allowed })
        assertFalse(report.allAllowed)
    }

    @Test fun `listing returns every object under photos with its storage class and follows pagination`() = runBlocking {
        val keys = RemoteKeys("p")
        val k1 = keys.photoKey(assetId, "b".repeat(64), "a.jpg")
        val k2 = keys.photoKey("c".repeat(32), "d".repeat(64), "b.jpg")
        server.enqueue(listResponse(k1, "p/photos/garbage", truncatedToken = "tok", storageClass = "GLACIER_IR"))
        server.enqueue(listResponse(k2))
        val listed = storage("p").listPhotos()
        // Unrecognised keys are stored (and billed) too: the caller decides what to do with them.
        assertEquals(listOf(k1, "p/photos/garbage", k2), listed.map { it.key })
        assertEquals(listOf("GLACIER_IR", "GLACIER_IR", "STANDARD"), listed.map { it.storageClass })
        assertEquals("two ListObjectsV2 requests were needed", 2, (listed as com.s3vault.app.domain.PagedListing).requests)
        val first = server.takeRequest(); val second = server.takeRequest()
        assertTrue(first.url.toString().contains("photos"))
        assertTrue(second.url.toString().contains("continuation-token=tok"))
    }

    @Test fun `a listing that fails half way throws instead of returning a partial inventory`() {
        val keys = RemoteKeys("p")
        server.enqueue(listResponse(keys.photoKey(assetId, "b".repeat(64), "a.jpg"), truncatedToken = "tok"))
        server.enqueue(error(403, "AccessDenied"))
        assertEquals(RemoteErrorKind.ACCESS_DENIED, failure { storage("p").listPhotos() }.kind)
    }

    @Test fun `keys outside the configured prefix are refused without any request`() {
        val s = storage("alice")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { s.head("bob/photos/x") } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { s.download("bob/photos/x", ByteArrayOutputStream()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { s.upload(UploadRequest("bob/photos/x", { ByteArrayOutputStream().toByteArray().inputStream() }, 0, "image/jpeg", "", emptyMap())) {} }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `uploads outside photos folder are refused`() {
        val s = storage("alice")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { s.upload(UploadRequest("alice/other/file", { ByteArray(0).inputStream() }, 0, "image/jpeg", "", emptyMap())) {} }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `upload sends the sha256 checksum and the file bytes`() = runBlocking {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val b64 = Base64.getEncoder().encodeToString(digest)
        val hex = digest.joinToString("") { "%02x".format(it) }
        val key = RemoteKeys("p/").photoKey(assetId, hex, "pic.jpg")
        server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"etag1\"").addHeader("x-amz-checksum-sha256", b64).build())
        var progressMax = 0L
        val result = storage("p/").upload(
            UploadRequest(key, { bytes.inputStream() }, bytes.size.toLong(), "image/jpeg", b64, mapOf("asset-id" to assetId)),
        ) { progressMax = maxOf(progressMax, it) }
        val r = server.takeRequest()
        assertEquals("PUT", r.method)
        assertEquals(b64, r.headers["x-amz-checksum-sha256"])
        assertEquals(assetId, r.headers["x-amz-meta-asset-id"])
        assertEquals("image/jpeg", r.headers["Content-Type"])
        assertTrue(r.url.encodedPath.endsWith("/pic.jpg"))
        assertNotNull(r.headers["Authorization"])
        assertTrue("body contains payload", r.body!!.size >= bytes.size)
        assertEquals(b64, result.checksumSha256Base64)
        assertTrue(progressMax > 0)
    }

    @Test fun `head returns null when the object does not exist`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        val key = RemoteKeys("p/").photoKey(assetId, "e".repeat(64), "x.jpg")
        assertNull(storage("p/").head(key))
    }

    @Test fun `head exposes size and checksum`() = runBlocking {
        val key = RemoteKeys("p/").photoKey(assetId, "e".repeat(64), "x.jpg")
        server.enqueue(
            MockResponse.Builder().code(200).body("x".repeat(1234)).addHeader("ETag", "\"abc\"")
                .addHeader("x-amz-checksum-sha256", "AAAA").addHeader("x-amz-meta-asset-id", assetId).addHeader("Content-Type", "image/jpeg").build(),
        )
        val m = storage("p/").head(key)!!
        assertEquals(1234L, m.sizeBytes)
        assertEquals("AAAA", m.checksumSha256Base64)
        assertEquals(assetId, m.userMetadata["asset-id"])
        val r = server.takeRequest()
        assertEquals("ENABLED", r.headers["x-amz-checksum-mode"])
    }

    @Test fun `download streams the body and supports ranges`() = runBlocking {
        val key = RemoteKeys("p/").photoKey(assetId, "e".repeat(64), "x.jpg")
        server.enqueue(MockResponse.Builder().code(206).body("world").build())
        val out = ByteArrayOutputStream()
        val n = storage("p/").download(key, out, offset = 6, ifMatchEtag = "\"abc\"")
        assertEquals(5L, n)
        assertEquals("world", out.toString())
        val r = server.takeRequest()
        assertEquals("bytes=6-", r.headers["Range"])
        assertEquals("\"abc\"", r.headers["If-Match"])
    }

    // ------------------------------------------------------------------ previews and deletion

    private val version = "e".repeat(64)

    @Test fun `delete sends one DeleteObjects request and reports per-key failures`() = runBlocking {
        val keys = RemoteKeys("p/")
        val k1 = keys.photoKey(assetId, version, "a.jpg")
        val k2 = keys.photoKey("c".repeat(32), version, "b.jpg")
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", XML).body(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><DeleteResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                    "<Error><Key>$k2</Key><Code>AccessDenied</Code><Message>Access Denied</Message></Error></DeleteResult>",
            ).build(),
        )

        val report = storage("p/").delete(listOf(k1, k2))

        assertEquals(listOf(k1), report.deleted)
        assertEquals(mapOf(k2 to RemoteErrorKind.ACCESS_DENIED), report.failed)
        assertEquals(1, server.requestCount)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertTrue(r.url.toString(), r.url.encodedQuery!!.startsWith("delete"))
        val body = r.body!!.utf8()
        assertTrue(body, body.contains("<Key>$k1</Key>") && body.contains("<Key>$k2</Key>"))
        assertTrue("only failures come back", body.contains("<Quiet>true</Quiet>"))
        assertTrue(r.headers["Authorization"]!!.startsWith("AWS4-HMAC-SHA256"))
    }

    @Test fun `delete refuses anything that is not a photo or preview key without sending a request`() {
        val keys = RemoteKeys("alice/")
        val s = storage("alice/")
        listOf(
            "bob/photos/$assetId/$version/x.jpg", // another prefix
            "alice/.s3vault/albums.json", // the album manifest is not deletable from the app
            "alice/.s3vault/connection-check.txt",
            "alice/photos/garbage",
            "alice/photos/../../bob/x",
        ).forEach { bad ->
            assertThrows(bad, IllegalArgumentException::class.java) { runBlocking { s.delete(listOf(keys.photoKey(assetId, version, "ok.jpg"), bad)) } }
        }
        val tooMany = (0..com.s3vault.app.domain.RemoteStorage.MAX_DELETE_BATCH).map { keys.photoKey("%032x".format(it), version, "x.jpg") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { s.delete(tooMany) } }
        assertEquals(0, server.requestCount)
    }

    @Test fun `delete with no keys sends nothing`() = runBlocking {
        val r = storage().delete(emptyList())
        assertTrue(r.deleted.isEmpty() && r.failed.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun `delete can delete previews`() = runBlocking {
        val keys = RemoteKeys("p/")
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", XML).body("<DeleteResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"></DeleteResult>").build())
        val report = storage("p/").delete(listOf(keys.thumbnailKey(version)))
        assertEquals(listOf(keys.thumbnailKey(version)), report.deleted)
    }

    @Test fun `a request denied by the IAM policy is reported as access denied`() {
        server.enqueue(error(403, "AccessDenied"))
        val key = RemoteKeys("p/").photoKey(assetId, version, "a.jpg")
        assertEquals(RemoteErrorKind.ACCESS_DENIED, failure { storage("p/").delete(listOf(key)) }.kind)
    }

    @Test fun `readBytes asks only for the first bytes with a Range request`() = runBlocking {
        val key = RemoteKeys("p/").photoKey(assetId, version, "a.jpg")
        server.enqueue(MockResponse.Builder().code(206).body("head-of-file").build())

        val bytes = storage("p/").readBytes(key, maxBytes = 131_072)

        assertEquals("head-of-file", String(bytes!!))
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("bytes=0-131071", r.headers["Range"])
    }

    @Test fun `readBytes without a limit sends no Range header`() = runBlocking {
        val key = RemoteKeys("p/").thumbnailKey(version)
        server.enqueue(MockResponse.Builder().code(200).body("jpeg").build())
        assertEquals("jpeg", String(storage("p/").readBytes(key)!!))
        assertNull(server.takeRequest().headers["Range"])
    }

    @Test fun `readBytes returns null for a missing object and throws for a forbidden one`() {
        val key = RemoteKeys("p/").thumbnailKey(version)
        server.enqueue(error(404, "NoSuchKey"))
        assertNull(runBlocking { storage("p/").readBytes(key) })
        server.enqueue(error(403, "AccessDenied"))
        assertEquals(RemoteErrorKind.ACCESS_DENIED, failure { storage("p/").readBytes(key) }.kind)
    }

    @Test fun `putThumbnail stores a small JPEG under the thumbnails folder`() = runBlocking {
        val keys = RemoteKeys("p/")
        server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"t\"").build())

        storage("p/").putThumbnail(keys.thumbnailKey(version), ByteArray(50) { 1 })

        val r = server.takeRequest()
        assertEquals("PUT", r.method)
        assertTrue(r.url.encodedPath.endsWith("/.s3vault/thumbs/$version.jpg"))
        assertEquals("image/jpeg", r.headers["Content-Type"])
        assertTrue(r.body!!.size >= 50)
    }

    @Test fun `putThumbnail refuses photo keys and keys outside the prefix`() {
        val keys = RemoteKeys("p/")
        val s = storage("p/")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { s.putThumbnail(keys.photoKey(assetId, version, "a.jpg"), ByteArray(1)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { s.putThumbnail("q/.s3vault/thumbs/$version.jpg", ByteArray(1)) } }
        assertEquals(0, server.requestCount)
    }
}
