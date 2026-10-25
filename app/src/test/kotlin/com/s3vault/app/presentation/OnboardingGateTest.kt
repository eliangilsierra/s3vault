package com.s3vault.app.presentation

import androidx.test.core.app.ApplicationProvider
import com.s3vault.app.AppContainer
import com.s3vault.app.data.local.MediaIndexer
import com.s3vault.app.data.local.ScannedMedia
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.onboarding.OnboardingProgress
import com.s3vault.app.presentation.onboarding.shouldShowOnboarding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The launch decision with the real container (real Room database and real settings store): new, interrupted, finished and existing installations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingGateTest {
    private lateinit var c: AppContainer

    @Before fun setUp() { c = AppContainer(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() { c.db.close() }

    private fun progress() = runBlocking { c.settings.onboardingProgress.first() }
    private fun gate() = runBlocking { shouldShowOnboarding(c) }

    @Test fun `a new installation shows the tour once and records it as started`() {
        assertEquals(OnboardingProgress.NOT_STARTED, progress())
        assertTrue(gate())
        assertEquals(OnboardingProgress.IN_PROGRESS, progress())
    }

    @Test fun `closing the app mid tour shows it again, even once photos were indexed after the permission`() {
        assertTrue(gate())
        runBlocking { MediaIndexer(c.db).reconcile(listOf(ScannedMedia(1, "content://media/1", MediaKind.IMAGE, "a.jpg", "image/jpeg", 10, 1, 1, 1, 1, "Camera")), true) }
        assertTrue("indexing during the tour must not read as an existing user", gate())
        assertEquals(OnboardingProgress.IN_PROGRESS, progress())
    }

    @Test fun `a finished tour does not come back`() {
        assertTrue(gate())
        runBlocking { c.settings.setOnboardingProgress(OnboardingProgress.DONE) }
        assertFalse(gate())
        assertFalse(gate())
        assertEquals(OnboardingProgress.DONE, progress())
    }

    @Test fun `an installation with an S3 configuration is not interrupted and is recorded as done`() {
        runBlocking { c.settings.saveS3Config(S3Config("eu-west-1", "mi-bucket", "fotos/", AuthMethod.STATIC)) }
        assertFalse(gate())
        assertEquals(OnboardingProgress.DONE, progress())
    }

    @Test fun `an installation whose library was already indexed by an earlier version is not interrupted`() {
        runBlocking { MediaIndexer(c.db).reconcile(listOf(ScannedMedia(1, "content://media/1", MediaKind.IMAGE, "a.jpg", "image/jpeg", 10, 1, 1, 1, 1, "Camera")), true) }
        assertFalse(gate())
        assertEquals(OnboardingProgress.DONE, progress())
    }

    @Test fun `the saved state survives a new settings object like a restart of the app`() {
        runBlocking { c.settings.setOnboardingProgress(OnboardingProgress.IN_PROGRESS) }
        val again = com.s3vault.app.settings.AppSettings(ApplicationProvider.getApplicationContext())
        assertEquals(OnboardingProgress.IN_PROGRESS, runBlocking { again.onboardingProgress.first() })
    }
}
