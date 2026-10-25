package com.s3vault.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.data.local.RemoteObjectEntity
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.MediaKind
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

/**
 * A bulk deletion from S3 as the user lives it: the progress is a card, not a wall; the gallery under it keeps working;
 * a second deletion is refused with a message; cancelling is safe; and a partial failure is reported honestly and can be
 * retried for the failed objects only. 250 objects, one DeleteObjects request each time (S3's limit is 1 000).
 */
class BulkCloudDeleteUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private lateinit var server: MockWebServer
    private lateinit var s3: FakeS3Dispatcher
    private val keys = RemoteKeys("bc/")
    private val total = 250
    private lateinit var objectKeys: List<String>

    private val cell = hasContentDescription("bc_", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    @Before fun setUp() {
        s3 = FakeS3Dispatcher("test-bucket", allowedPrefix = "bc/")
        server = MockWebServer().also { it.dispatcher = s3; it.start() }
        val now = System.currentTimeMillis()
        objectKeys = (1..total).map { keys.photoKey("%032x".format(it), "%064x".format(it), "bc_${"%04d".format(it)}.jpg") }
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            container.credentials.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
            container.settings.saveS3Config(S3Config("eu-west-1", "test-bucket", "bc/", AuthMethod.STATIC))
            container.db.remote().upsertAll(
                objectKeys.mapIndexed { i, k ->
                    val parsed = keys.parse(k)!!
                    RemoteObjectEntity(
                        key = k, assetId = parsed.assetId, versionId = parsed.versionId, displayName = parsed.fileName, sizeBytes = 10,
                        lastModifiedMs = now - i * 1_000L, effectiveDateMs = now - i * 1_000L, dayKey = 20261024, uploadDayKey = 20261024,
                        mediaKind = MediaKind.IMAGE,
                    )
                },
            )
            objectKeys.forEach { s3.objects[it] = FakeS3Dispatcher.Obj(ByteArray(8), "x", "image/jpeg", emptyMap()) }
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

    private fun waitFor(ms: Long = 30_000, cond: () -> Boolean) = compose.waitUntil(ms, cond)
    private fun textShown(t: String, substring: Boolean = false) = compose.onAllNodesWithText(t, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun selectAllAndAskToDelete() {
        compose.onNode(hasText("S3") and hasClickAction()).performClick()
        waitFor { compose.onAllNodes(cell).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(cell).onFirst().performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Seleccionar todo").performClick()
        waitFor { textShown("$total seleccionadas") }
        compose.onNodeWithContentDescription("Eliminar de S3…").performClick()
        waitFor { textShown("¿Eliminar $total elementos de S3?") } // the question is built off the main thread: wait for it
    }

    @Test fun theProgressIsACardAndTheGalleryUnderItKeepsWorking() {
        s3.deleteDelayMs = 2_500 // long enough to look around while the request is in flight
        selectAllAndAskToDelete()
        compose.onNodeWithText("Entiendo que no se podrán recuperar").performClick()
        compose.onNodeWithText("Eliminar de S3").performClick()

        // The operation is visible, with its size and a way out...
        waitFor { textShown("Eliminando $total elementos de S3…") }
        compose.onNodeWithText("Cancelar").assertIsDisplayed()
        // ...and the grid under it is alive: it scrolls and still answers to touch (a modal would swallow both).
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(20)
        compose.waitForIdle()
        assertTrue("cells are still there to look at", compose.onAllNodes(cell).fetchSemanticsNodes().isNotEmpty())
        // The selection ended when the operation started: a fresh one can be made while it runs.
        compose.onAllNodes(cell).onFirst().performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Eliminar de S3…").performClick()
        waitFor { textShown("Ya hay una eliminación en curso", substring = true) }

        waitFor(60_000) { textShown("Eliminadas de S3: $total", substring = true) }
        assertTrue("every object is gone from the bucket", s3.objects.isEmpty())
        assertEquals("one request for the photos, one for the previews", 2, s3.deleteBatches.size)
    }

    @Test fun cancellingStopsTheRunAndSaysWhatHappened() {
        s3.deleteDelayMs = 5_000
        selectAllAndAskToDelete()
        compose.onNodeWithText("Entiendo que no se podrán recuperar").performClick()
        compose.onNodeWithText("Eliminar de S3").performClick()
        waitFor { textShown("Eliminando $total elementos de S3…") }

        compose.onNodeWithText("Cancelar").performClick()

        waitFor { textShown("Cancelado tras procesar", substring = true) }
        assertTrue("the bar is gone", !textShown("Eliminando $total elementos de S3…"))
        // After a cancel the user can start again (the gate was released).
        s3.deleteDelayMs = 0
        compose.onAllNodes(cell).onFirst().performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Eliminar de S3…").performClick()
        waitFor { textShown("¿Eliminar 1 elemento de S3?") }
    }

    @Test fun aPartialFailureIsReportedAndOnlyTheFailedObjectsAreRetried() {
        val stubborn = objectKeys.take(5)
        stubborn.forEach { s3.failingKeys[it] = "SlowDown" } // S3 keeps throttling these five, even after the app's own retries
        selectAllAndAskToDelete()
        compose.onNodeWithText("Entiendo que no se podrán recuperar").performClick()
        compose.onNodeWithText("Eliminar de S3").performClick()

        waitFor(60_000) { textShown("No se pudieron eliminar 5 de $total") }
        compose.onNodeWithText("Eliminadas: ${total - 5}", substring = true).assertIsDisplayed()
        assertEquals("the five that failed are still in the bucket, the rest are gone", stubborn.toSet(), s3.objects.keys.filter { keys.isPhotoKey(it) }.toSet())

        // S3 recovers; retry sends only the failed five.
        s3.failingKeys.clear()
        s3.deleteBatches.clear()
        compose.onNodeWithText("Reintentar").performClick()
        waitFor(60_000) { textShown("Eliminadas de S3: 5", substring = true) }
        assertTrue(s3.objects.keys.none { keys.isPhotoKey(it) })
        assertEquals(stubborn.toSet(), s3.deleteBatches.first { it.any { k -> keys.isPhotoKey(k) } }.toSet())
    }
}
