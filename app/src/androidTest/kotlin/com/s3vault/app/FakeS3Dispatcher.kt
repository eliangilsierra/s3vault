package com.s3vault.app

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal path-style S3 stand-in (PUT/HEAD/GET/ListObjectsV2) backed by memory. It validates the
 * x-amz-checksum-sha256 header like S3 does (BadDigest) and requires a SigV4 Authorization header.
 * There is intentionally no DELETE handler: any DELETE would answer 405 and fail the test.
 */
class FakeS3Dispatcher(private val bucket: String, private val allowedPrefix: String = "") : Dispatcher() {
    class Obj(val bytes: ByteArray, val sha256B64: String, val contentType: String, val meta: Map<String, String>)

    val objects = ConcurrentHashMap<String, Obj>()
    val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile var denyAll = false

    private fun xml(status: Int, body: String) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/xml").body(body).build()

    private fun error(status: Int, code: String) =
        xml(status, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>$code</Code><Message>$code</Message><RequestId>R</RequestId></Error>")

    override fun dispatch(request: RecordedRequest): MockResponse {
        methods += request.method!!
        val auth = request.headers["Authorization"]
        if (auth == null || !auth.startsWith("AWS4-HMAC-SHA256")) return error(403, "AccessDenied")
        if (denyAll) return error(403, "AccessDenied")
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        if (segments.firstOrNull() != bucket) return error(404, "NoSuchBucket")
        val key = segments.drop(1).joinToString("/")
        if (key.isNotEmpty() && !key.startsWith(allowedPrefix)) return error(403, "AccessDenied")

        return when (request.method) {
            "PUT" -> {
                val bytes = request.body?.toByteArray() ?: ByteArray(0)
                val actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
                val claimed = request.headers["x-amz-checksum-sha256"]
                if (claimed != null && claimed != actual) return error(400, "BadDigest")
                val meta = request.headers.filter { it.first.startsWith("x-amz-meta-") }.associate { it.first.removePrefix("x-amz-meta-") to it.second }
                objects[key] = Obj(bytes, actual, request.headers["Content-Type"] ?: "application/octet-stream", meta)
                MockResponse.Builder().code(200).addHeader("ETag", "\"${actual.take(8)}\"").addHeader("x-amz-checksum-sha256", actual).build()
            }
            "HEAD" -> {
                val o = objects[key] ?: return MockResponse.Builder().code(404).build()
                val b = MockResponse.Builder().code(200).body(Buffer().write(o.bytes))
                    .addHeader("ETag", "\"${o.sha256B64.take(8)}\"").addHeader("x-amz-checksum-sha256", o.sha256B64)
                    .addHeader("Content-Type", o.contentType).addHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
                o.meta.forEach { (k, v) -> b.addHeader("x-amz-meta-$k", v) }
                b.build()
            }
            "GET" -> if (request.url.queryParameter("list-type") == "2") list(request) else getObject(request, key)
            else -> MockResponse.Builder().code(405).build()
        }
    }

    private fun list(request: RecordedRequest): MockResponse {
        val prefix = request.url.queryParameter("prefix").orEmpty()
        val max = request.url.queryParameter("max-keys")?.toIntOrNull() ?: 1000
        val matches = objects.entries.filter { it.key.startsWith(prefix) }.sortedBy { it.key }.take(max)
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
            append("<Name>$bucket</Name><Prefix>$prefix</Prefix><KeyCount>${matches.size}</KeyCount><MaxKeys>$max</MaxKeys><IsTruncated>false</IsTruncated>")
            matches.forEach { (k, o) ->
                append("<Contents><Key>$k</Key><LastModified>2026-10-21T07:28:00.000Z</LastModified><ETag>&quot;e&quot;</ETag><Size>${o.bytes.size}</Size><StorageClass>STANDARD</StorageClass></Contents>")
            }
            append("</ListBucketResult>")
        }
        return xml(200, body)
    }

    private fun getObject(request: RecordedRequest, key: String): MockResponse {
        val o = objects[key] ?: return error(404, "NoSuchKey")
        val range = request.headers["Range"]
        if (range != null && range.startsWith("bytes=")) {
            val start = range.removePrefix("bytes=").substringBefore('-').toInt()
            return MockResponse.Builder().code(206).body(Buffer().write(o.bytes.copyOfRange(start, o.bytes.size))).build()
        }
        return MockResponse.Builder().code(200).body(Buffer().write(o.bytes)).addHeader("Content-Type", o.contentType)
            .addHeader("x-amz-checksum-sha256", o.sha256B64).build()
    }
}
