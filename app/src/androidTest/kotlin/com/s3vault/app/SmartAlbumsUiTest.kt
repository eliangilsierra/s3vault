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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasClickAction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.RemoteObjectEntity
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File

/** Smart albums in the real app, over rows written straight into Room (what the albums query), including the S3 ones. */
class SmartAlbumsUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private val nowSec get() = System.currentTimeMillis() / 1000

    private fun media(
        id: Long, name: String, folder: String = "Camera", kind: MediaKind = MediaKind.IMAGE, size: Long = 2_000_000, favorite: Boolean = false,
        state: SyncState = SyncState.LOCAL_ONLY, dayKey: Int = 20240315, addedAgoDays: Long = 400,
    ) = MediaItemEntity(
        mediaStoreId = id, uri = "content://media/external/images/media/$id", kind = kind, displayName = name, mimeType = "image/jpeg", sizeBytes = size,
        dateModifiedSec = 10, dateTakenMs = 1_700_000_000_000L - id, width = 4000, height = 3000, folderName = folder, assetId = "%032x".format(id),
        syncState = state, isFavorite = favorite, dayKey = dayKey, dateAddedSec = nowSec - addedAgoDays * 86_400,
    )

    @Before fun setUp() {
        compose.waitUntil(30_000) { container.library.hasScanned || container.mediaAccess() == com.s3vault.app.sync.MediaAccess.NONE }
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            container.settings.clearS3Config()
            container.db.media().insertAll(
                listOf(
                    media(9301, "Playa.jpg", favorite = true, addedAgoDays = 3),
                    media(9302, "Captura_1.png", folder = "Screenshots", addedAgoDays = 2),
                    media(9303, "Clip.mp4", kind = MediaKind.VIDEO, size = 60_000_000),
                    media(9304, "Boda_2023.jpg", dayKey = 20230610),
                ),
            )
            container.settings.saveS3Config(S3Config("us-east-1", "bucket", "p/", AuthMethod.STATIC))
            container.db.remote().upsertAll(
                listOf(RemoteObjectEntity(key = "p/photos/${"2".repeat(32)}/${"b".repeat(64)}/Solo_nube.jpg", assetId = "2".repeat(32), versionId = "b".repeat(64), displayName = "Solo_nube.jpg", sizeBytes = 3_000_000, lastModifiedMs = 1_690_000_000_000L)),
            )
        }
        compose.onNode(hasText("Álbumes") and hasClickAction()).performClick()
    }

    @After fun tearDown() {
        runBlocking(Dispatchers.IO) { container.db.clearAllTables(); container.settings.clearS3Config() }
        // Opening a cloud album applies its filters to the shared S3 library query, which lives as long as the process.
        container.libraryStore.local.value = com.s3vault.app.domain.library.LibraryQuery()
        container.libraryStore.cloud.value = com.s3vault.app.domain.library.LibraryQuery()
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Text anywhere on screen; a lazy list is scrolled to it only when the text is not there yet. */
    private fun waitForText(text: String, substring: Boolean = false) {
        fun there() = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        try {
            compose.waitUntil(30_000) {
                if (!there()) runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring)) }
                there()
            }
        } catch (e: Throwable) { shot("timeout-" + text.take(24).replace(Regex("[^A-Za-z0-9]"), "_")); throw e }
    }

    /** A grid cell is described, not written: its name is in the content description. */
    private fun waitForCell(name: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(name, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun cellShown(name: String) = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(name, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theAlbumsTabOffersTheSmartAlbumsThatHaveSomethingWithoutAddingATab() {
        waitForText("Álbumes inteligentes")
        waitForText("Favoritas · 1")
        shot("smart-strip")
        compose.onNodeWithText("Favoritas · 1").performClick()
        waitForCell("Playa.jpg")
        assertTrue("only the favourite is in the album", !cellShown("Boda_2023.jpg"))
    }

    @Test fun theFullListShowsEveryKindOfAlbumWithHonestCloudNumbers() {
        waitForText("Ver todos")
        compose.onNodeWithText("Ver todos").performClick()
        waitForText("En este dispositivo")
        waitForText("Archivos grandes")
        waitForText("1 elemento · 25 MB o más")
        waitForText("Por año")
        waitForText("2024")
        waitForText("En Amazon S3")
        // The S3 section says where its numbers come from before anything else.
        waitForText("Aún no hay lista de S3")
        waitForText("Solo en S3")
        shot("smart-all")
        compose.onNodeWithText("2023").performScrollTo().performClick()
        waitForCell("Boda_2023.jpg")
        assertTrue(!cellShown("Playa.jpg"))
    }

    @Test fun aCustomAlbumShowsItsLiveCountBeforeSavingAndFillsItself() {
        waitForText("Ver todos")
        compose.onNodeWithText("Ver todos").performClick()
        compose.onNodeWithContentDescription("Crear álbum inteligente").performClick()
        waitForText("Nuevo álbum inteligente")
        compose.onNodeWithText("Nombre").performTextInput("Mis favoritas")
        waitForText("Sin condiciones: incluiría todo (4 elementos).")
        compose.onNodeWithText("Solo favoritas").performScrollTo().performClick()
        waitForText("Ahora coinciden 1 elemento.", substring = true)
        shot("smart-editor")
        compose.onNodeWithText("Guardar álbum").performScrollTo().performClick()
        waitForText("Tus álbumes inteligentes")
        waitForText("Mis favoritas")

        // A photo that becomes a favourite enters the album with no bookkeeping.
        runBlocking(Dispatchers.IO) { container.db.media().update(container.db.media().get(9304)!!.copy(isFavorite = true)) }
        waitForText("2 elementos")
    }

    @Test fun cloudAlbumsOpenTheS3LibraryWithTheirFiltersApplied() {
        waitForText("Ver todos")
        compose.onNodeWithText("Ver todos").performClick()
        waitForText("Solo en S3")
        compose.onNodeWithText("Solo en S3").performScrollTo().performClick()
        waitForText("Biblioteca en S3")
        waitForText("Solo en S3")
        assertEquals("the S3 library opened with the album's filter", true, container.libraryStore.cloud.value.filters.availability != null)
    }
}
