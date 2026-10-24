package com.s3vault.app

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.domain.library.LibraryQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.FileInputStream

/**
 * "Eliminar del dispositivo" for 30, 100 and 300 selected photos, end to end on a real MediaStore: select, choose the copy,
 * confirm, answer Android's own consent dialog, wait for the library to be settled. The probe runs from the confirm tap to the
 * final message and is split in two phases: until Android's dialog is up, and from "Allow" until the app is done.
 */
class BulkDeletePerfTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private val cell = hasContentDescription("bd_0", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    @Before fun setUp() {
        runBlocking(Dispatchers.IO) { BulkPerfSupport.deletePhotos("bf_"); BulkPerfSupport.deletePhotos("bd_"); container.db.clearAllTables() }
    }

    @After fun tearDown() {
        container.libraryStore.local.value = LibraryQuery() // the search used to select the photos must not leak into the next test
        runBlocking(Dispatchers.IO) { BulkPerfSupport.deletePhotos("bf_"); BulkPerfSupport.deletePhotos("bd_"); container.db.clearAllTables() }
    }

    private fun shell(cmd: String) = FileInputStream(instrumentation.uiAutomation.executeShellCommand(cmd).fileDescriptor).use { String(it.readBytes()) }

    /** Searches for the test photos by name and selects every match with the app's own "select all" (fast and exact). */
    private fun selectAllMatching(n: Int) {
        compose.onNodeWithContentDescription("Buscar").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("bd_")
        try {
            compose.waitUntil(60_000) { compose.onAllNodesWithText("$n resultados en este dispositivo").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            compose.onRoot().printToLog("BulkPerf")
            throw e
        }
        compose.onNode(hasSetTextAction()).performImeAction() // drops the keyboard that covers the grid
        compose.waitForIdle()
        compose.onAllNodes(cell).onFirst().performTouchInput { longClick() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 seleccionada").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Seleccionar todo").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("$n seleccionadas").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waits for Android's consent dialog and presses Allow; returns when the app is in front again. */
    private fun allowSystemDeleteDialog(probe: PerfProbe) {
        compose.waitForIdle()
        fun focus() = shell("dumpsys window").lineSequence().firstOrNull { it.contains("mCurrentFocus") }.orEmpty()
        fun waitFocus(want: (String) -> Boolean) {
            val end = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < end && !want(focus())) Thread.sleep(150)
        }
        waitFocus { it.contains("media") || it.contains("PermissionActivity") }
        probe.phase = "dialog-visible"
        Thread.sleep(1_200)
        val dm = instrumentation.targetContext.resources.displayMetrics
        probe.phase = "after-allow"
        // The dialog's height depends on how many photos it lists, so find the button instead of guessing where it is.
        val allow = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Allow")?.firstOrNull { it.isClickable }
        if (allow != null) {
            val r = android.graphics.Rect().also { allow.getBoundsInScreen(it) }
            shell("input tap ${r.centerX()} ${r.centerY()}")
        } else {
            shell("input tap ${(dm.widthPixels * 0.828).toInt()} ${(dm.heightPixels * 0.608).toInt()}")
        }
        waitFocus { it.contains("com.s3vault.app/") }
    }

    private fun run(n: Int) {
        runBlocking(Dispatchers.IO) {
            BulkPerfSupport.insertPhotos("bf_", 30)   // older filler: the gallery is never empty
            Thread.sleep(2_100)
            BulkPerfSupport.insertPhotos("bd_", n)    // newest: the ones that get selected
            container.library.refresh()
        }
        compose.waitUntil(60_000) { compose.onAllNodesWithContentDescription("bd_", substring = true).fetchSemanticsNodes().isNotEmpty() }
        selectAllMatching(n)
        compose.onNodeWithContentDescription("Eliminar…").performClick()
        compose.onNodeWithText("Del dispositivo ($n)").performClick()

        val probe = PerfProbe(compose.activity)
        val t0 = System.nanoTime()
        probe.start()
        probe.phase = "confirm-to-dialog"
        compose.onNodeWithText("Eliminar del dispositivo").performClick()
        allowSystemDeleteDialog(probe)
        val tAllowed = System.nanoTime()
        compose.waitUntil(120_000) { compose.onAllNodesWithText("Eliminadas del dispositivo: $n", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val tDone = System.nanoTime()
        probe.phase = "idle-after"
        compose.waitForIdle(); Thread.sleep(500)
        probe.stop()
        Log.i("BulkPerf", "delete n=$n: confirm->done ${(tDone - t0) / 1_000_000} ms, allow->done ${(tDone - tAllowed) / 1_000_000} ms")
        probe.report("delete n=$n")
        probe.memoryReport("delete n=$n")
        probe.close()
        val left = runBlocking(Dispatchers.IO) { container.db.media().getEverything().count { it.displayName.startsWith("bd_") && it.present } }
        assertEquals("every selected photo left the index", 0, left)
    }

    @Test fun delete30() = run(30)
    @Test fun delete100() = run(100)
    @Test fun delete300() = run(300)
}
