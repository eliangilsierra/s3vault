package com.s3vault.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.s3vault.app.data.privacy.KeystoreKeyring
import com.s3vault.app.data.privacy.PrivateVaultStore
import com.s3vault.app.domain.privacy.KeyringResult
import com.s3vault.app.domain.privacy.PrivateMeta
import com.s3vault.app.domain.privacy.UnlockPrompt
import com.s3vault.app.domain.privacy.VaultAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The real Android Keystore and the system authentication prompt, with a PIN set on the emulator through `locksettings` and typed
 * through `input`. Nothing here is mocked: the key is a Keystore key that needs the credential for every use (Android 11+).
 */
class PrivateKeystoreDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val container get() = (ctx.applicationContext as S3VaultApp).container
    private val pin = "2468"
    private val prompt = UnlockPrompt("Álbumes privados", "Prueba")
    private val vaultDir get() = File(ctx.filesDir, "vault")
    private var pinSet = false

    private fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Before fun setUp() {
        compose.waitForIdle()
        val out = shell("locksettings set-pin $pin")
        pinSet = !out.contains("Error", ignoreCase = true) && !out.contains("Exception")
        assumeTrue("could not set a PIN on this device: $out", pinSet && shell("locksettings verify --old $pin").contains("successfully"))
        container.keyringOverride = null
        runBlocking(Dispatchers.IO) {
            container.privateSession.lock()
            container.db.clearAllTables()
            vaultDir.deleteRecursively()
            KeystoreKeyring(ctx).deleteKey()
        }
    }

    @After fun tearDown() {
        container.privateSession.lock()
        runBlocking(Dispatchers.IO) { container.db.clearAllTables(); vaultDir.deleteRecursively() }
        KeystoreKeyring(ctx).deleteKey()
        if (pinSet) shell("locksettings clear --old $pin")
    }

    /** Waits for the system prompt to be up, types the PIN and confirms. */
    private fun typePin() {
        Thread.sleep(2_500)
        shell("input text $pin")
        shell("input keyevent 66")
    }

    private fun <T> withPin(block: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default)
        val job: Deferred<T> = scope.async { block() }
        typePin()
        return runBlocking { withTimeout(30_000) { job.await() } }
    }

    @Test fun theKeyIsCreatedBehindTheSystemPromptAndSurvivesALockAndUnlock() {
        val keyring = KeystoreKeyring(ctx)
        assertTrue("the device reports a secure lock screen", keyring.isSecure())
        val created = withPin { keyring.create(prompt) } as KeyringResult.Key
        assertEquals(32, created.key.size)
        val again = withPin { keyring.unwrap(created.wrapped, prompt) } as KeyringResult.Key
        assertArrayEquals("the same vault key comes back after authenticating again", created.key, again.key)
    }

    @Test fun cancellingThePromptReturnsCancelledAndNoKeyExists() {
        val keyring = KeystoreKeyring(ctx)
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.async { keyring.create(prompt) }
        Thread.sleep(2_500)
        // The first back hides the PIN keyboard, the second closes the prompt.
        shell("input keyevent 4"); Thread.sleep(800); shell("input keyevent 4")
        val r = runBlocking { withTimeout(30_000) { job.await() } }
        assertEquals(KeyringResult.Cancelled, r)
    }

    @Test fun theWholeStoreWorksOnTheRealKeystoreAndAnAlteredWrappedKeyIsRefused() {
        val store = container.privateVault
        assertEquals(VaultAvailability.NOT_CREATED, runBlocking { store.availability() })
        val first = withPin { store.unlock(prompt) }
        assertEquals(PrivateVaultStore.UnlockOutcome.Unlocked, first)
        val album = runBlocking { store.createAlbum("Real") }
        val data = ByteArray(200_000) { (it * 31).toByte() }
        runBlocking { store.add(album, { ByteArrayInputStream(data) }, null, PrivateMeta("real.jpg", "image/jpeg", data.size.toLong(), 0, 0, 1, "", "")) }
        store.lock()
        assertTrue(!container.privateSession.unlocked.value)
        val second = withPin { store.unlock(prompt) }
        assertEquals(PrivateVaultStore.UnlockOutcome.Unlocked, second)
        val item = runBlocking { store.item(container.db.privateVault().items(album).single().id)!! }
        assertArrayEquals(data, runBlocking { store.imageBytes(item) })
        store.lock()
        // A wrapped key that was altered in the database cannot be unwrapped: the GCM tag fails and the vault reports a lost key.
        runBlocking(Dispatchers.IO) {
            val row = container.db.privateVault().vault()!!
            val bad = row.wrappedKey.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            container.db.openHelper.writableDatabase.execSQL("UPDATE private_vault SET wrappedKey = x'${bad.joinToString("") { "%02x".format(it) }}'")
        }
        val third = withPin { store.unlock(prompt) }
        assertTrue("an altered wrapped key is refused, got $third", third is PrivateVaultStore.UnlockOutcome.KeyLost)
        assertEquals(VaultAvailability.KEY_LOST, runBlocking { store.availability() })
    }

    @Test fun removingTheScreenLockDestroysTheKeyAndTheVaultReportsItHonestly() {
        val store = container.privateVault
        assertEquals(PrivateVaultStore.UnlockOutcome.Unlocked, withPin { store.unlock(prompt) })
        store.lock()
        // Android deletes keys that require a screen lock when the lock is removed.
        shell("locksettings clear --old $pin")
        pinSet = false
        val out = runBlocking { store.unlock(prompt) }
        assertTrue("no key without a lock screen, got $out", out is PrivateVaultStore.UnlockOutcome.KeyLost)
        assertEquals(VaultAvailability.KEY_LOST, runBlocking { store.availability() })
    }
}
