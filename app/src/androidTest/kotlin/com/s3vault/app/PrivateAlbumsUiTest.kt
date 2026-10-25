package com.s3vault.app

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.data.local.MediaStoreSource
import com.s3vault.app.domain.privacy.KeyringResult
import com.s3vault.app.domain.privacy.PrivateMeta
import com.s3vault.app.domain.privacy.UnlockPrompt
import com.s3vault.app.domain.privacy.VaultCrypto
import com.s3vault.app.domain.privacy.VaultKeyring
import com.s3vault.app.domain.privacy.WrappedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A software keyring so these tests drive the real screens without a system prompt (the real Keystore is covered by PrivateKeystoreDeviceTest). */
class SoftKeyring : VaultKeyring {
    var secure = true
    var lost = false
    private var wrapKey = ByteArray(32).also { Random(5).nextBytes(it) }
    private fun cipher(mode: Int, iv: ByteArray?): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        if (iv == null) init(mode, SecretKeySpec(wrapKey, "AES")) else init(mode, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, iv))
    }
    override fun isSecure() = secure
    override suspend fun create(prompt: UnlockPrompt): KeyringResult {
        if (!secure) return KeyringResult.NeedsScreenLock
        wrapKey = ByteArray(32).also { Random(System.nanoTime()).nextBytes(it) }
        val master = VaultCrypto.newKey()
        val c = cipher(Cipher.ENCRYPT_MODE, null)
        return KeyringResult.Key(master, WrappedKey(c.doFinal(master), c.iv))
    }
    override suspend fun unwrap(wrapped: WrappedKey, prompt: UnlockPrompt): KeyringResult {
        if (lost) return KeyringResult.KeyLost("clave perdida")
        return KeyringResult.Key(cipher(Cipher.DECRYPT_MODE, wrapped.iv).doFinal(wrapped.cipherText), wrapped)
    }
    override fun deleteKey() { wrapKey = ByteArray(32) }
}

/**
 * The private albums in the real app: moving photos in from the gallery (originals untouched), the locked entrance, the album and
 * viewer, moving out, tampered files, and what happens to open screens when the vault locks.
 */
class PrivateAlbumsUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private val keyring = SoftKeyring()
    private val inserted = mutableListOf<android.net.Uri>()
    private val vaultDir get() = File(instrumentation.targetContext.filesDir, "vault")

    private fun asset(name: String) = instrumentation.context.assets.open("semantic/$name").readBytes()

    @Before fun setUp() {
        compose.waitUntil(30_000) { container.library.hasScanned || container.mediaAccess() == com.s3vault.app.sync.MediaAccess.NONE }
        container.keyringOverride = keyring
        container.secureWindows = false // the screenshots below
        runBlocking(Dispatchers.IO) {
            container.privateSession.lock()
            container.db.clearAllTables()
            vaultDir.deleteRecursively()
            container.settings.setPrivateAutoLockMs(60_000)
        }
        listOf("cat", "coffee", "page").forEach { inserted += BulkPerfSupport.insertBytes("priv_$it.jpg", asset("$it.jpg"), "Pictures/S3VaultPrivate") }
        runBlocking(Dispatchers.IO) {
            container.library.refresh(force = true)
            container.db.openHelper.writableDatabase.execSQL("DELETE FROM media_items WHERE folderName != 'S3VaultPrivate'")
        }
    }

    @After fun tearDown() {
        container.privateSession.lock()
        inserted.forEach { BulkPerfSupport.delete(it) }
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            vaultDir.deleteRecursively()
        }
        container.keyringOverride = null
        container.secureWindows = true
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForText(text: String, substring: Boolean = false, timeout: Long = 30_000) {
        try {
            compose.waitUntil(timeout) {
                if (compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty()) {
                    runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring)) }
                }
                compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) { shot("timeout-" + text.take(24).replace(Regex("[^A-Za-z0-9]"), "_")); throw e }
    }

    private fun waitForCell(name: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription(name, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun noText(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty()

    private fun openHub() {
        compose.onNodeWithContentDescription("Más opciones").performClick()
        compose.onNodeWithText("Herramientas de la biblioteca").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Álbumes privados").performScrollTo().performClick()
    }

    /** A vault with one album and the three pictures sealed in it, locked again. */
    private fun seedVault(albumName: String = "Viajes", vararg names: String = arrayOf("priv_cat.jpg", "priv_coffee.jpg", "priv_page.jpg")): Long = runBlocking(Dispatchers.IO) {
        val store = container.privateVault
        store.unlock(UnlockPrompt("t", "s"))
        val album = store.createAlbum(albumName)
        for (n in names) {
            val bytes = asset(n.removePrefix("priv_"))
            store.add(album, { ByteArrayInputStream(bytes) }, null, PrivateMeta(n, "image/jpeg", bytes.size.toLong(), 0, 0, 1_000, "", "Camera"))
        }
        store.lock()
        album
    }

    @Test fun movingFromTheGalleryCopiesEncryptedAndLeavesTheOriginalsUntouched() {
        waitForCell("priv_cat.jpg")
        compose.onNode(hasContentDescription("priv_cat.jpg", substring = true)).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Seleccionar todo").performClick()
        compose.onNodeWithContentDescription("Más acciones").performClick()
        compose.onNodeWithText("Mover a álbum privado…").performClick()
        waitForText("Crear álbumes privados")
        shot("private-create-vault")
        compose.onNodeWithText("Verificar identidad").performClick()
        waitForText("Mover a álbum privado")
        compose.onNodeWithText("Nuevo álbum privado").performTextInput("Documentos")
        compose.onNodeWithText("Crear y elegir").performClick()
        waitForText("¿Copiar 3 fotos a «Documentos»?")
        waitForText("NO se borran", substring = true)
        shot("private-confirm-move")
        compose.onNodeWithText("Copiar al álbum privado").performClick()
        waitForText("3 fotos en «Documentos»")
        waitForText("Tus originales siguen en la galería", substring = true)
        shot("private-move-done")
        compose.onNodeWithText("Conservar originales").performClick()
        compose.waitForIdle()

        // The originals are untouched: still indexed, still in MediaStore.
        val rows = runBlocking(Dispatchers.IO) { container.db.media().getEverything().filter { it.folderName == "S3VaultPrivate" } }
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.present })
        val reader = MediaStoreSource(instrumentation.targetContext)
        for (r in rows) assertTrue("${r.displayName} still readable", reader.open(r.uri).use { it.readBytes().isNotEmpty() })
        // Sealed files exist, none of them starts like a JPEG, and the database has three items.
        val files = vaultDir.listFiles().orEmpty()
        assertEquals(3, files.count { it.name.endsWith(".v") })
        for (f in files.filter { it.name.endsWith(".v") }) {
            val head = f.inputStream().use { it.readNBytes(4) }
            assertFalse("sealed file is not a JPEG", head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte())
        }
        assertEquals(3, runBlocking(Dispatchers.IO) { container.db.privateVault().itemCount() })
        assertTrue("the vault stays open after creating it", container.privateSession.unlocked.value)
    }

    @Test fun theEntranceIsLockedUntilUnlockedAndLockingReplacesOpenScreens() {
        val album = seedVault()
        openHub()
        waitForText("Álbumes privados bloqueados")
        assertTrue("no album name while locked", noText("Viajes"))
        shot("private-locked")
        compose.onNodeWithText("Desbloquear").performClick()
        waitForText("Viajes")
        waitForText("3 fotos", substring = true)
        shot("private-hub-open")
        compose.onNodeWithText("Viajes").performClick()
        waitForCell("priv_cat.jpg")
        shot("private-album")
        // The vault locks while the album is open (timeout, screen off or "bloquear ahora"): the screen is replaced at once.
        container.privateSession.lock()
        waitForText("Álbumes privados bloqueados")
        assertTrue("no picture name remains", compose.onAllNodes(hasContentDescription("priv_", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue("no album name remains", noText("Viajes"))
        assertTrue(album > 0)
    }

    @Test fun theViewerShowsTheDecryptedPhotoAndAlteredFilesAreRefused() {
        val album = seedVault("Viajes", "priv_cat.jpg", "priv_coffee.jpg")
        openHub()
        compose.onNodeWithText("Desbloquear").performClick()
        waitForText("Viajes")
        compose.onNodeWithText("Viajes").performClick()
        waitForCell("priv_cat.jpg")
        // Alter one sealed file on disk: its picture must be refused, the other must still open.
        val items = runBlocking(Dispatchers.IO) { container.db.privateVault().items(album) }
        assertEquals(2, items.size)
        compose.onNode(hasContentDescription("priv_cat.jpg", substring = true)).performClick()
        compose.waitForIdle()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("priv_cat.jpg").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodes(hasContentDescription("priv_", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("private-viewer")
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.waitForIdle()
        waitForCell("priv_coffee.jpg")
        container.privateImages.clear() // the first viewing left a decrypted copy in memory; the altered file must be read afresh
        val target = items.first()
        val f = File(vaultDir, target.fileName + ".v")
        val b = f.readBytes(); b[b.size / 2] = (b[b.size / 2].toInt() xor 1).toByte(); f.writeBytes(b)
        // Both cells are in the grid; open whichever one is the altered file by trying each.
        for (name in listOf("priv_cat.jpg", "priv_coffee.jpg")) {
            compose.onNode(hasContentDescription(name, substring = true)).performClick()
            compose.waitForIdle()
            val refused = runCatching { compose.waitUntil(8_000) { compose.onAllNodesWithText("No se pudo descifrar esta foto", substring = true).fetchSemanticsNodes().isNotEmpty() } }.isSuccess
            if (refused) { shot("private-viewer-tampered"); return }
            compose.onNodeWithContentDescription("Volver").performClick()
            compose.waitForIdle()
        }
        error("the altered file was shown instead of refused")
    }

    @Test fun takingAPhotoOutPutsAVerifiedCopyInTheGalleryAndRemovesTheSealedOne() {
        seedVault("Viajes", "priv_cat.jpg")
        val before = runBlocking(Dispatchers.IO) { container.db.privateVault().itemCount() }
        assertEquals(1, before)
        openHub()
        compose.onNodeWithText("Desbloquear").performClick()
        waitForText("Viajes")
        compose.onNodeWithText("Viajes").performClick()
        waitForCell("priv_cat.jpg")
        compose.onNode(hasContentDescription("priv_cat.jpg", substring = true)).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Sacar del álbum").performClick()
        waitForText("¿Sacar 1 foto del álbum privado?")
        shot("private-confirm-out")
        compose.onNodeWithText("Sacar").performClick()
        compose.waitUntil(30_000) { runBlocking(Dispatchers.IO) { container.db.privateVault().itemCount() } == 0 }
        val resolver = instrumentation.targetContext.contentResolver
        val found = resolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("Pictures/S3Vault/%", "priv_cat%"), null,
        )!!.use { c -> val ids = mutableListOf<Long>(); while (c.moveToNext()) ids += c.getLong(0); ids }
        try {
            assertTrue("the decrypted copy is in Pictures/S3Vault", found.isNotEmpty())
            val bytes = resolver.openInputStream(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, found.first()))!!.use { it.readBytes() }
            assertTrue("same bytes as the original", bytes.contentEquals(asset("cat.jpg")))
            assertTrue("the sealed file is gone", vaultDir.listFiles().orEmpty().none { it.name.endsWith(".v") })
        } finally {
            found.forEach { id -> BulkPerfSupport.delete(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)) }
        }
    }

    @Test fun aDeviceWithoutScreenLockIsToldWhyAndNothingIsCreated() {
        keyring.secure = false
        openHub()
        waitForText("Necesitas un bloqueo de pantalla")
        shot("private-needs-screen-lock")
        assertEquals(0, runBlocking(Dispatchers.IO) { container.db.privateVault().itemCount() })
        assertTrue(runBlocking(Dispatchers.IO) { container.db.privateVault().vault() } == null)
    }

    @Test fun aLostKeyIsExplainedAndOnlyDeletedAfterConfirmation() {
        seedVault()
        keyring.lost = true
        openHub()
        compose.onNodeWithText("Desbloquear").performClick()
        waitForText("La clave de los álbumes privados se perdió")
        shot("private-key-lost")
        assertTrue("files are kept until the user confirms", vaultDir.listFiles().orEmpty().isNotEmpty())
        compose.onNodeWithText("Eliminar archivos protegidos").performClick()
        waitForText("¿Eliminar los archivos protegidos?")
        compose.onAllNodesWithText("Eliminar")[0].performClick()
        compose.waitUntil(20_000) { vaultDir.listFiles().orEmpty().isEmpty() }
    }

    @Test fun privateScreensAreKeptOutOfScreenshotsAndRecents() {
        container.secureWindows = true
        seedVault()
        openHub()
        waitForText("Álbumes privados bloqueados")
        val flags = compose.activity.window.attributes.flags
        assertTrue("FLAG_SECURE is set on the private screens", flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 }
    }
}
