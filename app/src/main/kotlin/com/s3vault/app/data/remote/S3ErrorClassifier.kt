package com.s3vault.app.data.remote

import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import java.io.IOException

/** Raw facts extracted from an S3 failure, independent from SDK types so the mapping is unit-testable. */
data class S3ErrorFacts(
    val httpStatus: Int?,
    val errorCode: String?,
    val message: String?,
    /** Value of the `x-amz-bucket-region` response header, when present. */
    val bucketRegionHeader: String?,
)

enum class OperationContext { LIST, PUT, GET, HEAD, DELETE }

object S3ErrorClassifier {
    private val expectingRegion = Regex("expecting '([a-z0-9-]+)'", RegexOption.IGNORE_CASE)
    private val regionWord = Regex("\\bregion\\b", RegexOption.IGNORE_CASE)

    fun classify(f: S3ErrorFacts, ctx: OperationContext): RemoteException {
        val code = f.errorCode.orEmpty()
        val status = f.httpStatus
        val detail = listOfNotNull(status?.let { "HTTP $it" }, code.ifBlank { null }).joinToString(" ").ifBlank { null }

        fun kind(k: RemoteErrorKind, region: String? = null) = RemoteException(k, expectedRegion = region, detail = detail)

        return when {
            code in setOf("ExpiredToken", "TokenRefreshRequired", "ExpiredTokenException") ->
                kind(RemoteErrorKind.CREDENTIALS_EXPIRED)
            code in setOf("InvalidAccessKeyId", "SignatureDoesNotMatch", "InvalidToken", "InvalidClientTokenId", "UnrecognizedClientException") ->
                kind(RemoteErrorKind.CREDENTIALS_INVALID)
            code == "NoSuchBucket" -> kind(RemoteErrorKind.BUCKET_NOT_FOUND)
            code == "NoSuchKey" -> kind(RemoteErrorKind.OBJECT_NOT_FOUND)
            code in setOf("PermanentRedirect", "IllegalLocationConstraintException") || status == 301 || status == 307 ->
                kind(RemoteErrorKind.WRONG_REGION, regionFrom(f))
            code == "AuthorizationHeaderMalformed" ->
                if (regionWord.containsMatchIn(f.message.orEmpty()) || f.bucketRegionHeader != null) {
                    kind(RemoteErrorKind.WRONG_REGION, regionFrom(f))
                } else {
                    kind(RemoteErrorKind.CREDENTIALS_INVALID)
                }
            code in setOf("BadDigest", "InvalidDigest", "XAmzContentSHA256Mismatch") ->
                kind(RemoteErrorKind.INTEGRITY_MISMATCH)
            code in setOf("SlowDown", "ThrottlingException", "RequestLimitExceeded", "Throttling", "ServiceUnavailable") || status == 503 ->
                kind(RemoteErrorKind.THROTTLED)
            code in setOf("QuotaExceeded", "ServiceQuotaExceeded", "TooManyBuckets", "EntityTooLarge", "AccountProblem") ->
                kind(RemoteErrorKind.QUOTA_EXCEEDED)
            code in setOf("AccessDenied", "AllAccessDisabled", "AccessDeniedException") -> kind(RemoteErrorKind.ACCESS_DENIED)
            code in setOf("RequestTimeout", "InternalError", "SlowDownWrite") || status in setOf(500, 502, 504) ->
                kind(RemoteErrorKind.NETWORK)
            // Responses to HEAD carry no body, hence no error code: rely on the status.
            status == 403 -> kind(RemoteErrorKind.ACCESS_DENIED)
            status == 404 -> kind(if (ctx == OperationContext.HEAD || ctx == OperationContext.GET) RemoteErrorKind.OBJECT_NOT_FOUND else RemoteErrorKind.BUCKET_NOT_FOUND)
            status == 400 && f.bucketRegionHeader != null -> kind(RemoteErrorKind.WRONG_REGION, f.bucketRegionHeader)
            status == 412 -> kind(RemoteErrorKind.INTEGRITY_MISMATCH)
            status == 401 -> kind(RemoteErrorKind.CREDENTIALS_INVALID)
            else -> kind(RemoteErrorKind.UNKNOWN)
        }
    }

    private fun regionFrom(f: S3ErrorFacts): String? =
        f.bucketRegionHeader?.takeIf { it.isNotBlank() }
            ?: expectingRegion.find(f.message.orEmpty())?.groupValues?.get(1)

    /** Walks the cause chain: our own [RemoteException] (e.g. thrown while signing) wins over SDK wrappers. */
    fun isNetworkLevel(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth++ < 8) {
            if (cur is IOException) return true
            val n = cur::class.java.name
            if (n.contains("HttpException") || n.contains("Timeout") || n.contains("ConnectException") || n.contains("UnknownHost")) return true
            cur = cur.cause
        }
        return false
    }
}
