package com.s3vault.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The real app on a real device: select photos, choose which copy to delete, confirm, see progress and results, and
 * preview S3 photos. S3 is an in-memory stand-in on localhost; the media are real MediaStore files.
 * Screenshots go to the app's external files dir (`.../files/shots`) for visual review.
 */
class PhotoManagementUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as S3VaultApp
    private val container get() = app.container
    private lateinit var server: MockWebServer
    private lateinit var s3: FakeS3Dispatcher
    private val inserted = mutableListOf<Uri>()
    private val keys = RemoteKeys("ui/")

    @Before fun setUp() {
        s3 = FakeS3Dispatcher("test-bucket", allowedPrefix = "ui/")
        server = MockWebServer().also { it.dispatcher = s3; it.start() }
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            container.credentials.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
            container.settings.saveS3Config(S3Config("eu-west-1", "test-bucket", "ui/", AuthMethod.STATIC))
        }
        container.endpointOverride = server.url("/").toString().trimEnd('/')
    }

    @After fun tearDown() {
        container.endpointOverride = null
        runBlocking(Dispatchers.IO) {
            inserted.forEach { runCatching { instrumentation.targetContext.contentResolver.delete(it, null, null) } }
            instrumentation.targetContext.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("ui_%"),
            )
            container.db.clearAllTables()
            container.credentials.clear()
            container.settings.clearS3Config()
            container.previews.clear()
        }
        server.close()
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private fun photo(name: String, hue: Int): ByteArray {
        val w = 1200; val h = 900
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.8f, 0.95f)), Color.HSVToColor(floatArrayOf((hue + 70f) % 360, 0.9f, 0.35f)), Shader.TileMode.CLAMP)
        })
        val rnd = java.util.Random(name.hashCode().toLong())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(60) {
            p.color = Color.argb(160, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            canvas.drawCircle(rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), 30f + rnd.nextInt(120), p)
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun insert(name: String, hue: Int) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/S3VaultUiTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = instrumentation.targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(photo(name, hue)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        inserted += uri
    }

    /** Three photos on the device; the first two are backed up (with previews) through the app's own engine. */
    private fun seed() = runBlocking(Dispatchers.IO) {
        insert("ui_a.jpg", 10); insert("ui_b.jpg", 140); insert("ui_c.jpg", 250)
        container.library.refresh()
        val rows = container.db.media().getEverything().filter { it.displayName.startsWith("ui_") }
        assertEquals(3, rows.size)
        rows.filter { it.displayName != "ui_c.jpg" }.forEach { container.db.media().enqueue(it.mediaStoreId, 1L) }
        container.backupEngine.run(1)
        container.catalog.refresh(container.remoteProvider.get()!!)
        assertEquals(2, container.db.media().getEverything().count { it.syncState == SyncState.BACKED_UP })
        assertEquals("two photos and two previews", 4, s3.objects.size)
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Android's own "Allow S3 Vault to delete this photo?" dialog belongs to another package, so Compose cannot see it.
     * Give it time to appear, save a screenshot, and press "Allow" with a tap at its position (right-hand button of the
     * dialog, measured on the 1080x2400 emulator and expressed relative to the screen size).
     */
    private fun allowSystemDeleteDialog() {
        // Compose tests only render frames while the test thread is inside a Compose call: run one pass now so the
        // effect that launches Android's dialog actually executes, then stop touching Compose while the dialog is up.
        compose.waitForIdle()
        val ua = instrumentation.uiAutomation
        fun shell(cmd: String) = java.io.FileInputStream(ua.executeShellCommand(cmd).fileDescriptor).use { String(it.readBytes()) }
        fun focus() = shell("dumpsys window").lineSequence().firstOrNull { it.contains("mCurrentFocus") }.orEmpty()
        fun waitFocus(want: (String) -> Boolean) {
            val end = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < end && !want(focus())) Thread.sleep(300)
        }
        waitFocus { it.contains("media") || it.contains("PermissionActivity") } // Android's own (media provider) dialog
        Thread.sleep(1_200) // let it finish animating
        // screencap sees every window (UiAutomation.takeScreenshot may not include another app's dialog).
        val png = java.io.FileInputStream(ua.executeShellCommand("screencap -p").fileDescriptor).use { it.readBytes() }
        File(File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }, "system-delete-dialog.png").writeBytes(png)
        val dm = instrumentation.targetContext.resources.displayMetrics
        // "Allow" is the right-hand button of the dialog (measured on the 1080x2400 emulator, expressed relative to the screen).
        shell("input tap ${(dm.widthPixels * 0.828).toInt()} ${(dm.heightPixels * 0.642).toInt()}")
        waitFocus { it.contains("com.s3vault.app/") }
    }

    private fun waitFor(ms: Long = 20_000, cond: () -> Boolean) = compose.waitUntil(ms, cond)
    private fun textShown(t: String, substring: Boolean = false) = compose.onAllNodesWithText(t, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun descShown(d: String) = compose.onAllNodesWithContentDescription(d, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun cell(name: String) = compose.onNodeWithContentDescription(name, substring = true)
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun waitForGallery() = waitFor { compose.onAllNodesWithContentDescription("ui_a.jpg", substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun select(name: String) {
        cell(name).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    private fun mediaRow(name: String) = runBlocking(Dispatchers.IO) { container.db.media().getEverything().first { it.displayName == name } }

    // ------------------------------------------------------------------ flows

    @Test fun cloudDeleteFromTheGalleryKeepsTheLocalPhoto() {
        seed()
        waitForGallery()
        select("ui_a.jpg")
        compose.onNodeWithText("1 seleccionada").assertIsDisplayed()
        compose.onNodeWithText("Todas con copia en S3").assertIsDisplayed()
        shot("01-gallery-selection")

        compose.onNodeWithContentDescription("Eliminar…").performClick()
        compose.onNodeWithText("Eliminar 1 elemento").assertIsDisplayed()
        compose.onNodeWithText("Del dispositivo (1)").assertIsDisplayed()
        compose.onNodeWithText("De Amazon S3 (1)").assertIsDisplayed()
        shot("02-choose-copy-sheet")

        compose.onNodeWithText("De Amazon S3 (1)").performClick()
        compose.onNodeWithText("¿Eliminar 1 elemento de S3?").assertIsDisplayed()
        compose.onNodeWithText("Las copias de este dispositivo (1) no se tocan", substring = true).assertIsDisplayed()
        shot("03-confirm-cloud")
        // The photo exists on the device, so no extra acknowledgement is needed.
        compose.onNodeWithText("Eliminar de S3").assertIsEnabled()
        compose.onNodeWithText("Eliminar de S3").performClick()

        waitFor { textShown("Eliminadas de S3: 1", substring = true) }
        shot("04-cloud-deleted")
        assertEquals("only the other photo and its preview remain", 2, s3.objects.size)
        val a = mediaRow("ui_a.jpg")
        assertEquals(SyncState.LOCAL_ONLY, a.syncState)
        assertTrue(a.backupExcluded)
        cell("ui_a.jpg").assertIsDisplayed() // still in the gallery
        assertTrue("the device copy was never deleted", runBlocking { container.mediaGateway.exists(a.uri) })
    }

    @Test fun deviceDeleteKeepsTheS3CopyAndThePhotoThenAppearsInTheS3Library() {
        seed()
        waitForGallery()
        select("ui_b.jpg")
        compose.onNodeWithContentDescription("Eliminar…").performClick()
        compose.onNodeWithText("Del dispositivo (1)").performClick()
        compose.onNodeWithText("¿Eliminar 1 elemento del dispositivo?").assertIsDisplayed()
        compose.onNodeWithText("1 siguen en Amazon S3", substring = true).assertIsDisplayed()
        shot("05-confirm-device")
        compose.onNodeWithText("Eliminar del dispositivo").performClick()

        allowSystemDeleteDialog()
        waitFor(40_000) { textShown("Eliminadas del dispositivo: 1", substring = true) }
        shot("06-device-deleted")
        val b = mediaRow("ui_b.jpg")
        assertEquals(SyncState.REMOTE_ONLY, b.syncState)
        assertFalse(b.present)
        assertEquals("S3 untouched by a local deletion", 4, s3.objects.size)
        assertTrue(s3.deleteBatches.isEmpty())

        // The S3 library shows the photo with its preview, and the viewer says it is a preview, not the original.
        tab("S3").performClick()
        waitFor { descShown("ui_b.jpg") }
        compose.waitForIdle()
        Thread.sleep(1500) // let the thumbnail GET finish
        shot("07-s3-library")
        cell("ui_b.jpg").performClick()
        waitFor { textShown("Vista previa · no es el original", substring = true) }
        repeat(8) { Thread.sleep(250); compose.waitForIdle() } // Compose only renders frames while the test is inside a Compose call
        shot("08-cloud-viewer")
        assertTrue("only previews were fetched while looking", s3.getKeys.none { keys.isPhotoKey(it) })
    }

    @Test fun deletingAPhotoThatOnlyExistsInS3NeedsAnExplicitAcknowledgement() {
        seed()
        runBlocking(Dispatchers.IO) {
            // ui_b leaves the device (outside the app), so S3 holds the only copy.
            val b = mediaRow("ui_b.jpg")
            instrumentation.targetContext.contentResolver.delete(Uri.parse(b.uri), null, null)
            container.library.refresh()
        }
        tab("S3").performClick()
        waitFor { descShown("ui_b.jpg") }
        cell("ui_b.jpg").performTouchInput { longClick() }
        compose.onNodeWithText("Solo existen en S3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Eliminar de S3…").performClick()

        compose.onNodeWithText("¿Eliminar 1 elemento de S3?").assertIsDisplayed()
        compose.onNodeWithText("1 solo existen en S3 y se perderán para siempre.").assertIsDisplayed()
        compose.onNodeWithText("Eliminar de S3").assertIsNotEnabled()
        shot("09-confirm-cloud-only")
        compose.onNodeWithText("Entiendo que no se podrán recuperar").performClick()
        compose.onNodeWithText("Eliminar de S3").assertIsEnabled()
        compose.onNodeWithText("Eliminar de S3").performClick()

        waitFor { textShown("Eliminadas de S3: 1", substring = true) }
        assertEquals(2, s3.objects.size)
        assertNull(runBlocking(Dispatchers.IO) { container.db.media().getEverything().firstOrNull { it.displayName == "ui_b.jpg" } })
    }

    @Test fun anIamPolicyThatForbidsDeletingIsExplainedAndNothingIsLost() {
        seed()
        s3.allowDelete = false
        waitForGallery()
        select("ui_a.jpg")
        compose.onNodeWithContentDescription("Eliminar…").performClick()
        compose.onNodeWithText("De Amazon S3 (1)").performClick()
        compose.onNodeWithText("Eliminar de S3").performClick()

        waitFor { textShown("No se pudieron eliminar 1 de 1") }
        compose.onNodeWithText("tu política IAM no permite borrar objetos", substring = true).assertIsDisplayed()
        shot("10-delete-denied")
        assertEquals(4, s3.objects.size)
        assertEquals(SyncState.BACKED_UP, mediaRow("ui_a.jpg").syncState)

        // Retry after the policy is fixed.
        s3.allowDelete = true
        compose.onNodeWithText("Reintentar").performClick()
        waitFor { textShown("Eliminadas de S3: 1", substring = true) }
        assertEquals(2, s3.objects.size)
    }

    @Test fun theChoiceSheetOffersOnlyTheLocationsThatExist() {
        seed()
        waitForGallery()
        select("ui_c.jpg") // never backed up
        compose.onNodeWithContentDescription("Eliminar…").performClick()
        compose.onNodeWithText("Del dispositivo (1)").assertIsDisplayed()
        compose.onNodeWithText("Ninguno está respaldado en S3.").assertIsDisplayed()
        compose.onNodeWithText("No tienen copia en S3.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Del dispositivo (1)").performClick()
        compose.onNodeWithText("1 NO tienen copia en S3 y se perderán para siempre.").assertIsDisplayed()
        shot("11-confirm-device-no-backup")
        compose.onNodeWithText("Cancelar").performClick()
        assertTrue("cancelling deletes nothing", mediaRow("ui_c.jpg").present)
        assertTrue(s3.deleteBatches.isEmpty())
    }
}
