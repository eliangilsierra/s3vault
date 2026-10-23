package com.s3vault.app.auth

import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.AwsCredentials
import com.s3vault.app.domain.AwsCredentialsProvider
import com.s3vault.app.domain.ConfigValidator
import com.s3vault.app.domain.CredentialsInfo
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.Clock
import java.time.Instant

sealed interface AuthStatus {
    data object Missing : AuthStatus
    data class Valid(val info: CredentialsInfo) : AuthStatus
    /** Temporary credentials that will expire within [ExpiryWarning]. */
    data class ExpiringSoon(val info: CredentialsInfo) : AuthStatus
    data class Expired(val info: CredentialsInfo) : AuthStatus
}

class InvalidCredentialsInput(val issues: List<ConfigValidator.Issue>) : Exception(issues.joinToString { it.message })

/**
 * Owns the credentials lifecycle: validation, encrypted persistence ([SecretStore]), expiry tracking and
 * deletion. Secrets live only inside the SecretStore and transiently in [AwsCredentials]; the exposed
 * [status] contains no secrets.
 *
 * Renewal: manually-supplied temporary credentials cannot be renewed by the app (there is no identity
 * source it could call). [canRenewAutomatically] is therefore false, and expiry produces
 * [AuthStatus.Expired] so the UI and workers ask for new credentials instead of faking a refresh.
 */
class CredentialsRepository(
    private val store: SecretStore,
    private val clock: Clock = Clock.systemUTC(),
) : AwsCredentialsProvider {

    private val _status = MutableStateFlow(computeStatus(load()))
    val status: StateFlow<AuthStatus> = _status.asStateFlow()

    override val canRenewAutomatically: Boolean = false

    /** Token for Method B (long-lived keys). */
    fun saveStatic(accessKeyId: String, secretAccessKey: String) {
        val issues = listOfNotNull(
            ConfigValidator.validateAccessKeyId(accessKeyId),
            ConfigValidator.validateSecret(secretAccessKey),
        )
        if (issues.isNotEmpty()) throw InvalidCredentialsInput(issues)
        if (accessKeyId.trim().startsWith("ASIA")) {
            throw InvalidCredentialsInput(
                listOf(ConfigValidator.Issue(ConfigValidator.Field.ACCESS_KEY_ID, "Un Access key ASIA… es temporal: usa el método de credenciales temporales.")),
            )
        }
        persist(Stored(AuthMethod.STATIC, accessKeyId.trim(), secretAccessKey.trim(), null, null))
    }

    /** Method A: session credentials pasted by the user. [expiration] is mandatory. */
    fun saveTemporary(accessKeyId: String, secretAccessKey: String, sessionToken: String, expiration: Instant?) {
        val issues = listOfNotNull(
            ConfigValidator.validateAccessKeyId(accessKeyId),
            ConfigValidator.validateSecret(secretAccessKey),
            ConfigValidator.validateSessionToken(sessionToken),
            if (expiration == null) ConfigValidator.Issue(ConfigValidator.Field.EXPIRATION, "Falta la fecha de expiración (ISO-8601, p. ej. 2026-10-22T18:30:00Z).") else null,
            if (expiration != null && !expiration.isAfter(clock.instant())) ConfigValidator.Issue(ConfigValidator.Field.EXPIRATION, "Estas credenciales ya han caducado.") else null,
        )
        if (issues.isNotEmpty()) throw InvalidCredentialsInput(issues)
        persist(Stored(AuthMethod.TEMPORARY, accessKeyId.trim(), secretAccessKey.trim(), sessionToken.trim(), expiration))
    }

    /** Parses CLI output / env snippet and stores it as temporary credentials. */
    fun saveTemporaryFromText(text: String) {
        val parsed = TemporaryCredentialsParser.parse(text)
            ?: throw InvalidCredentialsInput(listOf(ConfigValidator.Issue(ConfigValidator.Field.SECRET_KEY, "No se reconoció el formato. Pega el JSON de la CLI de AWS o las líneas AWS_ACCESS_KEY_ID=…")))
        saveTemporary(parsed.accessKeyId, parsed.secretAccessKey, parsed.sessionToken.orEmpty(), parsed.expiration)
    }

    /** Deletes the credentials from this device. Remote objects are not touched. */
    fun clear() {
        store.delete(SECRET_NAME)
        _status.value = AuthStatus.Missing
    }

    val method: AuthMethod? get() = load()?.method

    override suspend fun getCredentials(): AwsCredentials {
        val stored = load() ?: throw RemoteException(RemoteErrorKind.CREDENTIALS_MISSING)
        val creds = stored.toCredentials()
        if (creds.isExpired(clock.instant())) {
            refreshStatus()
            throw RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED)
        }
        return creds
    }

    /** Re-evaluates expiry; call periodically from the UI and before starting work. */
    fun refreshStatus() {
        _status.value = computeStatus(load())
    }

    private fun persist(stored: Stored) {
        store.put(SECRET_NAME, stored.toJson().toByteArray(Charsets.UTF_8))
        _status.value = computeStatus(stored)
    }

    private fun load(): Stored? = store.get(SECRET_NAME)?.let { bytes ->
        try {
            Stored.fromJson(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun computeStatus(stored: Stored?): AuthStatus {
        if (stored == null) return AuthStatus.Missing
        val info = CredentialsInfo(
            method = stored.method,
            accessKeyIdHint = stored.accessKeyId.take(4) + "…" + stored.accessKeyId.takeLast(4),
            hasSessionToken = stored.sessionToken != null,
            expiration = stored.expiration,
        )
        val exp = stored.expiration ?: return AuthStatus.Valid(info)
        val now = clock.instant()
        return when {
            !now.isBefore(exp.minusSeconds(60)) -> AuthStatus.Expired(info)
            now.isAfter(exp.minus(ExpiryWarning)) -> AuthStatus.ExpiringSoon(info)
            else -> AuthStatus.Valid(info)
        }
    }

    private class Stored(
        val method: AuthMethod,
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String?,
        val expiration: Instant?,
    ) {
        fun toCredentials() = AwsCredentials(accessKeyId, secretAccessKey, sessionToken, expiration)

        fun toJson(): String = JSONObject().apply {
            put("m", method.name)
            put("a", accessKeyId)
            put("s", secretAccessKey)
            sessionToken?.let { put("t", it) }
            expiration?.let { put("e", it.toString()) }
        }.toString()

        companion object {
            fun fromJson(json: String): Stored {
                val o = JSONObject(json)
                return Stored(
                    AuthMethod.valueOf(o.getString("m")),
                    o.getString("a"),
                    o.getString("s"),
                    if (o.has("t")) o.getString("t") else null,
                    if (o.has("e")) Instant.parse(o.getString("e")) else null,
                )
            }
        }
    }

    companion object {
        const val SECRET_NAME = "aws_credentials"
        val ExpiryWarning: java.time.Duration = java.time.Duration.ofMinutes(15)
    }
}
