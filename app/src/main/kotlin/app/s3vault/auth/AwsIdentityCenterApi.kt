package app.s3vault.auth

import aws.sdk.kotlin.services.sso.SsoClient
import aws.sdk.kotlin.services.sso.getRoleCredentials
import aws.sdk.kotlin.services.sso.listAccountRoles
import aws.sdk.kotlin.services.sso.listAccounts
import aws.sdk.kotlin.services.sso.model.UnauthorizedException
import aws.sdk.kotlin.services.ssooidc.SsoOidcClient
import aws.sdk.kotlin.services.ssooidc.createToken
import aws.sdk.kotlin.services.ssooidc.model.AccessDeniedException
import aws.sdk.kotlin.services.ssooidc.model.AuthorizationPendingException
import aws.sdk.kotlin.services.ssooidc.model.ExpiredTokenException
import aws.sdk.kotlin.services.ssooidc.model.InvalidClientException
import aws.sdk.kotlin.services.ssooidc.model.InvalidGrantException
import aws.sdk.kotlin.services.ssooidc.model.SlowDownException
import aws.sdk.kotlin.services.ssooidc.registerClient
import aws.sdk.kotlin.services.ssooidc.startDeviceAuthorization
import aws.smithy.kotlin.runtime.ServiceException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Clock
import java.time.Instant

/**
 * Implementación sobre el AWS SDK for Kotlin de los endpoints públicos de IAM Identity Center
 * (SSO OIDC y portal SSO). Estos endpoints no requieren credenciales AWS previas: la identidad
 * se demuestra con el flujo de autorización de dispositivo y los tokens resultantes.
 *
 * Los errores de transporte se relanzan como [IOException] para que el llamante los distinga
 * de un rechazo del servicio.
 */
class AwsIdentityCenterApi(private val clock: Clock = Clock.systemUTC()) : IdentityCenterApi {

    override suspend fun registerClient(ssoRegion: String): OidcClientRegistration = oidc(ssoRegion) { c ->
        val r = c.registerClient {
            clientName = CLIENT_NAME
            clientType = "public"
            scopes = listOf(SCOPE)
            grantTypes = listOf(DEVICE_GRANT, REFRESH_GRANT)
        }
        OidcClientRegistration(
            clientId = r.clientId ?: throw IOException("Respuesta incompleta de SSO OIDC"),
            clientSecret = r.clientSecret ?: throw IOException("Respuesta incompleta de SSO OIDC"),
            expiresAt = Instant.ofEpochSecond(r.clientSecretExpiresAt),
        )
    }

    override suspend fun startDeviceAuthorization(
        ssoRegion: String,
        registration: OidcClientRegistration,
        startUrl: String,
    ): DeviceAuthorization = oidc(ssoRegion) { c ->
        val r = c.startDeviceAuthorization {
            clientId = registration.clientId
            clientSecret = registration.clientSecret
            this.startUrl = startUrl
        }
        DeviceAuthorization(
            deviceCode = r.deviceCode ?: throw IOException("Respuesta incompleta de SSO OIDC"),
            userCode = r.userCode ?: "",
            verificationUri = r.verificationUri ?: "",
            verificationUriComplete = r.verificationUriComplete,
            expiresAt = clock.instant().plusSeconds(r.expiresIn.toLong()),
            pollIntervalSeconds = r.interval.takeIf { it > 0 } ?: 5,
        )
    }

    override suspend fun pollToken(
        ssoRegion: String,
        registration: OidcClientRegistration,
        authorization: DeviceAuthorization,
    ): TokenPoll = try {
        oidc(ssoRegion) { c ->
            val r = c.createToken {
                clientId = registration.clientId
                clientSecret = registration.clientSecret
                grantType = DEVICE_GRANT
                deviceCode = authorization.deviceCode
            }
            TokenPoll.Granted(
                OidcTokens(
                    accessToken = r.accessToken ?: throw IOException("Respuesta sin access token"),
                    expiresAt = clock.instant().plusSeconds(r.expiresIn.toLong()),
                    refreshToken = r.refreshToken,
                ),
            )
        }
    } catch (e: AuthorizationPendingException) {
        TokenPoll.Pending
    } catch (e: SlowDownException) {
        TokenPoll.SlowDown
    } catch (e: AccessDeniedException) {
        TokenPoll.Denied
    } catch (e: ExpiredTokenException) {
        TokenPoll.Expired
    }

