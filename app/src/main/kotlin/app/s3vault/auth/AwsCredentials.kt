package app.s3vault.auth

import java.time.Duration
import java.time.Instant

/** Método con el que el usuario obtuvo las credenciales. */
enum class AuthMethod {
    /** Credenciales temporales (STS) pegadas manualmente. Recomendado por defecto. */
    TEMPORARY_MANUAL,

    /** Sesión de IAM Identity Center (flujo de dispositivo), renovable sin backend propio. */
    IDENTITY_CENTER,

    /** Access key + secret estáticas de un usuario IAM dedicado. Solo usuarios avanzados. */
    STATIC_KEYS,
}

/**
 * Credenciales listas para firmar solicitudes. [toString] y [equals] jamás exponen secretos:
 * un volcado accidental en logs o en una excepción solo muestra el id enmascarado.
 */
class AwsCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String?,
    val expiration: Instant?,
) {
    val isTemporary: Boolean get() = sessionToken != null

    override fun toString(): String = "AwsCredentials(accessKeyId=${maskKeyId(accessKeyId)}, secret=***, " +
        "sessionToken=${if (sessionToken != null) "***" else "none"}, expiration=$expiration)"

    override fun equals(other: Any?): Boolean = other is AwsCredentials &&
        accessKeyId == other.accessKeyId && secretAccessKey == other.secretAccessKey &&
        sessionToken == other.sessionToken && expiration == other.expiration

    override fun hashCode(): Int = accessKeyId.hashCode()

    companion object {
        fun maskKeyId(id: String): String =
            if (id.length <= 8) "****" else id.take(4) + "…" + id.takeLast(2)
    }
}

/** Estado de la autenticación que se muestra en pantalla y condiciona los trabajos. */
sealed interface CredentialStatus {
    data object NotConfigured : CredentialStatus

    /** Válidas. [expiresAt] es null para claves estáticas (no caducan, pero se pueden revocar). */
    data class Valid(
        val method: AuthMethod,
        val expiresAt: Instant?,
        val maskedKeyId: String,
    ) : CredentialStatus

    /** Válidas pero caducan pronto; conviene renovar. */
    data class ExpiringSoon(
        val method: AuthMethod,
        val expiresAt: Instant,
        val remaining: Duration,
        val maskedKeyId: String,
    ) : CredentialStatus

    data class Expired(val method: AuthMethod, val expiredAt: Instant?) : CredentialStatus

    /** No se pudo renovar la sesión (p. ej. el proveedor de identidad exige iniciar sesión de nuevo). */
    data class RenewalFailed(val method: AuthMethod, val reason: RenewalFailure) : CredentialStatus
}

enum class RenewalFailure { SIGN_IN_REQUIRED, NETWORK, REJECTED }

enum class UnavailableReason { NOT_CONFIGURED, EXPIRED, RENEWAL_FAILED }

/** Las credenciales no están disponibles; el trabajo debe conservarse y esperar. */
class CredentialsUnavailableException(
    val reason: UnavailableReason,
    val renewal: RenewalFailure? = null,
) : Exception("Credenciales no disponibles: $reason")

/**
 * Abstracción de la que depende toda la lógica de S3. La galería y la cola no saben qué tipo
 * de credencial hay detrás.
 */
interface AwsCredentialsProvider {
    /**
     * Devuelve credenciales vigentes, renovándolas si el proveedor lo permite.
     * @throws CredentialsUnavailableException si no hay o no se pueden renovar.
     */
    suspend fun credentials(): AwsCredentials

    /** Estado actual sin efectos de red (no intenta renovar). */
    suspend fun status(): CredentialStatus
}
