package app.s3vault.pure

import app.s3vault.data.remote.IamPolicies
import app.s3vault.data.remote.RawS3Error
import app.s3vault.data.remote.S3ErrorClassifier
import app.s3vault.domain.model.ConfigError
import app.s3vault.domain.model.ConfigValidation
import app.s3vault.domain.model.S3Config
import app.s3vault.domain.model.S3ConfigRules
import app.s3vault.domain.model.SyncState
import app.s3vault.domain.remote.FailureType
import app.s3vault.domain.remote.ObjectMetadata
import app.s3vault.domain.remote.RemoteKeys
import app.s3vault.domain.sync.SyncEvent
import app.s3vault.domain.sync.SyncStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncStateMachineTest {
    @Test
    fun `flujo feliz`() {
        var s = SyncState.LOCAL_ONLY
        for (e in listOf(SyncEvent.ENQUEUE, SyncEvent.START_UPLOAD, SyncEvent.UPLOAD_COMPLETED, SyncEvent.VERIFIED)) {
            s = SyncStateMachine.require(s, e)
        }
        assertEquals(SyncState.BACKED_UP, s)
    }

    @Test
    fun `solo la verificacion o una restauracion verificada llevan a BACKED_UP`() {
        val allowed = setOf(SyncEvent.VERIFIED, SyncEvent.RESTORE_COMPLETED, SyncEvent.LOCAL_MEDIA_RETURNED_SAME)
        for (from in SyncState.entries) for (e in SyncEvent.entries) {
            val to = SyncStateMachine.next(from, e)
            if (to == SyncState.BACKED_UP && !(from == SyncState.BACKED_UP && e == SyncEvent.ENQUEUE)) {
                assertTrue(e in allowed, "$from --$e--> BACKED_UP no debería existir")
            }
        }
    }

    @Test
    fun `los errores nunca producen BACKED_UP y no pierden trabajo`() {
        for (from in listOf(SyncState.UPLOADING, SyncState.VERIFYING)) {
            assertEquals(SyncState.QUEUED, SyncStateMachine.next(from, SyncEvent.DEFER))
            assertEquals(SyncState.FAILED, SyncStateMachine.next(from, SyncEvent.FAIL))
            assertEquals(SyncState.QUEUED, SyncStateMachine.next(from, SyncEvent.RECOVER))
        }
    }

    @Test
    fun `un contenido modificado invalida el respaldo de la version anterior`() {
        assertEquals(SyncState.LOCAL_ONLY, SyncStateMachine.next(SyncState.BACKED_UP, SyncEvent.CONTENT_CHANGED))
        assertEquals(SyncState.QUEUED, SyncStateMachine.next(SyncState.UPLOADING, SyncEvent.CONTENT_CHANGED))
    }

    @Test
    fun `borrar localmente un respaldado lo deja REMOTE_ONLY y se puede restaurar`() {
        val gone = SyncStateMachine.require(SyncState.BACKED_UP, SyncEvent.LOCAL_MEDIA_GONE)
        assertEquals(SyncState.REMOTE_ONLY, gone)
        val r = SyncStateMachine.require(gone, SyncEvent.RESTORE_REQUESTED)
        assertEquals(SyncState.RESTORING, r)
        assertEquals(SyncState.BACKED_UP, SyncStateMachine.require(r, SyncEvent.RESTORE_COMPLETED))
        assertEquals(SyncState.REMOTE_ONLY, SyncStateMachine.require(r, SyncEvent.RESTORE_FAILED))
    }

    @Test
    fun `transiciones ilegales se rechazan`() {
        assertNull(SyncStateMachine.next(SyncState.LOCAL_ONLY, SyncEvent.VERIFIED))
        assertNull(SyncStateMachine.next(SyncState.QUEUED, SyncEvent.UPLOAD_COMPLETED))
        assertNull(SyncStateMachine.next(SyncState.BACKED_UP, SyncEvent.START_UPLOAD))
        assertNull(SyncStateMachine.next(SyncState.LOCAL_ONLY, SyncEvent.RESTORE_REQUESTED))
    }
}

class S3ConfigTest {
    private fun valid(region: String = "eu-west-1", bucket: String = "my-photos", prefix: String = "") =
        S3Config.create(region, bucket, prefix)

    @Test
    fun `normaliza el prefijo`() {
        assertEquals("", S3ConfigRules.normalizePrefix("  "))
        assertEquals("", S3ConfigRules.normalizePrefix("/"))
        assertEquals("fotos/", S3ConfigRules.normalizePrefix("fotos"))
        assertEquals("a/b/", S3ConfigRules.normalizePrefix("/a/b//".trimEnd('/').let { "/$it/" }))
        assertEquals("phone-1/2024/", S3ConfigRules.normalizePrefix("/phone-1/2024/"))
    }

