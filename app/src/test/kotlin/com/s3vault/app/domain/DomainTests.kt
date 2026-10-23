package com.s3vault.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeysTest {
    private val assetId = "a".repeat(32)
    private val version = "b".repeat(64)

    @Test fun `prefix is normalized`() {
        assertEquals("", RemoteKeys.normalizePrefix(""))
        assertEquals("", RemoteKeys.normalizePrefix(" / "))
        assertEquals("a/b/", RemoteKeys.normalizePrefix("/a//b"))
        assertEquals("fotos/", RemoteKeys.normalizePrefix("fotos/"))
    }

    @Test fun `photo key layout includes prefix asset version and sanitized name`() {
        val keys = RemoteKeys("mi/prefijo")
        assertEquals("mi/prefijo/photos/$assetId/$version/IMG_001.jpg", keys.photoKey(assetId, version, "IMG_001.jpg"))
    }

    @Test fun `same file name for different assets never collides`() {
        val keys = RemoteKeys("")
        val k1 = keys.photoKey("1".repeat(32), version, "IMG.jpg")
        val k2 = keys.photoKey("2".repeat(32), version, "IMG.jpg")
        assertTrue(k1 != k2)
    }

    @Test fun `new content version gives a new key for the same asset`() {
        val keys = RemoteKeys("p")
        assertTrue(keys.photoKey(assetId, "c".repeat(64), "a.jpg") != keys.photoKey(assetId, "d".repeat(64), "a.jpg"))
    }

    @Test fun `file names are sanitized`() {
        assertEquals("fotografia.jpg", RemoteKeys.sanitizeFileName("fotografía.jpg"))
        assertEquals("a_b.jpg", RemoteKeys.sanitizeFileName("a b.jpg"))
        assertEquals("passwd", RemoteKeys.sanitizeFileName("../../etc/passwd"))
        assertEquals("file", RemoteKeys.sanitizeFileName("***"))
    }

    @Test fun `parse round trips and rejects foreign keys`() {
        val keys = RemoteKeys("p/")
        val k = keys.photoKey(assetId, version, "x.png")
        val parsed = keys.parse(k)
        assertNotNull(parsed)
        assertEquals(assetId, parsed!!.assetId)
        assertEquals(version, parsed.versionId)
        assertEquals("x.png", parsed.fileName)
        assertNull(keys.parse("p/other/thing"))
        assertNull(keys.parse("q/photos/$assetId/$version/x.png"))
        assertFalse(keys.isPhotoKey("p/photos/not-a-uuid/$version/x.png"))
    }

    @Test fun `isWithinPrefix isolates the configured prefix`() {
        val keys = RemoteKeys("alice")
        assertTrue(keys.isWithinPrefix("alice/photos/x"))
        assertFalse(keys.isWithinPrefix("bob/photos/x"))
        assertFalse(keys.isWithinPrefix("alice-evil/photos/x")) // normalized prefix is "alice/"
        assertFalse(keys.isWithinPrefix("alice/../bob/x"))
    }

    @Test fun `invalid ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RemoteKeys("").photoKey("short", version, "a.jpg") }
        assertThrows(IllegalArgumentException::class.java) { RemoteKeys("").photoKey(assetId, "zz", "a.jpg") }
    }
}

class ConfigValidatorTest {
    @Test fun regions() {
        assertNull(ConfigValidator.validateRegion("eu-west-1"))
        assertNull(ConfigValidator.validateRegion("us-gov-west-1"))
        assertNotNull(ConfigValidator.validateRegion("europe"))
        assertNotNull(ConfigValidator.validateRegion(""))
    }

    @Test fun buckets() {
        assertNull(ConfigValidator.validateBucket("my-photos.backup"))
        assertNotNull(ConfigValidator.validateBucket("ab"))
        assertNotNull(ConfigValidator.validateBucket("My-Bucket"))
        assertNotNull(ConfigValidator.validateBucket("-bucket"))
        assertNotNull(ConfigValidator.validateBucket("bucket-"))
        assertNotNull(ConfigValidator.validateBucket("a..b"))
        assertNotNull(ConfigValidator.validateBucket("192.168.0.1"))
        assertNotNull(ConfigValidator.validateBucket("xn--bucket"))
        assertNotNull(ConfigValidator.validateBucket("a".repeat(64)))
    }

    @Test fun prefixes() {
        assertNull(ConfigValidator.validatePrefix(""))
        assertNull(ConfigValidator.validatePrefix("fotos/2026"))
        assertNotNull(ConfigValidator.validatePrefix("../etc"))
        assertNotNull(ConfigValidator.validatePrefix("a/./b"))
        assertNotNull(ConfigValidator.validatePrefix("a*"))
        assertNotNull(ConfigValidator.validatePrefix("a\u0000b"))
    }

    @Test fun credentials() {
        assertNull(ConfigValidator.validateAccessKeyId("AKIAIOSFODNN7EXAMPLE"))
        assertNotNull(ConfigValidator.validateAccessKeyId("short"))
        assertNotNull(ConfigValidator.validateSecret("tooshort"))
        assertNull(ConfigValidator.validateSecret("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
    }
}

class SyncStateMachineTest {
    @Test fun `happy path to backed up goes through verifying`() {
        val path = listOf(SyncState.LOCAL_ONLY, SyncState.QUEUED, SyncState.UPLOADING, SyncState.VERIFYING, SyncState.BACKED_UP)
        path.zipWithNext().forEach { (a, b) -> assertTrue("$a->$b", SyncStateMachine.canTransition(a, b)) }
    }

    @Test fun `cannot skip verification`() {
        assertFalse(SyncStateMachine.canTransition(SyncState.LOCAL_ONLY, SyncState.BACKED_UP))
        assertFalse(SyncStateMachine.canTransition(SyncState.QUEUED, SyncState.BACKED_UP))
        assertFalse(SyncStateMachine.canTransition(SyncState.UPLOADING, SyncState.BACKED_UP))
        assertThrows(IllegalStateException::class.java) { SyncStateMachine.require(SyncState.UPLOADING, SyncState.BACKED_UP) }
    }

    @Test fun `interrupted states recover to queued`() {
        assertEquals(SyncState.QUEUED, SyncStateMachine.recoveryTarget(SyncState.UPLOADING))
        assertEquals(SyncState.QUEUED, SyncStateMachine.recoveryTarget(SyncState.VERIFYING))
        assertNull(SyncStateMachine.recoveryTarget(SyncState.BACKED_UP))
    }

    @Test fun `modified backed up file returns to local only, failure can be retried`() {
        assertTrue(SyncStateMachine.canTransition(SyncState.BACKED_UP, SyncState.LOCAL_ONLY))
        assertTrue(SyncStateMachine.canTransition(SyncState.FAILED, SyncState.QUEUED))
    }

    @Test fun `remote only restore cycle`() {
        assertTrue(SyncStateMachine.canTransition(SyncState.REMOTE_ONLY, SyncState.RESTORING))
        assertTrue(SyncStateMachine.canTransition(SyncState.RESTORING, SyncState.REMOTE_ONLY))
    }
}

class PermissionReportTest {
    private fun report(vararg c: Pair<Boolean, RemoteErrorKind?>) =
        PermissionReport(c.mapIndexed { i, (ok, e) -> PermissionCheck("op$i", ok, e) })

    @Test fun `missing permissions are not a blocking error`() {
        val r = report(true to null, false to RemoteErrorKind.ACCESS_DENIED, false to null)
        org.junit.Assert.assertNull(r.blockingError)
    }

    @Test fun `a wrong region is reported instead of missing permissions`() {
        val r = report(false to RemoteErrorKind.WRONG_REGION, false to RemoteErrorKind.WRONG_REGION, false to null)
        org.junit.Assert.assertEquals(RemoteErrorKind.WRONG_REGION, r.blockingError)
    }

    @Test fun `a fully allowed report has no blocking error`() {
        org.junit.Assert.assertNull(report(true to null, true to null).blockingError)
    }
}

class ThumbnailKeysTest {
    private val version = "b".repeat(64)

    @Test fun `previews live outside photos, keyed by content`() {
        val keys = RemoteKeys("mi/prefijo")
        val k = keys.thumbnailKey(version)
        assertEquals("mi/prefijo/.s3vault/thumbs/$version.jpg", k)
        assertTrue(keys.isThumbnailKey(k))
        assertFalse("a preview is never a photo", keys.isPhotoKey(k))
        assertNull(keys.parse(k))
    }

    @Test fun `without a prefix previews sit at the root of the vault folder`() {
        assertEquals(".s3vault/thumbs/$version.jpg", RemoteKeys("").thumbnailKey(version))
    }

    @Test fun `only well formed preview keys are accepted`() {
        val keys = RemoteKeys("p")
        assertFalse(keys.isThumbnailKey("p/.s3vault/thumbs/short.jpg"))
        assertFalse(keys.isThumbnailKey("p/.s3vault/thumbs/$version.png"))
        assertFalse(keys.isThumbnailKey("p/.s3vault/albums.json"))
        assertFalse(keys.isThumbnailKey("q/.s3vault/thumbs/$version.jpg"))
        assertFalse(keys.isThumbnailKey("p/.s3vault/thumbs/../$version.jpg"))
        assertThrows(IllegalArgumentException::class.java) { keys.thumbnailKey("nothex") }
    }
}
