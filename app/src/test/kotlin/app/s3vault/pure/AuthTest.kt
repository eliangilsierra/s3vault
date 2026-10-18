package app.s3vault.pure

import app.s3vault.auth.AccountInfo
import app.s3vault.auth.AuthMethod
import app.s3vault.auth.AwsCredentials
import app.s3vault.auth.CredentialInputError
import app.s3vault.auth.CredentialInputResult
import app.s3vault.auth.CredentialStatus
import app.s3vault.auth.CredentialTextParser
import app.s3vault.auth.CredentialVault
import app.s3vault.auth.CredentialsManager
import app.s3vault.auth.CredentialsUnavailableException
import app.s3vault.auth.DeviceAuthorization
import app.s3vault.auth.IdentityCenterApi
import app.s3vault.auth.IdentityCenterSignIn
import app.s3vault.auth.OidcClientRegistration
import app.s3vault.auth.OidcTokens
import app.s3vault.auth.RenewalFailure
import app.s3vault.auth.StoredCredentials
import app.s3vault.auth.TokenPoll
import app.s3vault.auth.UnavailableReason
import app.s3vault.security.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AKID_STATIC = "AKIAIOSFODNN7EXAMPLE"
private const val AKID_TEMP = "ASIAIOSFODNN7EXAMPLE"
private const val SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
private const val TOKEN = "IQoJb3JpZ2luX2VjEXAMPLETOKENEXAMPLETOKENEXAMPLETOKEN0123456789abcdef"

class CredentialsManagerTest {
    private val clock = MutableClock(Instant.parse("2026-01-01T10:00:00Z"))
    private val store = InMemorySecretStore()
    private fun manager(ic: IdentityCenterApi? = null) = CredentialsManager(CredentialVault(store), clock, ic)

    @Test
    fun `sin configurar no hay credenciales`() = runTest {
        val m = manager()
        assertEquals(CredentialStatus.NotConfigured, m.status())
        assertEquals(UnavailableReason.NOT_CONFIGURED, assertFailsWith<CredentialsUnavailableException> { m.credentials() }.reason)
    }

    @Test
    fun `claves estaticas`() = runTest {
        val m = manager()
        assertIs<CredentialInputResult.Ok>(m.saveStatic(AKID_STATIC, SECRET))
        val c = m.credentials()
        assertEquals(AKID_STATIC, c.accessKeyId); assertNull(c.sessionToken); assertNull(c.expiration)
        val st = assertIs<CredentialStatus.Valid>(m.status())
        assertEquals(AuthMethod.STATIC_KEYS, st.method); assertNull(st.expiresAt)
        assertFalse(st.maskedKeyId.contains("OSFODNN7"))
    }

    @Test
    fun `claves estaticas mal formadas o temporales se rechazan`() = runTest {
        val m = manager()
        assertEquals(listOf(CredentialInputError.ACCESS_KEY_ID_FORMAT, CredentialInputError.SECRET_FORMAT),
            (m.saveStatic("corta", "x") as CredentialInputResult.Rejected).errors)
        val r = m.saveStatic(AKID_TEMP, SECRET) as CredentialInputResult.Rejected
        assertTrue(CredentialInputError.KEY_TYPE_MISMATCH in r.errors)
        assertEquals(CredentialStatus.NotConfigured, m.status())
    }

    @Test
    fun `credenciales temporales exigen token y expiracion futura`() = runTest {
        val m = manager()
        val past = (m.saveTemporary(AKID_TEMP, SECRET, TOKEN, clock.now.minusSeconds(5)) as CredentialInputResult.Rejected).errors
        assertTrue(CredentialInputError.ALREADY_EXPIRED in past)
        val noToken = (m.saveTemporary(AKID_TEMP, SECRET, "", clock.now.plusSeconds(3600)) as CredentialInputResult.Rejected).errors
        assertTrue(CredentialInputError.SESSION_TOKEN_REQUIRED in noToken)
        val noExp = (m.saveTemporary(AKID_TEMP, SECRET, TOKEN, null) as CredentialInputResult.Rejected).errors
        assertTrue(CredentialInputError.EXPIRATION_REQUIRED in noExp)
        val longTerm = (m.saveTemporary(AKID_STATIC, SECRET, TOKEN, clock.now.plusSeconds(3600)) as CredentialInputResult.Rejected).errors
        assertTrue(CredentialInputError.KEY_TYPE_MISMATCH in longTerm)
    }

    @Test
    fun `temporales - vigencia, aviso de expiracion y caducidad`() = runTest {
        val m = manager()
        assertIs<CredentialInputResult.Ok>(m.saveTemporary(AKID_TEMP, SECRET, TOKEN, clock.now.plus(Duration.ofHours(1))))
        assertTrue(m.credentials().isTemporary)
        assertIs<CredentialStatus.Valid>(m.status())

        clock.advance(55 * 60) // quedan 5 min
        assertIs<CredentialStatus.ExpiringSoon>(m.status())
        assertNotNull(m.credentials())

        clock.advance(4 * 60 + 30) // quedan 30 s: dentro del margen de seguridad
        assertFailsWith<CredentialsUnavailableException> { m.credentials() }.also { assertEquals(UnavailableReason.EXPIRED, it.reason) }
        assertIs<CredentialStatus.Expired>(m.status())
    }

