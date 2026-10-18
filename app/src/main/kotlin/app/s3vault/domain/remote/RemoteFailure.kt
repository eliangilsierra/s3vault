package app.s3vault.domain.remote

import app.s3vault.domain.model.ErrorKind

/** Causa clasificada de un fallo remoto. Ninguna variante transporta secretos. */
enum class FailureType(val errorKind: ErrorKind) {
    BUCKET_NOT_FOUND(ErrorKind.BUCKET_NOT_FOUND),
    WRONG_REGION(ErrorKind.WRONG_REGION),
    CREDENTIALS_MISSING(ErrorKind.CREDENTIALS_MISSING),
    CREDENTIALS_INVALID(ErrorKind.CREDENTIALS_INVALID),
    CREDENTIALS_EXPIRED(ErrorKind.CREDENTIALS_EXPIRED),
    CLOCK_SKEW(ErrorKind.CREDENTIALS_INVALID),
    ACCESS_DENIED(ErrorKind.ACCESS_DENIED),
    NETWORK(ErrorKind.NETWORK),
    THROTTLED(ErrorKind.THROTTLED),
    SERVER(ErrorKind.SERVER),
    CHECKSUM_MISMATCH(ErrorKind.CHECKSUM_MISMATCH),
    OBJECT_CHANGED(ErrorKind.OBJECT_CHANGED),
    OBJECT_NOT_FOUND(ErrorKind.UNKNOWN),
    TOO_LARGE(ErrorKind.TOO_LARGE),
    UNKNOWN(ErrorKind.UNKNOWN);

    /** Reintentar más tarde puede resolverlo sin intervención del usuario. */
    val isTransient: Boolean
        get() = this == NETWORK || this == THROTTLED || this == SERVER ||
            this == CHECKSUM_MISMATCH || this == OBJECT_CHANGED

    /**
     * Bloquea toda la cola, no solo un elemento: hasta que el usuario cambie credenciales,
     * permisos o configuración, el resto de las transferencias fallaría igual.
     */
    val blocksQueue: Boolean
        get() = this == BUCKET_NOT_FOUND || this == WRONG_REGION || this == CREDENTIALS_MISSING ||
            this == CREDENTIALS_INVALID || this == CREDENTIALS_EXPIRED || this == CLOCK_SKEW ||
            this == ACCESS_DENIED

    /** Es un problema de identidad/autorización (se resuelve renovando credenciales). */
    val isAuthentication: Boolean
        get() = this == CREDENTIALS_MISSING || this == CREDENTIALS_INVALID ||
            this == CREDENTIALS_EXPIRED || this == CLOCK_SKEW
}

data class RemoteFailure(
    val type: FailureType,
    /** Texto técnico breve y ya saneado; nunca incluye claves ni tokens. */
    val detail: String? = null,
    /** Región real del bucket cuando AWS la informa (error de región). */
    val bucketRegion: String? = null,
)

class RemoteException(val failure: RemoteFailure, cause: Throwable? = null) :
    Exception("${failure.type}${failure.detail?.let { ": $it" } ?: ""}", cause)
