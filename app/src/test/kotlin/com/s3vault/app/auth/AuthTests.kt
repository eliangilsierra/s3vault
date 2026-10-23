package com.s3vault.app.auth

import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.security.InMemorySecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MutableClock(var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = now
}

class TemporaryCredentialsParserTest {
    @Test fun `sts get-session-token json`() {
        val p = TemporaryCredentialsParser.parse(
            """{"Credentials":{"AccessKeyId":"ASIAEXAMPLEEXAMPLE","SecretAccessKey":"secretsecretsecretsecretsecret1234","SessionToken":"tokentokentokentoken","Expiration":"2026-10-22T18:00:00+00:00"}}""",
        )!!
        assertEquals("ASIAEXAMPLEEXAMPLE", p.accessKeyId)
        assertEquals("tokentokentokentoken", p.sessionToken)
        assertEquals(Instant.parse("2026-10-22T18:00:00Z"), p.expiration)
    }

    @Test fun `export-credentials process format`() {
        val p = TemporaryCredentialsParser.parse(
            """{"Version":1,"AccessKeyId":"ASIAEXAMPLEEXAMPLE","SecretAccessKey":"secretsecretsecretsecretsecret1234","SessionToken":"tok","Expiration":"2026-10-22T18:00:00Z"}""",
        )!!
        assertEquals(Instant.parse("2026-10-22T18:00:00Z"), p.expiration)
    }

    @Test fun `env var lines`() {
        val p = TemporaryCredentialsParser.parse(
            """
            export AWS_ACCESS_KEY_ID=ASIAEXAMPLEEXAMPLE
            export AWS_SECRET_ACCESS_KEY="secretsecretsecretsecretsecret1234"
            export AWS_SESSION_TOKEN=tokentokentoken
            export AWS_CREDENTIAL_EXPIRATION=2026-10-22T18:00:00Z
            """.trimIndent(),
        )!!
        assertEquals("tokentokentoken", p.sessionToken)
        assertEquals(Instant.parse("2026-10-22T18:00:00Z"), p.expiration)
    }

    @Test fun `powershell env lines`() {
        val p = TemporaryCredentialsParser.parse(
            "\$Env:AWS_ACCESS_KEY_ID=\"ASIAEXAMPLEEXAMPLE\"\n\$Env:AWS_SECRET_ACCESS_KEY=\"secretsecretsecretsecretsecret1234\"\n\$Env:AWS_SESSION_TOKEN=\"tokentoken\"",
        )!!
        assertEquals("ASIAEXAMPLEEXAMPLE", p.accessKeyId)
        assertNull(p.expiration)
    }

    @Test fun garbage() {
        assertNull(TemporaryCredentialsParser.parse("hello"))
        assertNull(TemporaryCredentialsParser.parse("{not json"))
        assertNull(TemporaryCredentialsParser.parse("""{"Credentials":{"AccessKeyId":"x"}}"""))
    }
}

class CredentialsRepositoryTest {
    private val t0 = Instant.parse("2026-10-22T12:00:00Z")
    private val clock = MutableClock(t0)
    private val store = InMemorySecretStore()
    private val repo = CredentialsRepository(store, clock)
    private val ak = "AKIAIOSFODNN7EXAMPLE"
    private val sk = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

    @Test fun `static credentials are retrievable`() = runBlocking {
        repo.saveStatic(ak, sk)
        val c = repo.getCredentials()
        assertEquals(ak, c.accessKeyId)
        assertNull(c.sessionToken)
        assertNull(c.expiration)
        assertEquals(AuthMethod.STATIC, repo.method)
        assertTrue(repo.status.value is AuthStatus.Valid)
    }

    @Test fun `static method rejects temporary ASIA keys`() {
        assertThrows(InvalidCredentialsInput::class.java) { repo.saveStatic("ASIAIOSFODNN7EXAMPLE", sk) }
    }

    @Test fun `temporary credentials require session token and future expiration`() {
        assertThrows(InvalidCredentialsInput::class.java) { repo.saveTemporary("ASIAIOSFODNN7EXAMPLE", sk, "", t0.plusSeconds(3600)) }
        assertThrows(InvalidCredentialsInput::class.java) { repo.saveTemporary("ASIAIOSFODNN7EXAMPLE", sk, "tokentokentokentoken", null) }
        assertThrows(InvalidCredentialsInput::class.java) { repo.saveTemporary("ASIAIOSFODNN7EXAMPLE", sk, "tokentokentokentoken", t0.minusSeconds(5)) }
    }

    @Test fun `temporary credentials expire and requests are refused`() = runBlocking {
        repo.saveTemporary("ASIAIOSFODNN7EXAMPLE", sk, "tokentokentokentoken", t0.plusSeconds(3600))
        assertTrue(repo.status.value is AuthStatus.Valid)
        assertEquals("tokentokentokentoken", repo.getCredentials().sessionToken)

        clock.now = t0.plusSeconds(3600 - 600) // inside the 15 min warning window
        repo.refreshStatus()
        assertTrue(repo.status.value is AuthStatus.ExpiringSoon)

        clock.now = t0.plusSeconds(3601)
        val e = runCatching { repo.getCredentials() }.exceptionOrNull() as RemoteException
        assertEquals(RemoteErrorKind.CREDENTIALS_EXPIRED, e.kind)
        assertTrue(e.needsCredentials)
        assertTrue(repo.status.value is AuthStatus.Expired)
        assertFalse(repo.canRenewAutomatically) // manual credentials are never silently "renewed"
    }

    @Test fun `missing credentials`() = runBlocking {
        val e = runCatching { repo.getCredentials() }.exceptionOrNull() as RemoteException
        assertEquals(RemoteErrorKind.CREDENTIALS_MISSING, e.kind)
        assertTrue(repo.status.value is AuthStatus.Missing)
    }

    @Test fun `clear removes secrets and status`() {
        repo.saveStatic(ak, sk)
        repo.clear()
        assertNull(store.get(CredentialsRepository.SECRET_NAME))
        assertTrue(repo.status.value is AuthStatus.Missing)
    }

    @Test fun `new instance reads persisted credentials`() = runBlocking {
        repo.saveStatic(ak, sk)
        val again = CredentialsRepository(store, clock)
        assertEquals(ak, again.getCredentials().accessKeyId)
    }

    @Test fun `status never exposes secret values`() {
        repo.saveTemporary("ASIAIOSFODNN7EXAMPLE", sk, "tokentokentokentoken", t0.plusSeconds(3600))
        val text = repo.status.value.toString()
        assertFalse(text.contains(sk))
        assertFalse(text.contains("tokentokentokentoken"))
        assertFalse(text.contains("ASIAIOSFODNN7EXAMPLE"))
    }

    @Test fun `saveTemporaryFromText parses cli json`() {
        repo.saveTemporaryFromText(
            """{"Credentials":{"AccessKeyId":"ASIAIOSFODNN7EXAMPLE","SecretAccessKey":"$sk","SessionToken":"tokentokentokentoken","Expiration":"2026-10-22T13:00:00Z"}}""",
        )
        assertEquals(AuthMethod.TEMPORARY, repo.method)
        assertThrows(InvalidCredentialsInput::class.java) { repo.saveTemporaryFromText("nonsense") }
    }

    @Test fun `credentials toString hides secrets`() = runBlocking {
        repo.saveStatic(ak, sk)
        val s = repo.getCredentials().toString()
        assertFalse(s.contains(sk))
        assertFalse(s.contains(ak))
    }
}