    @Test
    fun `rechaza prefijos peligrosos para politicas IAM`() {
        for (bad in listOf("a/*", "a?b", "../x", "a/../b", "a//b", "mi foto", "a/./b", "\$x", "a\\b", "ñ", "a".repeat(300))) {
            assertNull(S3ConfigRules.normalizePrefix(bad), "debería rechazar '$bad'")
        }
    }

    @Test
    fun `valida nombres de bucket`() {
        for (ok in listOf("abc", "my-bucket", "a.b.c-d", "bucket123", "a".repeat(63))) assertTrue(S3ConfigRules.isValidBucketName(ok), ok)
        for (bad in listOf("ab", "A-bucket", "-bucket", "bucket-", "a..b", "192.168.1.1", "xn--abc", "my_bucket", "a".repeat(64), "b-s3alias", "")) {
            assertFalse(S3ConfigRules.isValidBucketName(bad), bad)
        }
    }

    @Test
    fun `valida la region`() {
        for (ok in listOf("us-east-1", "eu-west-3", "ap-southeast-2", "us-gov-west-1", "il-central-1")) assertTrue(S3ConfigRules.isValidRegion(ok), ok)
        for (bad in listOf("", "us_east_1", "useast1", "US-EAST-1", "us-east", "https://x")) assertFalse(S3ConfigRules.isValidRegion(bad), bad)
    }

    @Test
    fun `create acumula errores`() {
        val r = S3Config.create("nope", "X", "a/*")
        assertTrue(r is ConfigValidation.Invalid)
        assertEquals(setOf(ConfigError.INVALID_REGION, ConfigError.INVALID_BUCKET, ConfigError.INVALID_PREFIX), r.errors.toSet())
        val ok = valid(prefix = "/fotos") as ConfigValidation.Valid
        assertEquals("fotos/", ok.config.prefix)
    }
}

class RemoteKeysTest {
    private val keys = RemoteKeys("backup/")
    private val asset = "0123456789abcdef0123456789abcdef"
    private val version = "fedcba9876543210fedcba9876543210"

    @Test
    fun `formato de clave predecible y parseable`() {
        val k = keys.objectKey(asset, version, "IMG 0001 (1).JPG")
        assertEquals("backup/photos/$asset/$version/IMG_0001__1.JPG", k)
        val p = assertNotNull(keys.parse(k))
        assertEquals(asset, p.assetId); assertEquals(version, p.versionId)
    }

    @Test
    fun `distintos archivos con el mismo nombre no colisionan`() {
        val a = keys.objectKey(asset, version, "IMG_1.jpg")
        val b = keys.objectKey("1".repeat(32), version, "IMG_1.jpg")
        val c = keys.objectKey(asset, "2".repeat(32), "IMG_1.jpg")
        assertEquals(3, setOf(a, b, c).size)
    }

    @Test
    fun `sin prefijo las claves empiezan en photos`() {
        assertTrue(RemoteKeys("").objectKey(asset, version, "a.jpg").startsWith("photos/"))
    }

    @Test
    fun `aislamiento por prefijo`() {
        assertTrue(keys.isInScope("backup/photos/x"))
        assertFalse(keys.isInScope("other/photos/x"))
        assertFalse(keys.isInScope("backup2/photos/x"))
        assertFalse(keys.isInScope("backup/../other/x"))
        assertFalse(keys.isInScope("backup//x"))
        assertFalse(keys.isInScope("backup/a\u0000b"))
        assertNull(keys.parse("other/photos/$asset/$version/a.jpg"))
        assertNull(keys.parse("backup/.s3vault/connection-check.txt"))
        assertNull(keys.parse("backup/photos/NOTANID/$version/a.jpg"))
        assertNull(keys.parse("backup/photos/$asset/$version/dir/a.jpg"))
    }

    @Test
    fun `safeName conserva extension y elimina caracteres peligrosos`() {
        assertEquals("file", RemoteKeys.safeName("???"))
        assertEquals("a.jpg", RemoteKeys.safeName("../../a.jpg"))
        assertEquals("foto.jpeg", RemoteKeys.safeName("foto.jpeg"))
        assertTrue(RemoteKeys.safeName("a".repeat(500) + ".png").length <= 91)
        assertEquals("file.png", RemoteKeys.safeName("日本語.png"))
    }

    @Test
    fun `metadatos usan solo ASCII y roundtrip`() {
        val m = ObjectMetadata("a".repeat(64), "Niño ☺.jpg", 5L, 10, 20, asset, version)
        val map = m.toMap()
        assertTrue(map.values.all { v -> v.all { it.code < 128 } })
        assertEquals("Niño ☺.jpg", ObjectMetadata.fromMap(map)!!.originalName)
        assertNull(ObjectMetadata.fromMap(mapOf("foo" to "bar")))
    }
}