    @Test
    fun `renovar manualmente restablece el servicio`() = runTest {
        val m = manager()
        m.saveTemporary(AKID_TEMP, SECRET, TOKEN, clock.now.plusSeconds(600))
        clock.advance(601)
        assertFailsWith<CredentialsUnavailableException> { m.credentials() }
        m.saveTemporary("ASIAZZZZZZZZZZZZZZZZ", SECRET, TOKEN, clock.now.plusSeconds(3600))
        assertEquals("ASIAZZZZZZZZZZZZZZZZ", m.credentials().accessKeyId)
    }

    @Test
    fun `los secretos solo estan cifrados en el almacen y se pueden borrar`() = runTest {
        val m = manager()
        m.saveStatic(AKID_STATIC, SECRET)
        assertTrue(store.names.isNotEmpty())
        m.clear()
        assertTrue(store.names.isEmpty())
        assertEquals(CredentialStatus.NotConfigured, m.status())
        // una instancia nueva (tras reinicio) tampoco encuentra nada
        assertEquals(CredentialStatus.NotConfigured, manager().status())
    }

    @Test
    fun `persistencia tras reinicio`() = runTest {
        manager().saveTemporary(AKID_TEMP, SECRET, TOKEN, clock.now.plusSeconds(3600))
        val reborn = manager()
        assertEquals(AKID_TEMP, reborn.credentials().accessKeyId)
    }

    @Test
    fun `toString nunca expone secretos`() {
        val c = AwsCredentials(AKID_TEMP, SECRET, TOKEN, null)
        val text = c.toString() + StoredCredentials.Temporary(c) + StoredCredentials.StaticKeys(AKID_STATIC, SECRET)
        for (s in listOf(SECRET, TOKEN, AKID_STATIC, AKID_TEMP)) assertFalse(text.contains(s), "filtra $s")
    }

    @Test
    fun `parser entiende la salida de la AWS CLI y variables de entorno`() {
        val json = """{"Credentials":{"AccessKeyId":"$AKID_TEMP","SecretAccessKey":"$SECRET","SessionToken":"$TOKEN","Expiration":"2026-01-01T12:00:00+00:00"}}"""
        val a = CredentialTextParser.parse(json)
        assertEquals(AKID_TEMP, a.accessKeyId); assertEquals(SECRET, a.secretAccessKey); assertEquals(TOKEN, a.sessionToken)
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), a.expiration)

        val env = """
            export AWS_ACCESS_KEY_ID=$AKID_TEMP
            export AWS_SECRET_ACCESS_KEY="$SECRET"
            export AWS_SESSION_TOKEN=$TOKEN
            export AWS_CREDENTIAL_EXPIRATION=2026-01-01T12:00:00Z
        """.trimIndent()
        val b = CredentialTextParser.parse(env)
        assertEquals(a.copy(expiration = Instant.parse("2026-01-01T12:00:00Z")), b)

        val ini = "[default]\naws_access_key_id = $AKID_STATIC\naws_secret_access_key = $SECRET\n"
        val c = CredentialTextParser.parse(ini)
        assertEquals(AKID_STATIC, c.accessKeyId); assertNull(c.sessionToken)
        assertFalse(c.toString().contains(SECRET))
    }
}

class FakeIdentityCenter(private val clock: MutableClock) : IdentityCenterApi {
    var refreshResult: OidcTokens? = null
    var refreshCalls = 0
    var roleCalls = 0
    var networkDown = false
    var accessTokenValid = true
    val polls = ArrayDeque<TokenPoll>()
    override suspend fun registerClient(ssoRegion: String) = OidcClientRegistration("cid", "csecret", clock.now.plus(Duration.ofDays(80)))
    override suspend fun startDeviceAuthorization(ssoRegion: String, registration: OidcClientRegistration, startUrl: String) =
        DeviceAuthorization("dev", "ABCD-1234", "https://device.sso.eu-west-1.amazonaws.com/", "https://device.sso.eu-west-1.amazonaws.com/?user_code=ABCD-1234", clock.now.plusSeconds(600), 5)
    override suspend fun pollToken(ssoRegion: String, registration: OidcClientRegistration, authorization: DeviceAuthorization) = polls.removeFirst()
    override suspend fun refreshToken(ssoRegion: String, registration: OidcClientRegistration, refreshToken: String): OidcTokens? {
        refreshCalls++
        if (networkDown) throw IOException("sin red")
        return refreshResult
    }
    override suspend fun listAccounts(ssoRegion: String, accessToken: String) = listOf(AccountInfo("111122223333", "prod"))
    override suspend fun listRoles(ssoRegion: String, accessToken: String, accountId: String) = listOf("PhotoBackup")
    override suspend fun getRoleCredentials(ssoRegion: String, accessToken: String, accountId: String, roleName: String): AwsCredentials? {
        roleCalls++
        if (networkDown) throw IOException("sin red")
        if (!accessTokenValid) return null
        return AwsCredentials(AKID_TEMP, SECRET, TOKEN + roleCalls, clock.now.plusSeconds(3600))
    }
}

