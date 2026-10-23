package com.s3vault.app

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal path-style S3 stand-in (PUT/HEAD/GET with Range/ListObjectsV2/DeleteObjects) backed by memory. It validates the
 * x-amz-checksum-sha256 header like S3 does (BadDigest) and requires a SigV4 Authorization header.
 * Single-object DELETE is intentionally not supported (405): the app only uses the batch DeleteObjects request, and
 * only when [allowDelete] is true, which mirrors an IAM policy that grants (or withholds) s3:DeleteObject.
 */
class FakeS3Dispatcher(private val bucket: String, private val allowedPrefix: String = "") : Dispatcher() {
    /**
     * [storageClass] and [listedSize] only affect what ListObjectsV2 reports (so a test can list 500 GiB without
     * holding it in memory); the bytes served by GET/HEAD are always [bytes].
     */
    class Obj(
        val bytes: ByteArray, val sha256B64: String, val contentType: String, val meta: Map<String, String>,
        val storageClass: String = "STANDARD", val listedSize: Long? = null,
    )

    val objects = ConcurrentHashMap<String, Obj>()
    val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile var denyAll = false

    /** False mimics the recommended read/write-only IAM policy: every DeleteObjects entry answers AccessDenied. */
    @Volatile var allowDelete = true

    /** Keys that were requested with GET (full or ranged), in order. */
    val getKeys = java.util.Collections.synchronizedList(mutableListOf<String>())
    val rangeHeaders = java.util.Collections.synchronizedList(mutableListOf<String>())
    val deleteBatches = java.util.Collections.synchronizedList(mutableListOf<List<String>>())

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
        if (request.method == "POST" && request.url.encodedQuery?.startsWith("delete") == true) return deleteObjects(request)

        return when (request.method) {
            "PUT" -> {
                val raw = request.body?.toByteArray() ?: ByteArray(0)
                // Over plain HTTP the SDK streams large bodies as aws-chunked (hex size, signature, data per chunk). Real S3 decodes it.
                val chunked = request.headers["Content-Encoding"]?.contains("aws-chunked") == true ||
                    request.headers["x-amz-content-sha256"]?.startsWith("STREAMING") == true
                val bytes = if (chunked) decodeAwsChunked(raw) else raw
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
                append("<Contents><Key>$k</Key><LastModified>2026-10-21T07:28:00.000Z</LastModified><ETag>&quot;e&quot;</ETag><Size>${o.listedSize ?: o.bytes.size.toLong()}</Size><StorageClass>${o.storageClass}</StorageClass></Contents>")
            }
            append("</ListBucketResult>")
        }
        return xml(200, body)
    }

    private fun decodeAwsChunked(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < raw.size) {
            var e = i
            while (e + 1 < raw.size && !(raw[e] == 0x0D.toByte() && raw[e + 1] == 0x0A.toByte())) e++
            val header = String(raw, i, e - i, Charsets.US_ASCII)
            val size = header.substringBefore(';').trim().toIntOrNull(16) ?: break
            i = e + 2
            if (size == 0) break
            out.write(raw, i, size)
            i += size + 2
        }
        return out.toByteArray()
    }

    /** DeleteObjects: parses the XML body, drops every key (missing keys count as deleted, like S3) and reports errors. */
    private fun deleteObjects(request: RecordedRequest): MockResponse {
        val keys = Regex("<Key>(.*?)</Key>").findAll(request.body?.utf8().orEmpty()).map { it.groupValues[1].xmlUnescape() }.toList()
        deleteBatches += keys
        val errors = StringBuilder()
        keys.forEach { k ->
            if (!allowDelete || !k.startsWith(allowedPrefix)) {
                errors.append("<Error><Key>${k.xmlEscape()}</Key><Code>AccessDenied</Code><Message>Access Denied</Message></Error>")
            } else {
                objects.remove(k)
            }
        }
        return xml(200, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><DeleteResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">$errors</DeleteResult>")
    }

    private fun String.xmlEscape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun String.xmlUnescape() = replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    private fun getObject(request: RecordedRequest, key: String): MockResponse {
        getKeys += key
        val o = objects[key] ?: return error(404, "NoSuchKey")
        val range = request.headers["Range"]
        if (range != null && range.startsWith("bytes=")) {
            rangeHeaders += range
            val spec = range.removePrefix("bytes=")
            val start = spec.substringBefore('-').toInt()
            val end = spec.substringAfter('-', "").toIntOrNull()?.coerceAtMost(o.bytes.size - 1) ?: (o.bytes.size - 1)
            return MockResponse.Builder().code(206).body(Buffer().write(o.bytes.copyOfRange(start, end + 1))).build()
        }
        return MockResponse.Builder().code(200).body(Buffer().write(o.bytes)).addHeader("Content-Type", o.contentType)
            .addHeader("x-amz-checksum-sha256", o.sha256B64).build()
    }
}
