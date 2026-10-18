package app.s3vault.pure

import app.s3vault.auth.AwsCredentials
import app.s3vault.auth.CredentialVault
import app.s3vault.auth.CredentialsManager
import app.s3vault.data.remote.Check
import app.s3vault.data.remote.ConnectionTester
import app.s3vault.data.remote.ScopedObjectStore
import app.s3vault.domain.remote.FailureType
import app.s3vault.domain.remote.RemoteFailure
import app.s3vault.domain.remote.RemoteKeys
import app.s3vault.logging.LogSink
import app.s3vault.logging.Redactor
import app.s3vault.logging.SecureLog
import app.s3vault.security.InMemorySecretStore
import app.s3vault.sync.BackupEngine
import app.s3vault.sync.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SecureLogTest {
    private val lines = mutableListOf<String>()
    private val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val token = "IQoJb3JpZ2luX2VjEXAMPLETOKENEXAMPLETOKENEXAMPLETOKEN0123456789abcdef"
    private val akid = "ASIAIOSFODNN7EXAMPLE"

    @BeforeTest fun setup() { SecureLog.sink = LogSink { _, tag, msg -> lines += "$tag $msg" } }
    @AfterTest fun tearDown() { SecureLog.sink = LogSink { _, _, _ -> }; Redactor.clearKnownSecrets() }

    @Test
    fun `redacta claves, tokens y cabeceras`() {
        val raw = "fallo con $akid secret=$secret x-amz-security-token: $token Authorization: AWS4-HMAC-SHA256 Credential=$akid/2026"
        val out = Redactor.redact(raw)
        for (s in listOf(secret, token, akid)) assertFalse(out.contains(s), "filtra $s: $out")
    }

    @Test
    fun `describe excepciones sin filtrar secretos`() {
        val e = RuntimeException("firma invalida para $akid con $secret", IllegalStateException("token $token"))
        val d = Redactor.describe(e)
        for (s in listOf(secret, token, akid)) assertFalse(d.contains(s))
    }

    @Test
    fun `secretos registrados se enmascaran aunque no tengan forma reconocible`() {
        Redactor.registerSecret("hunter2-hunter2")
        SecureLog.i("T", "el usuario escribio hunter2-hunter2")
        assertFalse(lines.single().contains("hunter2"))
    }

    @Test
    fun `AwsCredentials en logs y excepciones no expone nada`() = runTest {
        val c = AwsCredentials(akid, secret, token, Instant.now())
        SecureLog.e("Auth", "credenciales: $c", IllegalStateException("fallo $c"))
        val all = lines.joinToString("\n")
        for (s in listOf(secret, token, akid)) assertFalse(all.contains(s), "filtra $s")
    }

    @Test
    fun `un flujo completo de respaldo fallido no deja secretos en logs`() = runTest {
        // credenciales reales en el almacen; la subida falla con un mensaje que las contiene
        val manager = CredentialsManager(CredentialVault(InMemorySecretStore()), Clock.systemUTC())
        manager.saveTemporary(akid, secret, token, Instant.now().plusSeconds(3600))
        Redactor.registerSecret(secret); Redactor.registerSecret(token)
        val keys = RemoteKeys("p/")
        val remote = FakeObjectStore(); val store = FakeBackupStore(); val src = FakeMediaSource()
        src.files["content://m/1"] = ByteArray(100) to 1L
        store.rows["a".repeat(32)] = media("a".repeat(32), "content://m/1", size = 100)
        remote.failures += RemoteFailure(FailureType.CREDENTIALS_INVALID, "SignatureDoesNotMatch para $akid $secret")
        val engine = BackupEngine(store, ScopedObjectStore(remote, keys), keys, src, FakeIndex(), FakeLog(), { _, _, _ -> }, Clock.systemUTC(), Dispatchers.Unconfined)
        val out = engine.process("a".repeat(32))
        SecureLog.w("Backup", "resultado: $out ${store.rows.values}")
        SecureLog.w("Backup", "credenciales: ${manager.credentials()}")
        val all = lines.joinToString("\n")
        for (s in listOf(secret, token)) assertFalse(all.contains(s))
        // lo que se persiste en la fila (Room) tampoco puede contener secretos
        val persisted = store.rows.getValue("a".repeat(32)).toString()
        assertFalse(persisted.contains(secret)); assertFalse(persisted.contains(token))
    }
}

class HashingTest {
    @Test
    fun `sha256 conocido, streaming y version id`() {
        val r = Hashing.sha256(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", r.sha256Hex)
        assertEquals("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=", r.sha256Base64)
        assertEquals(3, r.bytes)
        assertEquals(r.sha256Hex.take(32), r.versionId)
        assertEquals(r.sha256Base64, Hashing.hexToBase64(r.sha256Hex))
    }

    @Test
    fun `la cancelacion se comprueba por bloque`() {
        var checks = 0
        Hashing.sha256(ByteArrayInputStream(ByteArray(200_000))) { checks++ }
        assertTrue(checks >= 3)
    }
}

class ConnectionTesterTest {
    private val keys = RemoteKeys("pre/")
    private val clock = Clock.systemUTC()
    private val manager = CredentialsManager(CredentialVault(InMemorySecretStore()), clock)

    @Test
    fun `conexion correcta usa una sola solicitud LIST acotada al prefijo`() = runTest {
        val remote = FakeObjectStore()
        val report = ConnectionTester(ScopedObjectStore(remote, keys), keys, manager).testConnection()
        assertTrue(report.connected)
        assertEquals(listOf("list:pre/photos/"), remote.calls)
    }

    @Test
    fun `prueba de permisos separa listar, escribir y leer`() = runTest {
        val remote = FakeObjectStore()
        val report = ConnectionTester(ScopedObjectStore(remote, keys), keys, manager).testPermissions()
        assertTrue(report.allPermissionsOk)
        assertTrue(remote.objects.keys.single() == "pre/.s3vault/connection-check.txt")
    }

    @Test
    fun `permisos insuficientes - se informa cual falta`() = runTest {
        val remote = FakeObjectStore()
        // listar está permitido, escribir no
        val denyPut = object : app.s3vault.data.remote.ObjectStore by remote {
            override suspend fun put(key: String, source: app.s3vault.data.remote.UploadSource, contentType: String, metadata: Map<String, String>, sha256Base64: String, onProgress: (Long) -> Unit) {
                throw app.s3vault.domain.remote.RemoteException(RemoteFailure(FailureType.ACCESS_DENIED))
            }
        }
        val report = ConnectionTester(denyPut, keys, manager).testPermissions()
        assertTrue(report.connected)
        assertEquals(FailureType.ACCESS_DENIED, assertIs<Check.Failed>(report.put).failure.type)
        assertEquals(Check.NotRun, report.get)
    }

    @Test
    fun `region incorrecta y bucket inexistente se distinguen`() = runTest {
        val remote = FakeObjectStore()
        remote.failures += RemoteFailure(FailureType.WRONG_REGION, bucketRegion = "eu-west-1")
        val r1 = ConnectionTester(remote, keys, manager).testConnection()
        val f1 = assertIs<Check.Failed>(r1.list).failure
        assertEquals("eu-west-1", f1.bucketRegion)
        remote.failures += RemoteFailure(FailureType.BUCKET_NOT_FOUND)
        assertEquals(FailureType.BUCKET_NOT_FOUND, assertIs<Check.Failed>(ConnectionTester(remote, keys, manager).testConnection().list).failure.type)
    }
}