    override suspend fun refreshToken(
        ssoRegion: String,
        registration: OidcClientRegistration,
        refreshToken: String,
    ): OidcTokens? = try {
        oidc(ssoRegion) { c ->
            val r = c.createToken {
                clientId = registration.clientId
                clientSecret = registration.clientSecret
                grantType = REFRESH_GRANT
                this.refreshToken = refreshToken
            }
            OidcTokens(
                accessToken = r.accessToken ?: throw IOException("Respuesta sin access token"),
                expiresAt = clock.instant().plusSeconds(r.expiresIn.toLong()),
                refreshToken = r.refreshToken,
            )
        }
    } catch (e: InvalidGrantException) {
        null
    } catch (e: InvalidClientException) {
        null
    } catch (e: ExpiredTokenException) {
        null
    } catch (e: AccessDeniedException) {
        null
    }

    override suspend fun listAccounts(ssoRegion: String, accessToken: String): List<AccountInfo> = sso(ssoRegion) { c ->
        val out = ArrayList<AccountInfo>()
        var next: String? = null
        do {
            val r = c.listAccounts { this.accessToken = accessToken; nextToken = next; maxResults = 100 }
            r.accountList.orEmpty().forEach { a ->
                out += AccountInfo(a.accountId ?: return@forEach, a.accountName ?: "")
            }
            next = r.nextToken
        } while (next != null)
        out
    }

    override suspend fun listRoles(ssoRegion: String, accessToken: String, accountId: String): List<String> =
        sso(ssoRegion) { c ->
            val out = ArrayList<String>()
            var next: String? = null
            do {
                val r = c.listAccountRoles {
                    this.accessToken = accessToken; this.accountId = accountId; nextToken = next; maxResults = 100
                }
                r.roleList.orEmpty().forEach { role -> role.roleName?.let(out::add) }
                next = r.nextToken
            } while (next != null)
            out
        }

    override suspend fun getRoleCredentials(
        ssoRegion: String,
        accessToken: String,
        accountId: String,
        roleName: String,
    ): AwsCredentials? = try {
        sso(ssoRegion) { c ->
            val rc = c.getRoleCredentials {
                this.accessToken = accessToken; this.accountId = accountId; this.roleName = roleName
            }.roleCredentials ?: throw IOException("Respuesta sin credenciales de rol")
            AwsCredentials(
                accessKeyId = rc.accessKeyId ?: throw IOException("Respuesta incompleta"),
                secretAccessKey = rc.secretAccessKey ?: throw IOException("Respuesta incompleta"),
                sessionToken = rc.sessionToken,
                expiration = Instant.ofEpochMilli(rc.expiration),
            )
        }
    } catch (e: UnauthorizedException) {
        null
    }

    private suspend fun <T> oidc(region: String, block: suspend (SsoOidcClient) -> T): T =
        SsoOidcClient { this.region = region }.use { guard { block(it) } }

    private suspend fun <T> sso(region: String, block: suspend (SsoClient) -> T): T =
        SsoClient { this.region = region }.use { guard { block(it) } }

    /** Deja pasar las excepciones del servicio (se interpretan arriba) y convierte el resto en E/S. */
    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ServiceException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("Fallo de transporte (${e.javaClass.simpleName})")
    }

    private companion object {
        const val CLIENT_NAME = "S3Vault"
        const val SCOPE = "sso:account:access"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val REFRESH_GRANT = "refresh_token"
    }
}
