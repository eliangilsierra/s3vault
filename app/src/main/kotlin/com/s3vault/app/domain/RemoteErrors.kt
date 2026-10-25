package com.s3vault.app.domain

/** User-meaningful classes of S3 failures. Messages never contain secrets or raw SDK text. */
enum class RemoteErrorKind(val retryable: Boolean, val userMessage: String) {
    NOT_CONFIGURED(false, "Amazon S3 no está configurado."),
    CREDENTIALS_MISSING(false, "No hay credenciales guardadas."),
    CREDENTIALS_EXPIRED(false, "Las credenciales temporales han caducado. Introduce credenciales nuevas."),
    CREDENTIALS_INVALID(false, "Credenciales inválidas (Access key o secret incorrectos, o clave desactivada)."),
    BUCKET_NOT_FOUND(false, "El bucket no existe (o el nombre está mal escrito)."),
    WRONG_REGION(false, "La región configurada no coincide con la del bucket."),
    ACCESS_DENIED(false, "Permisos insuficientes para esta operación. Revisa la política IAM."),
    OBJECT_NOT_FOUND(false, "El archivo ya no existe en S3 (puede haberse borrado desde fuera de la app)."),
    NETWORK(true, "Error temporal de red. Se reintentará."),
    THROTTLED(true, "Amazon S3 está limitando las solicitudes en este momento. Se reintentará más tarde."),
    QUOTA_EXCEEDED(false, "Tu cuenta de AWS alcanzó un límite o cuota. Revísalo en tu cuenta de AWS."),
    INTEGRITY_MISMATCH(true, "La copia subida no coincide con el original, así que no se da por respaldada. Se reintentará."),
    FILE_UNAVAILABLE(false, "No se puede leer el archivo del dispositivo (¿se movió o se borró?)."),
    FILE_TOO_LARGE(false, "El archivo es demasiado grande para esta versión de la app (el límite es 4 GiB)."),
    UNKNOWN(true, "Amazon S3 devolvió un error inesperado. Se reintentará; si se repite, revisa la conexión y la configuración."),
}

class RemoteException(
    val kind: RemoteErrorKind,
    /** Region S3 reports as correct, when [kind] is WRONG_REGION and it could be determined. */
    val expectedRegion: String? = null,
    /** Non-sensitive diagnostic (HTTP status / S3 error code only). */
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(buildMessage(kind, expectedRegion, detail), cause) {

    val retryable: Boolean get() = kind.retryable

    /** True when the operation cannot succeed until the user acts on credentials. */
    val needsCredentials: Boolean
        get() = kind == RemoteErrorKind.CREDENTIALS_EXPIRED || kind == RemoteErrorKind.CREDENTIALS_MISSING ||
            kind == RemoteErrorKind.CREDENTIALS_INVALID

    private companion object {
        fun buildMessage(kind: RemoteErrorKind, expectedRegion: String?, detail: String?): String = buildString {
            append(kind.userMessage)
            if (kind == RemoteErrorKind.WRONG_REGION && expectedRegion != null) append(" Región del bucket: $expectedRegion.")
            if (!detail.isNullOrBlank()) append(" [").append(detail).append("]")
        }
    }
}
