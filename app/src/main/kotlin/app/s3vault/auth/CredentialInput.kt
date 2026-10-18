package app.s3vault.auth

import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException

/** Campos extraídos de un texto pegado (salida de la AWS CLI, variables de entorno, etc.). */
data class ParsedCredentials(
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
    val sessionToken: String? = null,
    val expiration: Instant? = null,
) {
    override fun toString(): String = "ParsedCredentials(accessKeyId=${accessKeyId?.let(AwsCredentials::maskKeyId)}, secret=***)"
}

/**
 * Extrae credenciales de los formatos que producen las herramientas oficiales:
 *  * JSON de `aws sts get-session-token|assume-role` (con o sin el nivel `Credentials`),
 *  * JSON de `aws configure export-credentials`,
 *  * `export AWS_ACCESS_KEY_ID=...` / `$Env:AWS_...` / formato `~/.aws/credentials`.
 * No valida contra AWS; solo interpreta el texto. Nunca registra su contenido.
 */
object CredentialTextParser {
    private val VALUE = "[\"']?\\s*[:=]\\s*[\"']?([^\\s\"',]+)"
    private fun field(vararg names: String): Regex =
        Regex("(?i)(?:${names.joinToString("|")})$VALUE")

    private val ACCESS = field("aws_access_key_id", "AccessKeyId")
    private val SECRET = field("aws_secret_access_key", "SecretAccessKey")
    private val TOKEN = field("aws_session_token", "SessionToken", "aws_security_token")
    private val EXPIRATION = field("aws_credential_expiration", "aws_session_expiration", "Expiration", "Expiry")

    fun parse(text: String): ParsedCredentials {
        fun find(r: Regex) = r.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return ParsedCredentials(
            accessKeyId = find(ACCESS),
            secretAccessKey = find(SECRET),
            sessionToken = find(TOKEN),
            expiration = find(EXPIRATION)?.let(::parseInstant),
        )
    }

    fun parseInstant(value: String): Instant? = try {
        Instant.parse(value.trim())
    } catch (_: DateTimeParseException) {
        // Formatos con offset (2026-01-01T10:00:00+00:00) que genera la CLI.
        runCatching { java.time.OffsetDateTime.parse(value.trim()).toInstant() }.getOrNull()
    }
}

enum class CredentialInputError {
    ACCESS_KEY_ID_FORMAT,
    SECRET_FORMAT,
    SESSION_TOKEN_REQUIRED,
    SESSION_TOKEN_NOT_ALLOWED,
    EXPIRATION_REQUIRED,
    ALREADY_EXPIRED,
    KEY_TYPE_MISMATCH,
}

sealed interface CredentialInputResult {
    data class Ok(val credentials: StoredCredentials) : CredentialInputResult
    data class Rejected(val errors: List<CredentialInputError>) : CredentialInputResult
}

/** Validación local previa a guardar. La validez real solo la confirma AWS ("Probar conexión"). */
object CredentialValidator {
    private val ACCESS_KEY = Regex("^[A-Z0-9]{16,128}$")
    private val SECRET_KEY = Regex("^[A-Za-z0-9/+=]{30,128}$")

    fun validateStatic(accessKeyId: String, secret: String): CredentialInputResult {
        val id = accessKeyId.trim()
        val sk = secret.trim()
        val errors = buildList {
            if (!ACCESS_KEY.matches(id)) add(CredentialInputError.ACCESS_KEY_ID_FORMAT)
            // ASIA = credencial temporal: necesita session token y pertenece al método temporal.
            else if (id.startsWith("ASIA")) add(CredentialInputError.KEY_TYPE_MISMATCH)
            if (!SECRET_KEY.matches(sk)) add(CredentialInputError.SECRET_FORMAT)
        }
        return if (errors.isEmpty()) CredentialInputResult.Ok(StoredCredentials.StaticKeys(id, sk))
        else CredentialInputResult.Rejected(errors)
    }

    fun validateTemporary(
        accessKeyId: String,
        secret: String,
        sessionToken: String,
        expiration: Instant?,
        clock: Clock,
    ): CredentialInputResult {
        val id = accessKeyId.trim()
        val sk = secret.trim()
        val token = sessionToken.trim()
        val errors = buildList {
            if (!ACCESS_KEY.matches(id)) add(CredentialInputError.ACCESS_KEY_ID_FORMAT)
            else if (id.startsWith("AKIA")) add(CredentialInputError.KEY_TYPE_MISMATCH)
            if (!SECRET_KEY.matches(sk)) add(CredentialInputError.SECRET_FORMAT)
            if (token.length < 16) add(CredentialInputError.SESSION_TOKEN_REQUIRED)
            if (expiration == null) add(CredentialInputError.EXPIRATION_REQUIRED)
            else if (!expiration.isAfter(clock.instant())) add(CredentialInputError.ALREADY_EXPIRED)
        }
        return if (errors.isEmpty()) {
            CredentialInputResult.Ok(StoredCredentials.Temporary(AwsCredentials(id, sk, token, expiration)))
        } else CredentialInputResult.Rejected(errors)
    }
}