class S3ErrorClassifierTest {
    private fun t(raw: RawS3Error) = S3ErrorClassifier.classify(raw)

    @Test
    fun `distingue las causas pedidas`() {
        assertEquals(FailureType.BUCKET_NOT_FOUND, t(RawS3Error(404, "NoSuchBucket")).type)
        val region = t(RawS3Error(301, "PermanentRedirect", bucketRegionHeader = "eu-west-1"))
        assertEquals(FailureType.WRONG_REGION, region.type); assertEquals("eu-west-1", region.bucketRegion)
        val msg = t(RawS3Error(400, "AuthorizationHeaderMalformed", "the region 'us-east-1' is wrong; expecting 'sa-east-1'"))
        assertEquals("sa-east-1", msg.bucketRegion)
        assertEquals(FailureType.CREDENTIALS_INVALID, t(RawS3Error(403, "InvalidAccessKeyId")).type)
        assertEquals(FailureType.CREDENTIALS_INVALID, t(RawS3Error(403, "SignatureDoesNotMatch")).type)
        assertEquals(FailureType.CREDENTIALS_EXPIRED, t(RawS3Error(400, "ExpiredToken")).type)
        assertEquals(FailureType.ACCESS_DENIED, t(RawS3Error(403, "AccessDenied")).type)
        assertEquals(FailureType.ACCESS_DENIED, t(RawS3Error(403, null, isHead = true)).type)
        assertEquals(FailureType.NETWORK, t(RawS3Error(isNetworkError = true)).type)
        assertEquals(FailureType.THROTTLED, t(RawS3Error(503, "SlowDown")).type)
        assertEquals(FailureType.SERVER, t(RawS3Error(500, "InternalError")).type)
        assertEquals(FailureType.SERVER, t(RawS3Error(502)).type)
        assertEquals(FailureType.OBJECT_NOT_FOUND, t(RawS3Error(404, null, isHead = true)).type)
        assertEquals(FailureType.OBJECT_CHANGED, t(RawS3Error(412, "PreconditionFailed")).type)
        assertEquals(FailureType.CHECKSUM_MISMATCH, t(RawS3Error(400, "BadDigest")).type)
        assertEquals(FailureType.CLOCK_SKEW, t(RawS3Error(403, "RequestTimeTooSkewed")).type)
    }

    @Test
    fun `transitorios, bloqueantes y permanentes`() {
        assertTrue(FailureType.NETWORK.isTransient); assertTrue(FailureType.THROTTLED.isTransient)
        assertFalse(FailureType.ACCESS_DENIED.isTransient)
        for (f in listOf(FailureType.ACCESS_DENIED, FailureType.CREDENTIALS_EXPIRED, FailureType.WRONG_REGION, FailureType.BUCKET_NOT_FOUND)) assertTrue(f.blocksQueue)
        assertFalse(FailureType.NETWORK.blocksQueue)
        assertTrue(FailureType.CREDENTIALS_EXPIRED.isAuthentication)
    }

    @Test
    fun `el detalle no incluye el mensaje original`() {
        val f = t(RawS3Error(403, "AccessDenied", "User: arn:aws:iam::123:user/x token=SECRETSECRETSECRETSECRETSECRETSECRETSECRET"))
        assertFalse((f.detail ?: "").contains("SECRET"))
    }
}

class IamPoliciesTest {
    @Test
    fun `politica minima sin comodines peligrosos ni borrado`() {
        val p = IamPolicies.identityPolicy("my-bucket", "phone/")
        assertTrue(p.contains("\"s3:PutObject\"") && p.contains("\"s3:GetObject\"") && p.contains("s3:ListBucket"))
        assertFalse(p.contains("s3:*")); assertFalse(p.contains("Delete")); assertFalse(p.contains("\"Resource\": \"*\""))
        assertTrue(p.contains("arn:aws:s3:::my-bucket\""))
        assertTrue(p.contains("arn:aws:s3:::my-bucket/phone/*"))
        assertTrue(p.contains("\"s3:prefix\": [\"phone/*\"]"))
    }

    @Test
    fun `sin prefijo no hay condicion de listado`() {
        val p = IamPolicies.identityPolicy("my-bucket", "")
        assertFalse(p.contains("s3:prefix"))
        assertTrue(p.contains("arn:aws:s3:::my-bucket/*"))
    }

    @Test
    fun `la politica de bucket solo deniega transporte inseguro`() {
        val p = IamPolicies.bucketPolicyRequireTls("b")
        assertTrue(p.contains("\"Effect\": \"Deny\"") && !p.contains("\"Effect\": \"Allow\""))
    }
}
