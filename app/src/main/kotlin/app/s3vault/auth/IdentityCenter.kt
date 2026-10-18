package app.s3vault.auth

import java.time.Instant

/** Registro de la app como cliente público OIDC de IAM Identity Center. */
data class OidcClientRegistration(val clientId: String, val clientSecret: String, val expiresAt: Instant)

data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    /** URL que ya incluye el código de usuario; es la que se abre en el navegador. */
    val verificationUriComplete: String?,
    val expiresAt: Instant,
    val pollIntervalSeconds: Int,
)

data class OidcTokens(val accessToken: String, val expiresAt: Instant, val refreshToken: String?)

sealed interface TokenPoll {
    data object Pending : TokenPoll
    data object SlowDown : TokenPoll
    data class Granted(val tokens: OidcTokens) : TokenPoll
    data object Denied : TokenPoll
    data object Expired : TokenPoll
}

data class AccountInfo(val accountId: String, val accountName: String)

/**
 * Operaciones de IAM Identity Center necesarias (SSO OIDC + SSO portal). Todas viajan por HTTPS
 * hacia endpoints de AWS; ninguna requiere infraestructura propia.
 */
interface IdentityCenterApi {
    suspend fun registerClient(ssoRegion: String): OidcClientRegistration
    suspend fun startDeviceAuthorization(
        ssoRegion: String,
        registration: OidcClientRegistration,
        startUrl: String,
    ): DeviceAuthorization

    suspend fun pollToken(
        ssoRegion: String,
        registration: OidcClientRegistration,
        authorization: DeviceAuthorization,
    ): TokenPoll

    /** @return nuevos tokens, o null si el servidor rechazó el refresh token (hay que iniciar sesión). */
    suspend fun refreshToken(
        ssoRegion: String,
        registration: OidcClientRegistration,
        refreshToken: String,
    ): OidcTokens?

    suspend fun listAccounts(ssoRegion: String, accessToken: String): List<AccountInfo>
    suspend fun listRoles(ssoRegion: String, accessToken: String, accountId: String): List<String>

    /** @return null si el access token ya no es válido. */
    suspend fun getRoleCredentials(
        ssoRegion: String,
        accessToken: String,
        accountId: String,
        roleName: String,
    ): AwsCredentials?
}
