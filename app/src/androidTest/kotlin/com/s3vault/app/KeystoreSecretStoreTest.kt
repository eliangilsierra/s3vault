package com.s3vault.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.s3vault.app.auth.CredentialsRepository
import com.s3vault.app.security.KeystoreSecretStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs against the real Android Keystore on the device/emulator. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = KeystoreSecretStore(context, keyAlias = "s3vault_test_key")

    @After fun cleanup() {
        store.delete("test_secret")
        store.delete(CredentialsRepository.SECRET_NAME + "_t")
    }

    @Test fun roundTrip() {
        val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".toByteArray()
        store.put("test_secret", secret)
        assertArrayEquals(secret, store.get("test_secret"))
    }

    @Test fun secretIsNotStoredInPlaintext() {
        val marker = "SUPER-SECRET-MARKER-1234567890"
        store.put("test_secret", marker.toByteArray())
        val file = File(File(context.noBackupFilesDir, "secrets"), "test_secret.bin")
        assertTrue(file.exists())
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("ciphertext must not contain the plaintext", raw.contains(marker))
    }

    @Test fun differentIvEachTime() {
        store.put("test_secret", "same".toByteArray())
        val a = File(File(context.noBackupFilesDir, "secrets"), "test_secret.bin").readBytes()
        store.put("test_secret", "same".toByteArray())
        val b = File(File(context.noBackupFilesDir, "secrets"), "test_secret.bin").readBytes()
        assertFalse(a.contentEquals(b))
    }

    @Test fun survivesNewInstanceAndCanBeDeleted() {
        store.put("test_secret", "persist".toByteArray())
        val again = KeystoreSecretStore(context, keyAlias = "s3vault_test_key")
        assertEquals("persist", String(again.get("test_secret")!!))
        again.delete("test_secret")
        assertNull(store.get("test_secret"))
    }

    @Test fun tamperedFileIsTreatedAsAbsent() {
        store.put("test_secret", "data".toByteArray())
        val f = File(File(context.noBackupFilesDir, "secrets"), "test_secret.bin")
        val bytes = f.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte() // break the GCM tag
        f.writeBytes(bytes)
        assertNull(store.get("test_secret"))
    }

    @Test fun invalidNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { store.put("../evil", byteArrayOf(1)) }
    }

    @Test fun credentialsRepositoryPersistsEncryptedAndClears() = runBlocking {
        val repo = CredentialsRepository(store)
        repo.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        val file = File(File(context.noBackupFilesDir, "secrets"), CredentialsRepository.SECRET_NAME + ".bin")
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(raw.contains("wJalrXUtnFEMI"))
        // Fresh repository over the same store reads it back
        assertEquals("AKIAIOSFODNN7EXAMPLE", CredentialsRepository(store).getCredentials().accessKeyId)
        repo.clear()
        assertFalse(file.exists())
    }
}