class IdentityCenterTest {
    private val clock = MutableClock(Instant.parse("2026-01-01T10:00:00Z"))
    private val api = FakeIdentityCenter(clock)
    private val store = InMemorySecretStore()
    private val manager = CredentialsManager(CredentialVault(store), clock, api)
    private val signIn = IdentityCenterSignIn(api, manager, clock) { ms -> clock.advance(ms / 1000) }

    private suspend fun signedIn(): OidcTokens {
        api.polls += TokenPoll.Pending
        api.polls += TokenPoll.SlowDown
        val tokens = OidcTokens("access-1", clock.now.plusSeconds(3600), "refresh-1")
        api.polls += TokenPoll.Granted(tokens)
        val pending = (signIn.begin("https://d-123.awsapps.com/start", "eu-west-1") as IdentityCenterSignIn.StartResult.Started).pending
        val granted = assertIs<IdentityCenterSignIn.Approval.Granted>(signIn.awaitApproval(pending))
        assertTrue(signIn.finish(pending, granted.tokens, "111122223333", "PhotoBackup"))
        return tokens
    }

    @Test
    fun `inicio de sesion completo guarda la sesion y entrega credenciales`() = runTest {
        signedIn()
        assertEquals("ASIAIOSFODNN7EXAMPLE", manager.credentials().accessKeyId)
        assertEquals(AuthMethod.IDENTITY_CENTER, (manager.status() as CredentialStatus.Valid).method)
    }

    @Test
    fun `valida la URL de inicio y la region`() = runTest {
        assertEquals(IdentityCenterSignIn.StartResult.InvalidInput, signIn.begin("http://x.awsapps.com/start", "eu-west-1"))
        assertEquals(IdentityCenterSignIn.StartResult.InvalidInput, signIn.begin("https://x.awsapps.com/start", "mal"))
        assertEquals(IdentityCenterSignIn.StartResult.InvalidInput, signIn.begin("javascript:alert(1)", "eu-west-1"))
    }

    @Test
    fun `usuario deniega o el codigo expira`() = runTest {
        api.polls += TokenPoll.Denied
        val p = (signIn.begin("https://d-123.awsapps.com/start", "eu-west-1") as IdentityCenterSignIn.StartResult.Started).pending
        assertEquals(IdentityCenterSignIn.Approval.Denied, signIn.awaitApproval(p))
        repeat(200) { api.polls += TokenPoll.Pending }
        assertEquals(IdentityCenterSignIn.Approval.Expired, signIn.awaitApproval(p))
    }

    @Test
    fun `renueva credenciales de rol usando el access token vigente`() = runTest {
        signedIn()
        val first = manager.credentials().sessionToken
        clock.advance(3600) // caducan las del rol; el access token (1 h) tambien esta justo en el limite
        api.refreshResult = OidcTokens("access-2", clock.now.plusSeconds(3600), "refresh-2")
        val second = manager.credentials()
        assertTrue(second.sessionToken != first)
        assertEquals(1, api.refreshCalls, "con el access token caducado se usa el refresh token")
    }

    @Test
    fun `renovacion silenciosa sin refresh cuando el access token sigue vigente`() = runTest {
        signedIn()
        clock.advance(3000)
        // role creds (1 h) siguen vigentes; avanzamos para dejarlas caducar sin caducar el access token
        api.refreshResult = null
        val tokens = StoredCredentials.IdentityCenter("u", "eu-west-1", "1", "r", "c", "s", clock.now.plusSeconds(99999),
            "access", clock.now.plusSeconds(7200), "refresh", null)
        manager.saveIdentityCenter(tokens)
        assertNotNull(manager.credentials())
        assertEquals(0, api.refreshCalls)
    }

    @Test
    fun `si el refresh token es rechazado hay que iniciar sesion de nuevo y no se inventa nada`() = runTest {
        signedIn()
        clock.advance(7200)
        api.refreshResult = null // el servidor rechaza el refresh token
        val e = assertFailsWith<CredentialsUnavailableException> { manager.credentials() }
        assertEquals(UnavailableReason.RENEWAL_FAILED, e.reason)
        assertEquals(RenewalFailure.REJECTED, e.renewal)
    }

    @Test
    fun `sin red la renovacion falla de forma controlada`() = runTest {
        signedIn()
        clock.advance(7200)
        api.networkDown = true
        val e = assertFailsWith<CredentialsUnavailableException> { manager.credentials() }
        assertEquals(RenewalFailure.NETWORK, e.renewal)
    }

    @Test
    fun `sin refresh token y con access token caducado exige iniciar sesion`() = runTest {
        manager.saveIdentityCenter(StoredCredentials.IdentityCenter("u", "eu-west-1", "1", "r", "c", "s", clock.now.plusSeconds(99999),
            "access", clock.now.minusSeconds(10), null, null))
        assertEquals(RenewalFailure.SIGN_IN_REQUIRED, assertFailsWith<CredentialsUnavailableException> { manager.credentials() }.renewal)
        assertIs<CredentialStatus.RenewalFailed>(manager.status())
    }
}
