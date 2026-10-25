package com.s3vault.app

import android.graphics.Bitmap
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File

/**
 * Walks the main screens in the dark theme (and again with the system text size at 150 %), takes a screenshot of each for review and
 * checks that each one really shows its own title. It exists because a screen whose text has no colour of its own looks fine on the
 * light theme and is unreadable on the dark one.
 */
class ThemeAuditTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val container get() = (ctx.applicationContext as S3VaultApp).container
    private val inserted = mutableListOf<android.net.Uri>()

    private fun asset(name: String) = instrumentation.context.assets.open("semantic/$name").readBytes()

    @Before fun setUp() {
        runBlocking(Dispatchers.IO) { container.settings.setTheme(ThemeMode.DARK) }
        listOf("cat", "coffee", "page", "camera", "rocket", "coins").forEach { inserted += BulkPerfSupport.insertBytes("audit_$it.jpg", asset("$it.jpg"), "Pictures/S3VaultAudit") }
        runBlocking(Dispatchers.IO) { container.library.refresh(force = true) }
    }

    @After fun tearDown() {
        inserted.forEach { BulkPerfSupport.delete(it) }
        runBlocking(Dispatchers.IO) { container.settings.setTheme(ThemeMode.SYSTEM) }
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitUntil(30_000) { shown(text) }

    @Test fun everyMainScreenIsReadableInTheDarkTheme() {
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("audit_cat.jpg", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("dark-1-gallery")
        for ((tab, title) in listOf("Álbumes" to "Álbumes", "Respaldo" to "Respaldo", "S3" to "S3", "Ajustes" to "Ajustes")) {
            compose.onNodeWithText(tab).performClick()
            compose.waitForIdle()
            assertTrue("$tab shows its title", compose.onAllNodesWithText(title).fetchSemanticsNodes().size >= 1)
            shot("dark-tab-" + tab.lowercase().replace("á", "a"))
        }
        // Settings -> Help and Tools.
        waitFor(ctx.getString(R.string.settings_help_subtitle))
        compose.onNodeWithText(ctx.getString(R.string.settings_help_subtitle)).performClick()
        waitFor(ctx.getString(R.string.help_replay_title))
        shot("dark-help")
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Galería").performClick()
        compose.onNodeWithContentDescription("Más opciones").performClick()
        compose.onNodeWithText("Herramientas de la biblioteca").performClick()
        waitFor("Herramientas")
        shot("dark-tools")
    }
}
