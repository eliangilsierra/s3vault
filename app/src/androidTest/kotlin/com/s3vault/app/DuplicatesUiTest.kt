package com.s3vault.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/**
 * Duplicate detection on real files in the real MediaStore, decoded by the real platform decoder: two identical files, a resized
 * copy of the same picture and an unrelated picture. The scan runs in the real WorkManager worker.
 */
class DuplicatesUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private val inserted = mutableListOf<android.net.Uri>()

    /** The same scene at any size: shapes are placed in proportion to the picture, like a photo exported at two resolutions. */
    private fun scene(seed: Int, w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(seed.toLong())
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)), Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)), Shader.TileMode.CLAMP)
        })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(9) {
            p.color = Color.argb(220, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, (0.08f + rnd.nextFloat() * 0.2f) * w, p)
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    @Before fun setUp() {
        compose.waitUntil(30_000) { container.library.hasScanned || container.mediaAccess() == com.s3vault.app.sync.MediaAccess.NONE }
        runBlocking(Dispatchers.IO) { container.db.clearAllTables() }
        val same = scene(1, 800, 600)
        inserted += BulkPerfSupport.insertBytes("Playa_original.jpg", same, "Pictures/S3VaultDup")
        inserted += BulkPerfSupport.insertBytes("Playa_copia_whatsapp.jpg", same.copyOf(), "Pictures/S3VaultDup")
        inserted += BulkPerfSupport.insertBytes("Playa_pequena.jpg", scene(1, 400, 300), "Pictures/S3VaultDup")
        inserted += BulkPerfSupport.insertBytes("Montana.jpg", scene(77, 800, 600), "Pictures/S3VaultDup")
        runBlocking(Dispatchers.IO) {
            container.library.refresh(force = true)
            // The emulator has photos of its own; only the four above take part (nothing rescans while the screen under test is open).
            container.db.openHelper.writableDatabase.execSQL("DELETE FROM media_items WHERE folderName != 'S3VaultDup'")
        }
    }

    @After fun tearDown() {
        container.scheduler.cancelDuplicateScan()
        inserted.forEach { BulkPerfSupport.delete(it) }
        runBlocking(Dispatchers.IO) { container.db.clearAllTables() }
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(60_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        try {
            compose.waitUntil(60_000) {
                runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring)) }
                compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) { shot("timeout-" + text.take(20).replace(Regex("[^A-Za-z0-9]"), "_")); throw e }
    }

    private fun open() {
        compose.onNodeWithContentDescription("Más opciones").performClick()
        compose.onNodeWithText("Herramientas de la biblioteca").performClick()
        compose.onNodeWithText("Duplicados y parecidas").performClick()
    }

    @Test fun identicalFilesAreExactAndAResizedCopyIsOnlyProbable() {
        open()
        waitForText("Buscar duplicados")
        compose.onNodeWithText("Buscar duplicados").performScrollTo().performClick()

        waitForText("Idénticas · 1")
        waitForText("2 copias idénticas")
        waitForText("Playa_copia_whatsapp.jpg")
        shot("duplicates-exact")
        assertTrue("the unrelated picture is in no group", compose.onAllNodesWithText("Montana.jpg").fetchSemanticsNodes().isEmpty())
        // Local and cloud availability are told apart for each copy.
        assertTrue(compose.onAllNodesWithText("En el dispositivo").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("S3: sin copia").fetchSemanticsNodes().isNotEmpty())

        // Similar: the identical pair plus the resized copy are one probable group, clearly labelled as such; it is not an exact one.
        compose.onNodeWithText("Parecidas", substring = true).performScrollTo().performClick()
        waitForText("3 fotos parecidas (probable)")
        waitForText("Playa_pequena.jpg")
        compose.onNodeWithText("Coincidencias probables").assertIsDisplayed()
        assertTrue("an exact pair is not repeated as a similar finding on its own", compose.onAllNodesWithText("Montana.jpg").fetchSemanticsNodes().isEmpty())
        shot("duplicates-similar")
    }

    @Test fun deletingTheOthersAsksForConfirmationAndNeverTouchesS3() {
        open()
        compose.onNodeWithText("Buscar duplicados").performScrollTo().performClick()
        waitForText("Playa_copia_whatsapp.jpg")
        compose.onNodeWithContentDescription("Playa_copia_whatsapp.jpg, se puede eliminar", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Eliminar las otras 1 del dispositivo", substring = true).performScrollTo().performClick()
        waitForText("¿Eliminar 1 elemento del dispositivo?")
        compose.onNodeWithText("Libera unos", substring = true).assertIsDisplayed()
        shot("duplicates-confirm")
        compose.onNodeWithText("Cancelar").performClick()
        compose.waitForIdle()
        val present = runBlocking(Dispatchers.IO) { container.db.media().getEverything().count { it.present && it.folderName == "S3VaultDup" } }
        assertEquals("cancelling deletes nothing", 4, present)
    }

    @Test fun aDismissedGroupStaysDismissed() {
        open()
        compose.onNodeWithText("Buscar duplicados").performScrollTo().performClick()
        waitForText("2 copias idénticas")
        compose.onNodeWithContentDescription("Más acciones del grupo").performScrollTo().performClick()
        compose.onNodeWithText("No son duplicadas").performClick()
        waitForText("Volver a proponer 1 grupo descartado")
        assertTrue(compose.onAllNodesWithText("2 copias idénticas").fetchSemanticsNodes().isEmpty())
    }
}
