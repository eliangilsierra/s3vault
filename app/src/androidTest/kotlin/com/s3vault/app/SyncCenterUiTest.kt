package com.s3vault.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.data.local.JobEntity
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.RemoteObjectEntity
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.RemoteErrorKind
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

/**
 * The sync centre in the real app, on rows written straight into Room (the single source of truth it reads), so every state can
 * be shown without a bucket. Screenshots go to the app's external files dir (`.../files/shots`) for visual review.
 */
class SyncCenterUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container

    private fun media(id: Long, name: String, state: SyncState, error: String? = null, remoteKey: String? = null, present: Boolean = true) = MediaItemEntity(
        mediaStoreId = id, uri = "content://media/external/images/media/$id", kind = MediaKind.IMAGE, displayName = name, mimeType = "image/jpeg",
        sizeBytes = 2_400_000, dateModifiedSec = 10, dateTakenMs = 1_700_000_000_000L - id * 86_400_000L, width = 4000, height = 3000, folderName = "Camera",
        assetId = "%032x".format(id), present = present, syncState = state, remoteKey = remoteKey, remoteVersionId = remoteKey?.let { "a".repeat(64) },
        lastError = error, lastErrorPermanent = error != null, queuedAtMs = if (state == SyncState.QUEUED) id else null,
        lastBackupAtMs = if (state == SyncState.BACKED_UP) System.currentTimeMillis() - 3_600_000 else null,
    )

    @Before fun setUp() {
        // The gallery scans MediaStore when it first shows; wait for that so it cannot remove the rows seeded below.
        compose.waitUntil(30_000) { container.library.hasScanned || container.mediaAccess() == com.s3vault.app.sync.MediaAccess.NONE }
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            container.settings.clearS3Config()
            container.settings.setPaused(false)
            container.db.media().insertAll(
                listOf(
                    media(9001, "Playa_amanecer.jpg", SyncState.BACKED_UP, remoteKey = "p/photos/${"1".repeat(32)}/${"a".repeat(64)}/Playa_amanecer.jpg"),
                    media(9002, "Cumple_tarta.jpg", SyncState.QUEUED),
                    media(9003, "Recibo_hotel.jpg", SyncState.FAILED, error = RemoteErrorKind.ACCESS_DENIED.userMessage + " [403]"),
                    media(9004, "Captura_sin_subir.jpg", SyncState.LOCAL_ONLY),
                ),
            )
            container.db.remote().upsertAll(
                listOf(
                    RemoteObjectEntity(
                        key = "p/photos/${"2".repeat(32)}/${"b".repeat(64)}/Solo_en_la_nube.jpg", assetId = "2".repeat(32), versionId = "b".repeat(64),
                        displayName = "Solo_en_la_nube.jpg", sizeBytes = 3_000_000, lastModifiedMs = 1_690_000_000_000L,
                    ),
                ),
            )
        }
        tab("Respaldo").performClick()
    }

    @After fun tearDown() {
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            container.settings.clearS3Config()
            container.settings.setPaused(false)
        }
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The list is lazy: bring the row into composition by scrolling to it, then wait until it is really there. */
    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring)) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun state(id: Long) = runBlocking(Dispatchers.IO) { container.db.media().get(id)!! }

    @Test fun overviewCountsOnlyVerifiedBackupsAndExplainsWhyNothingMoves() {
        waitForText("de 4 respaldadas y verificadas")
        compose.onNodeWithText("Amazon S3 no está configurado").assertIsDisplayed()
        compose.onNodeWithText("Actividad · 2", substring = true).assertIsDisplayed() // queued + failed
        compose.onNodeWithText("Solo en S3 · 1", substring = true).performScrollTo().assertIsDisplayed()
        shot("sync-center-overview")
    }

    @Test fun activityListsWhatIsMovingOrNeedsAttention() {
        waitForText("Cumple_tarta.jpg")
        waitForText("Recibo_hotel.jpg")
        assertTrue("a finished backup is not activity", compose.onAllNodesWithText("Playa_amanecer.jpg").fetchSemanticsNodes().isEmpty())
        assertTrue("an untouched file is not activity", compose.onAllNodesWithText("Captura_sin_subir.jpg").fetchSemanticsNodes().isEmpty())
    }

    @Test fun aFailedItemExplainsTheReasonAndRetryReturnsItToTheQueue() {
        waitForText("Recibo_hotel.jpg")
        compose.onNodeWithText("Recibo_hotel.jpg").performScrollTo().performClick()
        waitForText("Permisos insuficientes", substring = true)
        compose.onNodeWithText("Revisa la política IAM", substring = true).assertIsDisplayed()
        shot("sync-center-failed-expanded")
        compose.onNodeWithText("Reintentar", substring = false).performScrollTo().performClick()
        compose.waitUntil(15_000) { state(9003).syncState == SyncState.QUEUED }
        assertEquals("the old error is gone once the user retries", null, state(9003).lastError)
    }

    @Test fun cancellingAQueuedUploadKeepsTheFileAndTheOtherQueueUntouched() {
        waitForText("Cumple_tarta.jpg")
        compose.onNodeWithText("Cumple_tarta.jpg").performScrollTo().performClick()
        compose.onNodeWithText("Cancelar subida").performScrollTo().performClick()
        compose.waitUntil(15_000) { state(9002).syncState == SyncState.LOCAL_ONLY }
        assertTrue(state(9002).backupExcluded)
        assertEquals(SyncState.FAILED, state(9003).syncState)
        assertEquals("the photo itself is untouched", true, state(9002).present)
    }

    @Test fun theCloudOnlyFilterSaysTheObjectIsNotOnThisDevice() {
        waitForText("Solo en S3 · 1", substring = true)
        compose.onNodeWithText("Solo en S3 · 1", substring = true).performScrollTo().performClick()
        waitForText("Solo_en_la_nube.jpg")
        compose.onNodeWithText("No está en este dispositivo").assertIsDisplayed()
        shot("sync-center-cloud-only")
    }

    @Test fun anInterruptedRunIsShownAsInterruptedAfterTheProcessRestarts() {
        runBlocking(Dispatchers.IO) {
            container.db.jobs().upsert(JobEntity(JobNames.BACKUP, JobState.RUNNING, 3, 10, 3, 0, startedAtMs = 1, updatedAtMs = 2))
            container.jobs.recoverInterrupted(processStartMs = System.currentTimeMillis())
        }
        waitForText("Subidas: interrumpida", substring = true)
    }

    @Test fun pausingIsPersistentAndTheQueueIsKept() {
        waitForText("Pausar")
        compose.onNodeWithText("Pausar").performScrollTo().performClick()
        waitForText("Respaldo en pausa")
        assertEquals(true, runBlocking(Dispatchers.IO) { container.settings.currentBackup().paused })
        assertEquals("pausing keeps the queue", SyncState.QUEUED, state(9002).syncState)
        assertTrue("the paused state offers to resume", compose.onAllNodesWithText("Reanudar").fetchSemanticsNodes().isNotEmpty())
    }
}
