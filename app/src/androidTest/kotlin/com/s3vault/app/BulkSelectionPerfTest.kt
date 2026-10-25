package com.s3vault.app

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.domain.library.LibraryQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * Selecting many photos in the real gallery, measured from inside the process (see [PerfProbe]). The photos are real
 * MediaStore files, the taps are real touch events through the Compose test rule, and the cells decode real thumbnails.
 * Results are logged under `BulkPerf`; the assertions are loose guard rails (an emulator is slow), the log is the data.
 */
class BulkSelectionPerfTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private val total = 340

    private val cell = hasContentDescription("bp_", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    @Before fun setUp() {
        container.libraryStore.local.value = LibraryQuery() // the query outlives the activity: start from a clean gallery
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            BulkPerfSupport.insertPhotos("bp_", total)
            container.library.refresh()
        }
    }

    @After fun tearDown() {
        runBlocking(Dispatchers.IO) {
            BulkPerfSupport.deletePhotos("bp_")
            container.db.clearAllTables()
        }
    }

    private fun waitForGallery() = compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("bp_", substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun scrollToTop() { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0); compose.waitForIdle() }

    /** Cells that are fully on screen right now, top to bottom. */
    private fun fullyVisibleCells(): List<Int> {
        val nodes = compose.onAllNodes(cell).fetchSemanticsNodes()
        return nodes.indices.filter { i ->
            val n = nodes[i]
            n.boundsInWindow.height >= n.size.height - 2f && n.boundsInWindow.width >= n.size.width - 2f
        }
    }

    private fun isSelected(i: Int) = compose.onAllNodes(cell).fetchSemanticsNodes()[i].config.getOrNull(SemanticsProperties.Selected) == true

    /** Selects the first [n] photos by tapping them, scrolling down when the screen is exhausted. Returns the number of taps. */
    private fun selectFirst(n: Int, probe: PerfProbe): Int {
        var taps = 0
        var stalls = 0
        var firstTap = true
        while (taps < n && stalls < 6) {
            probe.phase = "tap"
            val before = taps
            for (i in fullyVisibleCells()) {
                if (taps >= n) break
                if (isSelected(i)) continue
                val node = compose.onAllNodes(cell)[i]
                if (firstTap) node.performTouchInput { longClick() } else node.performClick()
                firstTap = false
                taps++
            }
            compose.waitForIdle()
            stalls = if (taps == before) stalls + 1 else 0
            if (taps < n) {
                probe.phase = "scroll"
                compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.45f, durationMillis = 250) }
                compose.waitForIdle()
                Thread.sleep(250) // lets the next pages of the window arrive: cells of a page that is not loaded yet have no content to tap
                compose.waitForIdle()
            }
        }
        return taps
    }

    @Test fun selectingManyPhotosStaysResponsive() {
        waitForGallery()
        val probe = PerfProbe(compose.activity)
        val summary = StringBuilder()
        try {
            for (n in listOf(1, 30, 100, 300)) {
                scrollToTop()
                probe.start()
                val t0 = System.nanoTime()
                val taps = selectFirst(n, probe)
                val ms = (System.nanoTime() - t0) / 1_000_000
                probe.phase = "settled"
                compose.waitForIdle()
                Thread.sleep(400)
                probe.stop()
                Log.i("BulkPerf", "select n=$n: $taps taps in $ms ms wall (includes the test's own idle waits)")
                summary.appendLine(probe.report("select n=$n"))
                summary.appendLine(probe.memoryReport("select n=$n"))
                assertTrue("tapped $n photos", taps == n)

                // Scrolling with a big selection must stay as smooth as without one.
                probe.start()
                probe.phase = "scroll-with-selection"
                repeat(3) {
                    compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.85f, durationMillis = 200) }
                    compose.waitForIdle()
                }
                probe.stop()
                summary.appendLine(probe.report("after select n=$n"))

                probe.start()
                probe.phase = "clear-selection"
                compose.onNodeWithContentDescription("Cancelar selección").performClick()
                compose.waitForIdle()
                Thread.sleep(300)
                probe.stop()
                summary.appendLine(probe.report("clear n=$n"))
            }
        } finally {
            probe.close()
        }
        Log.i("BulkPerf", "SUMMARY\n$summary")
    }
}
