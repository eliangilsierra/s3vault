package app.s3vault.presentation

import app.s3vault.auth.CredentialInputError
import app.s3vault.domain.model.ErrorKind
import app.s3vault.domain.model.SyncState
import app.s3vault.domain.remote.FailureType
import app.s3vault.domain.remote.RemoteFailure
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date
import java.util.Locale

/**
 * Textos de la interfaz (español). Centralizados aquí para facilitar su traducción posterior a
 * recursos `strings.xml`. Ningún mensaje incluye claves, tokens ni ARN completos.
 */
object Strings {
    fun failure(f: RemoteFailure): String = when (f.type) {
        FailureType.BUCKET_NOT_FOUND -> "El bucket no existe (o su nombre está mal escrito). Revisa el nombre y la región."
        FailureType.WRONG_REGION -> "La región es incorrecta." + (f.bucketRegion?.let { " El bucket está en $it." } ?: " Comprueba en la consola de S3 dónde está el bucket.")
        FailureType.CREDENTIALS_MISSING -> "No hay credenciales configuradas. Añádelas en Seguridad y credenciales."
        FailureType.CREDENTIALS_INVALID -> "AWS rechazó las credenciales (clave inexistente o firma inválida). Vuelve a introducirlas."
        FailureType.CREDENTIALS_EXPIRED -> "Las credenciales temporales caducaron. Introduce credenciales nuevas o inicia sesión de nuevo."
        FailureType.CLOCK_SKEW -> "La hora del dispositivo difiere demasiado de la de AWS. Activa la hora automática."
        FailureType.ACCESS_DENIED -> "Faltan permisos en la política IAM para esta operación (o el prefijo no coincide). Revisa la política recomendada."
        FailureType.NETWORK -> "Sin conexión o conexión inestable. Se reintentará automáticamente."
        FailureType.THROTTLED -> "AWS está limitando las solicitudes (límite o cuota). Se reintentará más tarde."
        FailureType.SERVER -> "Error temporal de AWS. Se reintentará automáticamente."
        FailureType.CHECKSUM_MISMATCH -> "La verificación de integridad falló; se reintentará."
        FailureType.OBJECT_CHANGED -> "El objeto cambió en S3 durante la descarga; se reintentará."
        FailureType.OBJECT_NOT_FOUND -> "El objeto ya no existe en S3."
        FailureType.TOO_LARGE -> "El archivo supera los 5 GiB; esta versión no admite subidas multiparte."
        FailureType.UNKNOWN -> "Error inesperado" + (f.detail?.let { " ($it)" } ?: "") + "."
    }

    /** `code` es el nombre de un [FailureType] o [ErrorKind] guardado como último error de restauración. */
    fun restoreError(code: String?): String {
        if (code.isNullOrBlank()) return ""
        val failure = FailureType.entries.firstOrNull { it.name == code }
        if (failure != null) return ": " + failureType(failure)
        val kind = ErrorKind.entries.firstOrNull { it.name == code }
        return if (kind != null) ": " + errorKind(kind) else ""
    }

    fun failureType(type: FailureType?): String = type?.let { failure(RemoteFailure(it)) } ?: ""

    fun errorKind(kind: ErrorKind?): String = when (kind) {
        null -> ""
        ErrorKind.NETWORK -> "Sin conexión; se reintentará."
        ErrorKind.SERVER -> "Error temporal de AWS; se reintentará."
        ErrorKind.THROTTLED -> "Límite de AWS; se reintentará."
        ErrorKind.CREDENTIALS_MISSING -> "Faltan credenciales."
        ErrorKind.CREDENTIALS_EXPIRED -> "Credenciales caducadas."
        ErrorKind.CREDENTIALS_INVALID -> "Credenciales rechazadas por AWS."
        ErrorKind.ACCESS_DENIED -> "Permisos insuficientes en AWS."
        ErrorKind.BUCKET_NOT_FOUND -> "Bucket inexistente."
        ErrorKind.WRONG_REGION -> "Región incorrecta."
        ErrorKind.CHECKSUM_MISMATCH -> "Falló la verificación de integridad."
        ErrorKind.OBJECT_CHANGED -> "El objeto cambió en S3."
        ErrorKind.SOURCE_MISSING -> "El archivo local ya no está disponible."
        ErrorKind.SOURCE_CHANGED -> "El archivo cambió durante la subida."
        ErrorKind.TOO_LARGE -> "Archivo mayor de 5 GiB."
        ErrorKind.STORAGE -> "No se pudo escribir en el dispositivo (¿sin espacio?)."
        ErrorKind.UNKNOWN -> "Error inesperado."
    }

    fun state(state: SyncState): String = when (state) {
        SyncState.LOCAL_ONLY -> "Solo en el dispositivo"
        SyncState.QUEUED -> "En cola"
        SyncState.UPLOADING -> "Subiendo"
        SyncState.VERIFYING -> "Verificando"
        SyncState.BACKED_UP -> "Respaldada"
        SyncState.FAILED -> "Con error"
        SyncState.REMOTE_ONLY -> "Solo en S3"
        SyncState.RESTORING -> "Restaurando"
    }

    fun credentialInput(e: CredentialInputError): String = when (e) {
        CredentialInputError.ACCESS_KEY_ID_FORMAT -> "El Access Key ID no tiene un formato válido."
        CredentialInputError.SECRET_FORMAT -> "El Secret Access Key no tiene un formato válido."
        CredentialInputError.SESSION_TOKEN_REQUIRED -> "Falta el Session Token (las credenciales temporales siempre lo incluyen)."
        CredentialInputError.SESSION_TOKEN_NOT_ALLOWED -> "Las claves estáticas no llevan Session Token."
        CredentialInputError.EXPIRATION_REQUIRED -> "Indica la fecha de expiración (formato 2026-01-31T12:00:00Z)."
        CredentialInputError.ALREADY_EXPIRED -> "Esas credenciales ya están caducadas."
        CredentialInputError.KEY_TYPE_MISMATCH -> "El tipo de clave no corresponde al método elegido (AKIA = estática, ASIA = temporal)."
    }

    // ---- Formatos ----------------------------------------------------------------------

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var i = -1
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return DecimalFormat("#,##0.#").format(v) + " " + units[i]
    }

    fun dateTime(ms: Long?): String =
        if (ms == null || ms <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(ms))

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds} s"
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86400 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        else -> "${seconds / 86400} d"
    }

    const val MEDIA_DELETE_NOTE =
        "Se enviarán a la papelera del dispositivo. Si ya tienen copia en S3, esa copia NO se elimina."
}
