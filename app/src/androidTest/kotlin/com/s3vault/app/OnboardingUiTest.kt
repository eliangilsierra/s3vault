package com.s3vault.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.onboarding.OnboardingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The first-run tour and the help screen in the real app: shown on a new installation, skippable at every step, resumed if the app
 * is closed in the middle, never repeated once done, reopenable from Help, and honest about the S3 connection (checked against a local
 * S3 stand-in, never a real bucket).
 */
class OnboardingUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    @get:Rule val permissions = perms
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val container get() = (ctx.applicationContext as S3VaultApp).container
    private var scenario: ActivityScenario<MainActivity>? = null
    private var server: MockWebServer? = null

    private fun s(id: Int) = ctx.getString(id)

    @Before fun setUp() {
        runBlocking(Dispatchers.IO) {
            container.settings.setOnboardingProgress(OnboardingProgress.NOT_STARTED)
            container.db.clearAllTables()
            container.credentials.clear()
            container.settings.clearS3Config()
        }
    }

    @After fun tearDown() {
        scenario?.close()
        server?.close()
        container.endpointOverride = null
        runBlocking(Dispatchers.IO) {
            container.credentials.clear()
            container.settings.clearS3Config()
            container.db.clearAllTables()
            container.settings.setOnboardingProgress(OnboardingProgress.DONE)
        }
    }

    private fun launch() { scenario?.close(); scenario = ActivityScenario.launch(MainActivity::class.java) }

    private fun progress() = runBlocking { container.settings.onboardingProgress.first() }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shown(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, substring: Boolean = false, timeout: Long = 30_000) {
        try {
            compose.waitUntil(timeout) {
                if (!shown(text, substring)) runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring)) }
                shown(text, substring)
            }
        } catch (e: Throwable) { shot("timeout-" + text.take(20).replace(Regex("[^A-Za-z0-9]"), "_")); throw e }
    }

    /** Buttons that live in the scrolling content are brought into view first; the fixed ones (top and bottom bars) are simply clicked. */
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun galleryIsShown() = compose.waitUntil(30_000) { shown("Galería") && !shown(s(R.string.onb_welcome_title)) }

    private fun goToPage(index: Int) {
        // pages: welcome, features, storage, photos, s3, first backup
        repeat(index) { click(s(if (it == 0) R.string.onb_start else R.string.onb_next)); compose.waitForIdle() }
    }

    // ------------------------------------------------------------------ first run

    @Test fun aNewInstallationSeesTheTourAndSkippingGoesToTheGalleryWithoutBlockingIt() {
        launch()
        waitFor(s(R.string.onb_welcome_title))
        compose.onNodeWithText(s(R.string.onb_welcome_title)).assertIsDisplayed()
        waitFor(s(R.string.onb_welcome_note_title))
        shot("onboarding-1-welcome")
        assertEquals("the tour is recorded as started, not done", OnboardingProgress.IN_PROGRESS, progress())
        click(s(R.string.onb_skip))
        galleryIsShown()
        assertEquals(OnboardingProgress.DONE, progress())
    }

    @Test fun walkingThroughEveryPageEndsInTheGalleryAndNeverRepeats() {
        launch()
        waitFor(s(R.string.onb_welcome_title))
        click(s(R.string.onb_start))
        waitFor(s(R.string.onb_features_title)); waitFor(s(R.string.onb_f_backup_title)); shot("onboarding-2-features")
        click(s(R.string.onb_next))
        waitFor(s(R.string.onb_storage_title)); waitFor(s(R.string.onb_storage_delete_title)); waitFor(s(R.string.onb_storage_cost_title)); shot("onboarding-3-storage")
        click(s(R.string.onb_next))
        waitFor(s(R.string.onb_photos_title)); shot("onboarding-4-photos")
        click(s(R.string.onb_next))
        waitFor(s(R.string.onb_s3_title)); waitFor(s(R.string.onb_s3_optional)); shot("onboarding-5-s3")
        click(s(R.string.onb_next))
        waitFor(s(R.string.onb_backup_title)); waitFor(s(R.string.onb_backup_not_connected_title)); shot("onboarding-6-first-backup")
        // Going back keeps the page content and nothing is lost.
        click(s(R.string.onb_back))
        waitFor(s(R.string.onb_s3_title))
        click(s(R.string.onb_next))
        assertEquals("still not done before the last button", OnboardingProgress.IN_PROGRESS, progress())
        click(s(R.string.onb_finish))
        galleryIsShown()
        assertEquals(OnboardingProgress.DONE, progress())

        launch()
        galleryIsShown()
        assertTrue("the tour does not come back at the next launch", !shown(s(R.string.onb_welcome_title)))
    }

    @Test fun closingTheAppInTheMiddleShowsTheTourAgainFromTheStart() {
        launch()
        waitFor(s(R.string.onb_welcome_title))
        goToPage(2)
        waitFor(s(R.string.onb_storage_title))
        scenario?.close()
        assertEquals(OnboardingProgress.IN_PROGRESS, progress())
        launch()
        waitFor(s(R.string.onb_welcome_title))
    }

    @Test fun anInstallationThatIsAlreadyConfiguredIsNotInterrupted() {
        runBlocking { container.settings.saveS3Config(S3Config("eu-west-1", "mi-bucket", "fotos/", AuthMethod.STATIC)) }
        launch()
        galleryIsShown()
        assertTrue(!shown(s(R.string.onb_welcome_title)))
        assertEquals("an existing installation is recorded as having nothing left to learn", OnboardingProgress.DONE, progress())
    }

    // ------------------------------------------------------------------ help and replay

    @Test fun helpIsReachableFromSettingsAndTheTourCanBeReplayedWithoutChangingAnything() {
        runBlocking { container.settings.setOnboardingProgress(OnboardingProgress.DONE) }
        launch()
        galleryIsShown()
        click("Ajustes")
        waitFor(s(R.string.settings_help_subtitle))
        click(s(R.string.settings_help_subtitle))
        waitFor(s(R.string.help_replay_title))
        shot("help-1-home")
        click(s(R.string.help_replay_title))
        waitFor(s(R.string.onb_welcome_title))
        assertEquals("a replay never touches the saved state", OnboardingProgress.DONE, progress())
        click(s(R.string.onb_close_replay)) // top-right close
        waitFor(s(R.string.help_replay_title))
        assertEquals(OnboardingProgress.DONE, progress())
    }

    @Test fun helpTopicsOpenAndCloseAndShowTheVersion() {
        runBlocking { container.settings.setOnboardingProgress(OnboardingProgress.DONE) }
        launch()
        galleryIsShown()
        click("Ajustes")
        waitFor(s(R.string.settings_help_subtitle))
        click(s(R.string.settings_help_subtitle))
        waitFor(s(R.string.help_setup_title))
        assertTrue("topics start closed", !shown("Crea una identidad IAM", substring = true))
        click(s(R.string.help_setup_title))
        waitFor("Crea una identidad IAM", substring = true)
        waitFor(s(R.string.help_link_aws_bucket))
        shot("help-2-topic-open")
        click(s(R.string.help_setup_title))
        compose.waitUntil(5_000) { !shown("Crea una identidad IAM", substring = true) }
        click(s(R.string.help_trouble_title))
        waitFor("Credenciales caducadas", substring = true)
        waitFor(ctx.getString(R.string.help_about_version, BuildConfig.VERSION_NAME))
        waitFor(s(R.string.help_about_issues))
    }

    // ------------------------------------------------------------------ the S3 step reads what is really saved

    private fun s3Server(bucket: String = "test-bucket"): FakeS3Dispatcher {
        val d = FakeS3Dispatcher(bucket, allowedPrefix = "ob/")
        server = MockWebServer().also { it.dispatcher = d; it.start() }
        container.endpointOverride = server!!.url("/").toString().trimEnd('/')
        return d
    }

    private fun configureS3(bucket: String = "test-bucket") {
        runBlocking(Dispatchers.IO) {
            container.credentials.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
            container.settings.saveS3Config(S3Config("eu-west-1", bucket, "ob/", AuthMethod.STATIC))
            container.settings.setOnboardingProgress(OnboardingProgress.IN_PROGRESS)
        }
    }

    private fun openS3Page() { launch(); waitFor(s(R.string.onb_welcome_title)); goToPage(4); waitFor(s(R.string.onb_s3_title)) }

    @Test fun theS3PageStartsEmptyAndExplainsWhatIsMissing() {
        runBlocking { container.settings.setOnboardingProgress(OnboardingProgress.IN_PROGRESS) }
        launch(); waitFor(s(R.string.onb_welcome_title)); goToPage(4)
        waitFor(s(R.string.onb_s3_step_config)); waitFor(s(R.string.onb_s3_state_blocked))
        waitFor(s(R.string.onb_s3_link_bucket)); waitFor(s(R.string.onb_s3_link_iam))
        assertTrue("nothing is claimed as done", !shown(s(R.string.onb_s3_state_done)))
        click(s(R.string.onb_s3_btn_config))
        waitFor("Región", substring = true) // the real S3 settings screen
    }

    @Test fun aWorkingConnectionIsVerifiedWithOneReadOnlyRequestAndAPermissionDenialIsExplained() {
        val s3 = s3Server(); configureS3()
        openS3Page()
        waitFor(s(R.string.onb_s3_state_todo)) // the connection test has not run
        click(s(R.string.onb_s3_btn_test))
        compose.waitUntil(30_000) { compose.onAllNodesWithText(s(R.string.onb_s3_state_done)).fetchSemanticsNodes().size == 3 }
        assertTrue("the check only lists: ${s3.methods}", s3.methods.all { it == "GET" })
        shot("onboarding-5-s3-connected")
        s3.denyAll = true
        click(s(R.string.onb_s3_btn_test))
        waitFor("Permisos insuficientes", substring = true)
        waitFor(s(R.string.onb_s3_state_attention), substring = true)
        shot("onboarding-5-s3-denied")
    }

    @Test fun aBucketThatDoesNotExistIsSaidInPlainWords() {
        s3Server(bucket = "otro-bucket"); configureS3("test-bucket")
        openS3Page()
        click(s(R.string.onb_s3_btn_test))
        waitFor("El bucket no existe", substring = true)
    }

    @Test fun noConnectionIsReportedAsATemporaryNetworkProblemAndTheAppStaysUsable() {
        s3Server(); configureS3()
        server!!.close() // nothing is listening any more
        openS3Page()
        click(s(R.string.onb_s3_btn_test))
        waitFor("Error temporal de red", substring = true, timeout = 90_000)
        // The rest of the tour still works and so does the gallery.
        click(s(R.string.onb_skip))
        galleryIsShown()
    }
}
