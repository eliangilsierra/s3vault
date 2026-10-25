package com.s3vault.app

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.s3vault.app.presentation.help.HelpScreen
import com.s3vault.app.presentation.onboarding.OnboardingScreen
import com.s3vault.app.presentation.onboarding.OnboardingViewModel
import com.s3vault.app.presentation.onboarding.PhotosPage
import com.s3vault.app.presentation.theme.S3VaultTheme
import com.s3vault.app.settings.ThemeMode
import com.s3vault.app.sync.MediaAccess
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * How the tour and Help look and behave under the conditions that break layouts: text scaled to 200 %, the dark theme, and each state
 * of photo access (granted, partial, denied). The permission states are rendered directly because revoking a permission from inside
 * an instrumented test would kill the app under test.
 */
class OnboardingRenderTest {
    @get:Rule val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val container get() = (ctx.applicationContext as S3VaultApp).container
    private fun s(id: Int) = ctx.getString(id)

    private fun render(mode: ThemeMode = ThemeMode.LIGHT, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) { S3VaultTheme(mode, content) }
        }
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // ------------------------------------------------------------------ photo access states

    @Test fun grantedAccessSaysSoAndDoesNotAskAgain() {
        render { PhotosPage(MediaAccess.FULL, onChanged = {}) }
        compose.onNodeWithText(s(R.string.onb_photos_granted_title)).assertIsDisplayed()
        assertTrue("granted: no button asking again", !shown(s(R.string.onb_photos_allow)))
    }

    @Test fun partialAccessOffersToChangeTheChosenPhotos() {
        render { PhotosPage(MediaAccess.PARTIAL, onChanged = {}) }
        compose.onNodeWithText(s(R.string.onb_photos_partial_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.onb_photos_manage)).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun deniedAccessExplainsWhyAndTheGalleryStaysEmptyUntilGranted() {
        render { PhotosPage(MediaAccess.NONE, onChanged = {}) }
        compose.onNodeWithText(s(R.string.onb_photos_denied_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.onb_photos_allow)).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(s(R.string.onb_photos_denied), substring = true).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ text scale and dark theme, page by page

    private fun walk(prefix: String) {
        compose.onNodeWithText(s(R.string.onb_welcome_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.onb_skip)).assertHeightIsAtLeast(48.dp)
        shot("$prefix-1")
        for (i in 2..6) {
            compose.onNodeWithText(s(if (i == 2) R.string.onb_start else R.string.onb_next)).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(ctx.getString(R.string.onb_step_of, i, 6)).assertIsDisplayed()
            shot("$prefix-$i")
        }
        // Even at the last page with huge text the primary action is on screen (the content scrolls, the buttons do not).
        compose.onNodeWithText(s(R.string.onb_finish)).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    private fun tour() {
        render(ThemeMode.DARK, 2f) {
            val vm = remember { OnboardingViewModel(container) }
            OnboardingScreen(vm, replay = false, onOpenS3Settings = {}, onOpenCredentials = {}, onFinished = {})
        }
    }

    @Test fun theTourWorksInTheDarkThemeWithTextAtTwoHundredPercent() {
        tour()
        walk("onboarding-dark-x2")
    }

    @Test fun theLongestPageStillReachesItsLastLineWithHugeText() {
        tour()
        compose.onNodeWithText(s(R.string.onb_start)).performClick()
        compose.onNodeWithText(s(R.string.onb_next)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.onb_storage_perm_body), substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.onb_storage_adv_toggle), substring = true).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.onb_storage_link_iam)).performScrollTo().assertIsDisplayed()
        shot("onboarding-dark-x2-storage-advanced")
    }

    @Test fun helpIsReadableInTheDarkThemeWithHugeText() {
        render(ThemeMode.DARK, 2f) { HelpScreen(versionName = "9.9.9", onBack = {}, onReplayTour = {}) }
        compose.onNodeWithText(s(R.string.help_replay_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.help_cost_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.help_link_aws_pricing)).performScrollTo().assertIsDisplayed()
        shot("help-dark-x2")
    }
}
