package app.s3vault.auth

import app.s3vault.domain.model.S3ConfigRules
import kotlinx.coroutines.delay
import java.net.URI
import java.time.Clock

/**
 * Orquesta el inicio de sesión con IAM Identity Center (flujo de autorización de dispositivo):
 *  1. registra la app como cliente público OIDC,
 *  2. obtiene un código de usuario y una URL que el usuario abre en el navegador,
 *  3. sondea hasta que el usuario aprueba,
 *  4. el usuario elige cuenta y rol y se piden las credenciales temporales del rol.
 * Los tokens quedan en el [CredentialsManager] (cifrados); nada pasa por un servidor propio.
 */
class IdentityCenterSignIn(
    private val api: IdentityCenterApi,
    private val manager: CredentialsManager,
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (millis: Long) -> Unit = { delay(it) },
) {
    class Pending(
        val startUrl: String,
        val ssoRegion: String,
        val registration: OidcClientRegistration,
        val authorization: DeviceAuthorization,
    )

    sealed interface Approval {
        data class Granted(val tokens: OidcTokens) : Approval
        data object Denied : Approval
        data object Expired : Approval
    }

    sealed interface StartResult {
        data class Started(val pending: Pending) : StartResult
        data object InvalidInput : StartResult
    }

    suspend fun begin(startUrl: String, ssoRegion: String): StartResult {
        val url = startUrl.trim()
        if (!isValidStartUrl(url) || !S3ConfigRules.isValidRegion(ssoRegion.trim())) return StartResult.InvalidInput
        val region = ssoRegion.trim()
        val registration = api.registerClient(region)
        val authorization = api.startDeviceAuthorization(region, registration, url)
        return StartResult.Started(Pending(url, region, registration, authorization))
    }

    /** Sondea respetando el intervalo indicado por AWS (y aumentándolo ante `slow_down`). */
    suspend fun awaitApproval(p: Pending): Approval {
        var interval = p.authorization.pollIntervalSeconds.coerceAtLeast(1)
        while (clock.instant().isBefore(p.authorization.expiresAt)) {
            sleep(interval * 1000L)
            when (val r = api.pollToken(p.ssoRegion, p.registration, p.authorization)) {
                is TokenPoll.Granted -> return Approval.Granted(r.tokens)
                TokenPoll.Pending -> Unit
                TokenPoll.SlowDown -> interval += 5
                TokenPoll.Denied -> return Approval.Denied
                TokenPoll.Expired -> return Approval.Expired
            }
        }
        return Approval.Expired
    }

    suspend fun accounts(p: Pending, tokens: OidcTokens): List<AccountInfo> =
        api.listAccounts(p.ssoRegion, tokens.accessToken)

    suspend fun roles(p: Pending, tokens: OidcTokens, accountId: String): List<String> =
        api.listRoles(p.ssoRegion, tokens.accessToken, accountId)

    /** Pide las credenciales del rol y guarda la sesión. Devuelve false si AWS las rechaza. */
    suspend fun finish(p: Pending, tokens: OidcTokens, accountId: String, roleName: String): Boolean {
        val creds = api.getRoleCredentials(p.ssoRegion, tokens.accessToken, accountId, roleName) ?: return false
        manager.saveIdentityCenter(
            StoredCredentials.IdentityCenter(
                startUrl = p.startUrl,
                ssoRegion = p.ssoRegion,
                accountId = accountId,
                roleName = roleName,
                clientId = p.registration.clientId,
                clientSecret = p.registration.clientSecret,
                clientExpiresAt = p.registration.expiresAt,
                accessToken = tokens.accessToken,
                accessTokenExpiresAt = tokens.expiresAt,
                refreshToken = tokens.refreshToken,
                roleCredentials = creds,
            ),
        )
        return true
    }

    companion object {
        /** El portal de acceso siempre es HTTPS; se rechaza cualquier otro esquema. */
        fun isValidStartUrl(url: String): Boolean = runCatching {
            val u = URI(url)
            u.scheme == "https" && !u.host.isNullOrBlank() && u.host.contains('.') && u.userInfo == null
        }.getOrDefault(false)
    }
}
