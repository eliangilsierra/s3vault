package com.s3vault.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
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

/**
 * The auditor in the real app (real WorkManager worker, real S3 client) against an in-memory S3: one backed-up photo whose object
 * is there, one whose object is gone. The server records every method, so the test also proves the audit only reads.
 */
class IntegrityUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private lateinit var server: MockWebServer
    private lateinit var s3: FakeS3Dispatcher
    private val keys = RemoteKeys("ui/")
    private val version = "a".repeat(64)
    private val size = 2_400_000L

    private fun keyFor(id: Long, name: String) = keys.photoKey("%032x".format(id), version, name)

    private fun media(id: Long, name: String) = MediaItemEntity(
        mediaStoreId = id, uri = "content://media/external/images/media/$id", kind = MediaKind.IMAGE, displayName = name, mimeType = "image/jpeg",
        sizeBytes = size, dateModifiedSec = 10, dateTakenMs = 1_700_000_000_000L - id, width = 4000, height = 3000, folderName = "Camera",
        assetId = "%032x".format(id), syncState = SyncState.BACKED_UP, remoteKey = keyFor(id, name), remoteVersionId = version,
        lastBackupAtMs = System.currentTimeMillis() - 86_400_000, backedUpSizeBytes = size, backedUpDateModifiedSec = 10,
    )

    @Before fun setUp() {
        compose.waitUntil(30_000) { container.library.hasScanned || container.mediaAccess() == com.s3vault.app.sync.MediaAccess.NONE }
        s3 = FakeS3Dispatcher("test-bucket", allowedPrefix = "ui/")
        server = MockWebServer().also { it.dispatcher = s3; it.start() }
        // Only the first photo's object exists in the bucket (declared size is what the listing reports).
        s3.objects[keyFor(9101, "Playa.jpg")] = FakeS3Dispatcher.Obj(ByteArray(0), "", "image/jpeg", emptyMap(), "STANDARD", size)
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            container.settings.clearS3Config()
            container.credentials.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
            container.settings.saveS3Config(S3Config("us-east-1", "test-bucket", "ui/", AuthMethod.STATIC))
            container.settings.setAuditIntervalDays(0)
            container.db.media().insertAll(listOf(media(9101, "Playa.jpg"), media(9102, "Cena.jpg")))
        }
        container.endpointOverride = server.url("/").toString().trimEnd('/')
    }

    @After fun tearDown() {
        container.endpointOverride = null
        runBlocking(Dispatchers.IO) {
            container.scheduler.cancelAudit()
            container.db.clearAllTables()
            container.credentials.clear()
            container.settings.clearS3Config()
        }
        server.close()
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The screen is a lazy list: scroll the wanted text into composition, then wait until it is there. */
    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) {
            runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring)) }
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openIntegrity() {
        compose.onNodeWithContentDescription("Más opciones").performClick()
        compose.onNodeWithText("Herramientas de la biblioteca").performClick()
        compose.onNodeWithText("Auditoría del respaldo").performClick()
    }

    @Test fun anAuditFindsTheMissingObjectExplainsItAndOnlyReads() {
        openIntegrity()
        waitForText("Todavía no se ha auditado")
        compose.onNodeWithText("Qué puede y qué no puede comprobar").performScrollTo().performClick()
        compose.onNodeWithText("La lista de S3 demuestra", substring = true).performScrollTo().assertIsDisplayed()
        shot("integrity-before")

        compose.onNodeWithText("Auditar con la lista de S3").performScrollTo().performClick()

        waitForText("1 objeto con problemas")
        waitForText("Cena.jpg")
        compose.onNodeWithText("Cena.jpg").assertIsDisplayed()
        compose.onNodeWithText("No aparece en la lista completa del bucket.").assertIsDisplayed()
        // The healthy one is one filter away.
        compose.onNodeWithText("Verificadas · 1").performScrollTo().assertIsDisplayed()
        shot("integrity-after")
        assertTrue("the audit only reads from S3: ${s3.methods}", s3.methods.none { it == "PUT" || it == "DELETE" || it == "POST" })
        assertTrue("one LIST request", s3.methods.count { it == "GET" } == 1)
        assertEquals("nothing local was changed by the audit", SyncState.BACKED_UP, runBlocking(Dispatchers.IO) { container.db.media().get(9102)!!.syncState })
    }

    @Test fun reuploadIsOfferedWithItsConsequencesAndNeverRunsWithoutConfirmation() {
        openIntegrity()
        compose.onNodeWithText("Auditar con la lista de S3").performScrollTo().performClick()
        waitForText("Cena.jpg")
        compose.onNodeWithText("Cena.jpg").performClick() // selects the row
        compose.onNodeWithContentDescription("Volver a subir…").performClick()
        compose.onNodeWithText("¿Volver a subir 1 foto?").assertIsDisplayed()
        compose.onNodeWithText("sobrescribe", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        compose.waitForIdle()
        assertEquals("cancelling changes nothing", SyncState.BACKED_UP, runBlocking(Dispatchers.IO) { container.db.media().get(9102)!!.syncState })
        assertTrue(s3.methods.none { it == "PUT" })
    }

    @Test fun theDeepAuditShowsItsCostBeforeAskingAndChecksTheChecksum() {
        openIntegrity()
        waitForText("Verificación profunda (2 objetos)")
        compose.onNodeWithText("2 solicitudes HEAD", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Verificación profunda (2 objetos)").performScrollTo().performClick()
        // Neither object has a SHA-256 in the fake S3 (declared listing only), so nothing is claimed as verified by checksum.
        waitForText("Verificación profunda ·", substring = true)
        assertTrue("HEAD requests were made, no body was downloaded", s3.methods.count { it == "HEAD" } >= 2 && s3.getKeys.isEmpty())
    }
}
