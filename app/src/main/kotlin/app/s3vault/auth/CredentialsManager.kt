package app.s3vault.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Proveedor único que usa toda la app. Lee el registro cifrado y, según el método, devuelve
 * las claves estáticas, las credenciales temporales manuales (si no expiraron) o renueva la
 * sesión de IAM Identity Center. Mantiene una copia en memoria que se invalida al guardar.
 */
class CredentialsManager(
    private val vault: CredentialVault,
    private val clock: Clock = Clock.systemUTC(),
    private val identityCenter: IdentityCenterApi? = null,
) : AwsCredentialsProvider {

    private val mutex = Mutex()
    private var cached: StoredCredentials? = null
    private var loaded = false

    /** Margen antes de la expiración en el que ya no se usa una credencial. */
    private val skew: Duration = Duration.ofSeconds(60)
    private val warnWindow: Duration = Duration.ofMinutes(10)

    suspend fun saveStatic(accessKeyId: String, secret: String): CredentialInputResult {
        val result = CredentialValidator.validateStatic(accessKeyId, secret)
        if (result is CredentialInputResult.Ok) store(result.credentials)
        return result
    }

    suspend fun saveTemporary(
        accessKeyId: String,
        secret: String,
        sessionToken: String,
        expiration: Instant?,
    ): CredentialInputResult {
        val result = CredentialValidator.validateTemporary(accessKeyId, secret, sessionToken, expiration, clock)
        if (result is CredentialInputResult.Ok) store(result.credentials)
        return result
    }

    suspend fun saveIdentityCenter(session: StoredCredentials.IdentityCenter) = store(session)

    /** Borra todo el material de autenticación local (no toca objetos remotos). */
    suspend fun clear() = mutex.withLock {
        vault.clear()
        cached = null
        loaded = true
    }

    private suspend fun store(c: StoredCredentials) = mutex.withLock {
        vault.save(c)
        cached = c
        loaded = true
    }

    private suspend fun current(): StoredCredentials? {
        if (!loaded) {
            cached = vault.load()
            loaded = true
        }
        return cached
    }

    override suspend fun credentials(): AwsCredentials = mutex.withLock {
        val now = clock.instant()
        when (val record = current()) {
            null -> throw CredentialsUnavailableException(UnavailableReason.NOT_CONFIGURED)
            is StoredCredentials.StaticKeys ->
                AwsCredentials(record.accessKeyId, record.secretAccessKey, null, null)

            is StoredCredentials.Temporary -> {
                val c = record.credentials
                if (isExpired(c.expiration, now)) throw CredentialsUnavailableException(UnavailableReason.EXPIRED)
                c
            }

            is StoredCredentials.IdentityCenter -> resolveIdentityCenter(record, now)
        }
    }

    override suspend fun status(): CredentialStatus = mutex.withLock {
        val now = clock.instant()
        when (val record = current()) {
            null -> CredentialStatus.NotConfigured
            is StoredCredentials.StaticKeys ->
                CredentialStatus.Valid(AuthMethod.STATIC_KEYS, null, AwsCredentials.maskKeyId(record.accessKeyId))

            is StoredCredentials.Temporary -> statusFor(AuthMethod.TEMPORARY_MANUAL, record.credentials, now)
            is StoredCredentials.IdentityCenter -> identityCenterStatus(record, now)
        }
    }

    private fun statusFor(method: AuthMethod, c: AwsCredentials, now: Instant): CredentialStatus {
        val exp = c.expiration ?: return CredentialStatus.Valid(method, null, AwsCredentials.maskKeyId(c.accessKeyId))
        if (isExpired(exp, now)) return CredentialStatus.Expired(method, exp)
        val remaining = Duration.between(now, exp)
        return if (remaining <= warnWindow) {
            CredentialStatus.ExpiringSoon(method, exp, remaining, AwsCredentials.maskKeyId(c.accessKeyId))
        } else CredentialStatus.Valid(method, exp, AwsCredentials.maskKeyId(c.accessKeyId))
    }

    private fun isExpired(exp: Instant?, now: Instant) = exp != null && !now.isBefore(exp.minus(skew))

    // ---- IAM Identity Center ------------------------------------------------------------

    private fun identityCenterStatus(r: StoredCredentials.IdentityCenter, now: Instant): CredentialStatus {
        val rc = r.roleCredentials
        if (rc != null && !isExpired(rc.expiration, now)) return statusFor(AuthMethod.IDENTITY_CENTER, rc, now)
        // Las credenciales de rol caducaron: se pueden renovar mientras haya token vigente o refresh token.
        val canRefresh = r.refreshToken != null ||
            (r.accessToken != null && r.accessTokenExpiresAt?.isAfter(now) == true)
        return if (canRefresh) {
            CredentialStatus.Valid(AuthMethod.IDENTITY_CENTER, r.roleCredentials?.expiration, "sso:${r.roleName}")
        } else CredentialStatus.RenewalFailed(AuthMethod.IDENTITY_CENTER, RenewalFailure.SIGN_IN_REQUIRED)
    }

    private suspend fun resolveIdentityCenter(r: StoredCredentials.IdentityCenter, now: Instant): AwsCredentials {
        r.roleCredentials?.let { if (!isExpired(it.expiration, now)) return it }
        val api = identityCenter ?: throw CredentialsUnavailableException(
            UnavailableReason.RENEWAL_FAILED, RenewalFailure.SIGN_IN_REQUIRED,
        )
        try {
            var session = r
            // 1) ¿Access token vigente? Si no, intentar el refresh token.
            val tokenValid = session.accessToken != null &&
                session.accessTokenExpiresAt?.let { it.minus(skew).isAfter(now) } == true
            if (!tokenValid) {
                val refresh = session.refreshToken ?: throw CredentialsUnavailableException(
                    UnavailableReason.RENEWAL_FAILED, RenewalFailure.SIGN_IN_REQUIRED,
                )
                if (!session.clientExpiresAt.isAfter(now)) throw CredentialsUnavailableException(
                    UnavailableReason.RENEWAL_FAILED, RenewalFailure.SIGN_IN_REQUIRED,
                )
                val tokens = api.refreshToken(
                    session.ssoRegion, OidcClientRegistration(session.clientId, session.clientSecret, session.clientExpiresAt),
                    refresh,
                ) ?: throw CredentialsUnavailableException(UnavailableReason.RENEWAL_FAILED, RenewalFailure.REJECTED)
                session = session.copy(
                    accessToken = tokens.accessToken,
                    accessTokenExpiresAt = tokens.expiresAt,
                    refreshToken = tokens.refreshToken ?: session.refreshToken,
                )
                persist(session)
            }
            val creds = api.getRoleCredentials(session.ssoRegion, session.accessToken!!, session.accountId, session.roleName)
                ?: throw CredentialsUnavailableException(UnavailableReason.RENEWAL_FAILED, RenewalFailure.REJECTED)
            persist(session.copy(roleCredentials = creds))
            return creds
        } catch (e: IOException) {
            throw CredentialsUnavailableException(UnavailableReason.RENEWAL_FAILED, RenewalFailure.NETWORK)
        }
    }

    // Se llama con el mutex tomado desde credentials().
    private suspend fun persist(c: StoredCredentials) {
        vault.save(c)
        cached = c
        loaded = true
    }
}
