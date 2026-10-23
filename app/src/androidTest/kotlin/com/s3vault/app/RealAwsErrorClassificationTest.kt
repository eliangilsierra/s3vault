package com.s3vault.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.s3vault.app.auth.CredentialsRepository
import com.s3vault.app.data.remote.S3RemoteStorage
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.S3Config
import com.s3vault.app.security.InMemorySecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Talks to the real Amazon S3 endpoint over HTTPS using AWS's published *documentation* example key (not a real
 * credential, owned by nobody) and a bucket name that cannot exist in a real account. It needs no AWS account and
 * touches no resources; it only checks that a real S3 rejection is classified correctly and leaks nothing.
 * Skipped silently when the device has no Internet.
 */
@RunWith(AndroidJUnit4::class)
class RealAwsErrorClassificationTest {
    private val fakeCreds = CredentialsRepository(InMemorySecretStore()).also {
        it.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
    }

    @Test fun realS3RejectsExampleCredentialsAndWeClassifyItWithoutLeakingSecrets() = runBlocking {
        val bucket = "s3vault-test-" + java.util.UUID.randomUUID().toString().take(12)
        val storage = S3RemoteStorage(S3Config("eu-west-1", bucket, "", AuthMethod.STATIC), fakeCreds)
        val e = try {
            storage.checkConnection()
            null
        } catch (e: RemoteException) {
            e
        } finally {
            storage.close()
        }
        assertNotNull("S3 must not accept example credentials", e)
        e!!
        if (e.kind == RemoteErrorKind.NETWORK) return@runBlocking // offline emulator: nothing to verify
        println("REAL_AWS kind=${e.kind} message=${e.message}")
        assertEquals(RemoteErrorKind.CREDENTIALS_INVALID, e.kind)
        assertFalse(e.message!!.contains("wJalrXUtnFEMI"))
        assertFalse(e.message!!.contains("AKIAIOSFODNN7EXAMPLE"))
    }
}
