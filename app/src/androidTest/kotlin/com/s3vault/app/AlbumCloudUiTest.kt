package com.s3vault.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
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
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Albums whose photos are only in S3 (a library migrated straight into the bucket): the manifest in the bucket is imported,
 * the album shows its S3 photos with their previews, and no original is downloaded while looking at them.
 */
class AlbumCloudUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private lateinit var server: MockWebServer
    private lateinit var s3: FakeS3Dispatcher
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
            container.db.clearAllTables()
            container.credentials.clear()
            container.settings.clearS3Config()
            container.previews.clear()
        }
        server.close()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun jpeg(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    /** What the migration script leaves behind: the photo under photos/{asset}/{sha}/{name}, and its preview. */
    private fun migrated(name: String, color: Int): String {
        val original = "original-of-$name".toByteArray()
        val hash = sha(original)
        val asset = "%032x".format(name.hashCode().toLong() and 0xffffffffL)
        s3.objects[keys.photoKey(asset, hash, name)] = FakeS3Dispatcher.Obj(original, sha64(original), "image/jpeg", emptyMap())
        val thumb = jpeg(color)
        s3.objects[keys.thumbnailKey(hash)] = FakeS3Dispatcher.Obj(thumb, sha64(thumb), "image/jpeg", emptyMap())
        return hash
    }

    private fun publish(json: String) {
        s3.objects[keys.albumManifestKey] = FakeS3Dispatcher.Obj(json.toByteArray(), sha64(json.toByteArray()), "application/json", emptyMap())
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        java.io.File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())
    private fun shown(t: String, substring: Boolean = false) = compose.onAllNodesWithText(t, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(ms: Long = 20_000, cond: () -> Boolean) {
        try {
            compose.waitUntil(ms, cond)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out. S3 saw ${s3.methods} gets=${s3.getKeys}. On screen: " + compose.onRoot().printToString(), e)
        }
    }
    private fun cell(name: String) = compose.onNode(hasContentDescription(name, substring = true))

    @Test fun anAlbumOfPhotosOnlyInS3IsImportedBrowsedAndEditedWithoutDownloadingOriginals() {
        val a = migrated("ui_a.jpg", Color.RED)
        val b = migrated("ui_b.jpg", Color.GREEN)
        val c = migrated("ui_c.jpg", Color.BLUE)
        val ghost = sha("not in the bucket".toByteArray())
        publish("""{"version":1,"albums":[{"name":"Viaje","items":["$a","$b","$c","$ghost"]},{"name":"Fantasma","items":["$ghost"]}]}""")

        tab("Álbumes").performClick()
        waitFor { shown("Aún no hay álbumes") }
        compose.onNodeWithText("Importar álbumes de S3").performClick()

        waitFor { shown("Álbumes nuevos: 1", substring = true) }
        assertTrue(shown("solo están en S3", substring = true))
        assertTrue("the photo that is nowhere is reported", shown("1 foto no se encontró", substring = true))
        compose.onNodeWithText("Viaje").assertIsDisplayed()
        compose.onNodeWithText("3 elementos").assertIsDisplayed()
        repeat(6) { Thread.sleep(250); compose.waitForIdle() }
        shot("a1-albums-imported")
        assertTrue("an album with nothing to show is not created", !shown("Fantasma"))

        compose.onNodeWithText("Viaje").performClick()
        waitFor { shown("Fotos del álbum en S3") }
        compose.waitForIdle()
        Thread.sleep(1500) // let the preview GETs finish
        compose.waitForIdle()
        cell("ui_a.jpg").assertIsDisplayed()
        cell("ui_b.jpg").assertIsDisplayed()
        cell("ui_c.jpg").assertIsDisplayed()
        shot("a2-album-detail")
        assertTrue("previews were read", s3.getKeys.any { it.contains(".s3vault/thumbs/") })
        assertTrue("no original was downloaded to show the album", s3.getKeys.none { keys.isPhotoKey(it) })

        // Taking a photo out of the album forgets the membership and nothing else.
        cell("ui_b.jpg").performTouchInput { longClick() }
        compose.onNodeWithText("1 seleccionada").assertIsDisplayed()
        shot("a3-selection")
        compose.onNodeWithContentDescription("Quitar del álbum").performClick()
        waitFor { compose.onAllNodesWithContentDescription("ui_b.jpg", substring = true).fetchSemanticsNodes().isEmpty() }
        cell("ui_a.jpg").assertIsDisplayed()
        val id = runBlocking(Dispatchers.IO) { container.db.albums().all().single().id }
        assertEquals(setOf(a, c), runBlocking(Dispatchers.IO) { container.db.albums().cloudMemberVersions(id).toSet() })
        assertTrue("the object is still in the bucket", s3.objects.keys.any { it.contains("/$b/") })
        assertTrue(s3.deleteBatches.isEmpty())
    }
}
