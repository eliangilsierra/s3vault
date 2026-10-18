package app.s3vault.data.remote

import app.s3vault.domain.remote.FailureType
import app.s3vault.domain.remote.RemoteFailure

/** Resumen independiente del SDK de un error HTTP/S3, para clasificarlo y poder probarlo. */
data class RawS3Error(
    val httpStatus: Int? = null,
    val code: String? = null,
    val message: String? = null,
    /** Valor de la cabecera `x-amz-bucket-region` si estaba presente. */
    val bucketRegionHeader: String? = null,
    val isNetworkError: Boolean = false,
    /** La operación afectada no devuelve cuerpo de error (HEAD). */
    val isHead: Boolean = false,
)

object S3ErrorClassifier {

    private val WRONG_REGION_CODES = setOf(
        "PermanentRedirect", "AuthorizationHeaderMalformed", "IllegalLocationConstraintException",
        "AuthorizationQueryParametersError",
    )
    private val INVALID_CREDENTIAL_CODES = setOf(
        "InvalidAccessKeyId", "SignatureDoesNotMatch", "InvalidSecurity", "InvalidToken",
        "UnrecognizedClientException", "IncompleteSignature", "MissingSecurityHeader",
        "InvalidClientTokenId",
    )
    private val EXPIRED_CODES = setOf("ExpiredToken", "ExpiredTokenException", "TokenRefreshRequired")
    private val SKEW_CODES = setOf("RequestTimeTooSkewed", "RequestExpired")
    private val DENIED_CODES = setOf("AccessDenied", "AllAccessDisabled", "AccountProblem", "NotSignedUp")
    private val THROTTLE_CODES = setOf(
        "SlowDown", "Throttling", "ThrottlingException", "RequestLimitExceeded", "TooManyRequests",
        "BandwidthLimitExceeded", "QuotaExceeded", "TooManyBuckets",
    )
    private val SERVER_CODES = setOf(
        "InternalError", "ServiceUnavailable", "RequestTimeout", "OperationAborted", "ReplicationFailure",
    )
    private val CHECKSUM_CODES = setOf(
        "BadDigest", "InvalidDigest", "XAmzContentSHA256Mismatch", "InvalidChecksum",
        "CRC32Mismatch", "SHA256Mismatch",
    )

    private val REGION_IN_MESSAGE = Regex("expecting '([a-z0-9-]+)'", RegexOption.IGNORE_CASE)

    fun classify(e: RawS3Error): RemoteFailure {
        val code = e.code
        val status = e.httpStatus
        val header = e.bucketRegionHeader?.takeIf { it.isNotBlank() }
        val regionFromMessage = e.message?.let { REGION_IN_MESSAGE.find(it)?.groupValues?.get(1) }

        if (e.isNetworkError) return RemoteFailure(FailureType.NETWORK)

        return when {
            code == "NoSuchBucket" -> RemoteFailure(FailureType.BUCKET_NOT_FOUND)
            code in WRONG_REGION_CODES || status == 301 || status == 307 ->
                RemoteFailure(FailureType.WRONG_REGION, bucketRegion = header ?: regionFromMessage)

            code in EXPIRED_CODES -> RemoteFailure(FailureType.CREDENTIALS_EXPIRED)
            code in SKEW_CODES -> RemoteFailure(FailureType.CLOCK_SKEW)
            code in INVALID_CREDENTIAL_CODES -> RemoteFailure(FailureType.CREDENTIALS_INVALID)
            code in CHECKSUM_CODES -> RemoteFailure(FailureType.CHECKSUM_MISMATCH)
            code == "EntityTooLarge" -> RemoteFailure(FailureType.TOO_LARGE)
            code == "PreconditionFailed" || status == 412 -> RemoteFailure(FailureType.OBJECT_CHANGED)
            code in THROTTLE_CODES || status == 429 -> RemoteFailure(FailureType.THROTTLED, code)
            code in DENIED_CODES -> RemoteFailure(FailureType.ACCESS_DENIED, code)
            code == "NoSuchKey" || code == "NotFound" -> RemoteFailure(FailureType.OBJECT_NOT_FOUND)
            code in SERVER_CODES || (status != null && status >= 500) ->
                RemoteFailure(FailureType.SERVER, code ?: "HTTP $status")

            // Respuestas sin cuerpo (HEAD): solo contamos con el código HTTP.
            status == 403 -> RemoteFailure(FailureType.ACCESS_DENIED, code)
            status == 404 && e.isHead -> RemoteFailure(FailureType.OBJECT_NOT_FOUND)
            status == 404 -> RemoteFailure(FailureType.BUCKET_NOT_FOUND)
            status == 401 -> RemoteFailure(FailureType.CREDENTIALS_INVALID)
            else -> RemoteFailure(FailureType.UNKNOWN, listOfNotNull(code, status?.let { "HTTP $it" }).joinToString(" "))
        }
    }
}
