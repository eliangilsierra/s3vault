package com.s3vault.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.data.local.IntegrityReason
import com.s3vault.app.data.local.IntegrityResultEntity
import com.s3vault.app.data.local.IntegrityVerdict
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.VerifyLevel
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.space.SafetyRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Freeing space in the real app: which photos are offered and why the others are not, the review of a batch, and the last check
 * against S3 (real client, in-memory S3) that runs before Android is asked to delete anything. The test stops at Android's own
 * confirmation: seeded rows have no real file behind them.
 */
class SpaceRecoveryUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private lateinit var server: MockWebServer
    private lateinit var s3: FakeS3Dispatcher
    private val keys = RemoteKeys("ui/")
    private val day = 24L * 3600 * 1000

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = sha(b).joinToString("") { "%02x".format(it) }

    private val inserted = mutableListOf<android.net.Uri>()

    /**
     * A backed-up photo: a real file in MediaStore (the last check re-reads the library, so a made-up row would be dropped as gone)
     * whose object exists in the fake S3 with its real SHA-256, unless [inS3] is false.
     */
    private fun seed(name: String, favorite: Boolean = false, audited: Boolean = true, inS3: Boolean = true): MediaItemEntity {
        val bytes = "contenido-de-$name-${System.nanoTime()}".toByteArray()
        val uri = BulkPerfSupport.insertBytes(name, bytes).also { inserted += it }
        runBlocking(Dispatchers.IO) { container.library.refresh(force = true) }
        val id = uri.lastPathSegment!!.toLong()
        val version = hex(bytes)
        val now = System.currentTimeMillis()
        val key = keys.photoKey("%032x".format(id), version, name)
        if (inS3) s3.objects[key] = FakeS3Dispatcher.Obj(bytes, Base64.getEncoder().encodeToString(sha(bytes)), "image/jpeg", mapOf("sha256-hex" to version))
        return runBlocking(Dispatchers.IO) {
            val row = container.db.media().get(id)!!
            val item = row.copy(
                syncState = SyncState.BACKED_UP, remoteKey = key, remoteVersionId = version, lastBackupAtMs = now - 100 * day,
                backedUpSizeBytes = row.sizeBytes, backedUpDateModifiedSec = row.dateModifiedSec, isFavorite = favorite,
            )
            container.db.media().update(item)
            if (audited) container.db.integrity().upsertResults(listOf(IntegrityResultEntity(key, IntegrityVerdict.VERIFIED, VerifyLevel.LISTING, IntegrityReason.NONE, now - 3_600_000, expectedSize = row.sizeBytes)))
            item
        }
    }

    @Before fun setUp() {
        compose.waitUntil(30_000) { container.library.hasScanned || container.mediaAccess() == com.s3vault.app.sync.MediaAccess.NONE }
        s3 = FakeS3Dispatcher("test-bucket", allowedPrefix = "ui/")
        server = MockWebServer().also { it.dispatcher = s3; it.start() }
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            container.settings.clearS3Config()
            // A file made a moment ago is "recent" and "backed up a moment ago": those two rules are what the tests below are not about.
            container.settings.saveSpaceRules(SafetyRules(keepRecentDays = 0, minBackedUpDays = 0))
            container.credentials.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
            container.settings.saveS3Config(S3Config("us-east-1", "test-bucket", "ui/", AuthMethod.STATIC))
        }
        container.endpointOverride = server.url("/").toString().trimEnd('/')
    }

    @After fun tearDown() {
        container.endpointOverride = null
        inserted.forEach { BulkPerfSupport.delete(it) }
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            container.credentials.clear()
            container.settings.clearS3Config()
            container.settings.saveSpaceRules(SafetyRules())
        }
        server.close()
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) {
            runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring)) }
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun open() {
        compose.onNodeWithContentDescription("Más opciones").performClick()
        compose.onNodeWithText("Herramientas de la biblioteca").performClick()
        compose.onNodeWithText("Liberar espacio").performClick()
    }

    @Test fun onlyConfirmedPhotosAreOfferedAndTheRestIsExplained() {
        seed("Playa.jpg")
        seed("SinConfirmar.jpg", audited = false)
        seed("Favorita.jpg", favorite = true) // last: every seed rescans MediaStore, which knows nothing of this flag
        open()
        waitForText("Esto libera espacio del móvil, no reduce tu factura de AWS")
        waitForText("Verificadas en S3 · 1")
        waitForText("Se conservan por tus reglas: 1")
        waitForText("Playa.jpg")
        assertTrue("a favourite is kept by the default rules", compose.onAllNodesWithText("Favorita.jpg").fetchSemanticsNodes().isEmpty())
        assertTrue("so is a photo nobody confirmed recently", compose.onAllNodesWithText("SinConfirmar.jpg").fetchSemanticsNodes().isEmpty())
        shot("space-overview")
    }

    @Test fun reviewThenCheckInS3ThenAndroidConfirmationWithoutTouchingS3() {
        val playa = seed("Playa.jpg")
        open()
        waitForText("Playa.jpg")
        compose.onNodeWithText("Playa.jpg").performClick()
        compose.onNodeWithText("Revisar y liberar 1 elemento").performClick()
        compose.onNodeWithText("Revisar el lote").assertIsDisplayed()
        compose.onNodeWithText("Solo se borra la copia de este móvil", substring = true).assertIsDisplayed()
        shot("space-review")
        compose.onNodeWithText("Comprobar y continuar").performClick()

        // The last check really asked S3 (HEAD), and then Android's confirmation for the device copy takes over.
        compose.waitUntil(30_000) { compose.onAllNodesWithText("¿Eliminar 1 elemento del dispositivo?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Comprobadas ahora en S3", substring = true).assertIsDisplayed()
        assertTrue("HEAD was requested: ${s3.methods}", s3.methods.contains("HEAD"))
        assertTrue("S3 is only read: ${s3.methods}", s3.methods.none { it == "PUT" || it == "DELETE" || it == "POST" })
        shot("space-confirm-device")
        compose.onNodeWithText("Cancelar").performClick()
        compose.waitForIdle()
        assertEquals("cancelling leaves the photo backed up and on the device", SyncState.BACKED_UP, runBlocking(Dispatchers.IO) { container.db.media().get(playa.mediaStoreId)!!.syncState })
    }

    @Test fun anObjectMissingInS3IsNeverDeletedEvenWhenSelected() {
        seed("Playa.jpg")
        val lost = seed("Perdida.jpg", inS3 = false, audited = false) // backed up, but the object is gone and nobody noticed yet
        open()
        waitForText("Sin verificar · 1")
        compose.onNodeWithText("Sin verificar · 1").performScrollTo().performClick()
        waitForText("Perdida.jpg")
        compose.onNodeWithText("Perdida.jpg").performClick()
        compose.onNodeWithText("Revisar y liberar 1 elemento").performClick()
        compose.onNodeWithText("Comprobar y continuar").performClick()
        // S3 answers "not found": the gate drops it and says so; nothing reaches Android's dialog.
        waitForText("No se eliminó nada")
        compose.onNodeWithText("1 · El objeto ya no existe en S3", substring = true).assertIsDisplayed()
        shot("space-blocked")
        compose.onNodeWithText("Entendido").performClick()
        assertEquals(SyncState.BACKED_UP, runBlocking(Dispatchers.IO) { container.db.media().get(lost.mediaStoreId)!!.syncState })
    }

    @Test fun theRulesSheetShowsCautiousDefaultsAndSavesChanges() {
        seed("Playa.jpg")
        open()
        waitForText("Playa.jpg")
        compose.onNodeWithContentDescription("Reglas de seguridad").performClick()
        waitForText("Estas reglas solo hacen la lista más corta", substring = true)
        compose.onNodeWithText("Conservar las favoritas").assertIsDisplayed()
        shot("space-rules")
        compose.onNodeWithText("Guardar reglas").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(runBlocking(Dispatchers.IO) { container.settings.currentSpaceRules().excludeFavorites })
    }
}
