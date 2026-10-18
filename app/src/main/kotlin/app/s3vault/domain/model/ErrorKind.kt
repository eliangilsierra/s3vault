package app.s3vault.domain.model

/** Clasificación persistida del último error (sin datos sensibles). */
enum class ErrorKind {
    NETWORK,
    SERVER,
    THROTTLED,
    CREDENTIALS_MISSING,
    CREDENTIALS_EXPIRED,
    CREDENTIALS_INVALID,
    ACCESS_DENIED,
    BUCKET_NOT_FOUND,
    WRONG_REGION,
    CHECKSUM_MISMATCH,
    OBJECT_CHANGED,
    SOURCE_MISSING,
    SOURCE_CHANGED,
    TOO_LARGE,
    STORAGE,
    UNKNOWN,
}
