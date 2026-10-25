package com.s3vault.app.domain.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    private fun show(p: OnboardingProgress, config: Boolean = false, creds: Boolean = false, photos: Boolean = false) =
        OnboardingPolicy.shouldShow(p, config, creds, photos)

    @Test fun `a new installation sees the tour`() {
        assertTrue(show(OnboardingProgress.NOT_STARTED))
    }

    @Test fun `an installation that is already configured or in use is not interrupted`() {
        assertFalse(show(OnboardingProgress.NOT_STARTED, config = true))
        assertFalse(show(OnboardingProgress.NOT_STARTED, creds = true))
        assertFalse(show(OnboardingProgress.NOT_STARTED, photos = true))
    }

    @Test fun `a finished tour never comes back on its own`() {
        assertFalse(show(OnboardingProgress.DONE))
        assertFalse(show(OnboardingProgress.DONE, config = true, creds = true, photos = true))
    }

    @Test fun `a tour closed in the middle starts again even after photo access was granted`() {
        // The photo permission fills the index while the tour is still open: that must not read as "existing user".
        assertTrue(show(OnboardingProgress.IN_PROGRESS, photos = true))
        assertTrue(show(OnboardingProgress.IN_PROGRESS))
    }

    private fun states(config: Boolean, c: CredentialState, ok: Boolean?) = SetupChecklist.of(config, c, ok).associate { it.id to it.state }

    @Test fun `nothing is set up on a new installation and the test waits for the rest`() {
        val s = states(false, CredentialState.MISSING, null)
        assertEquals(StepState.TODO, s[SetupStepId.CONFIG])
        assertEquals(StepState.TODO, s[SetupStepId.CREDENTIALS])
        assertEquals(StepState.BLOCKED, s[SetupStepId.TEST])
    }

    @Test fun `the connection test opens once there is a configuration and usable credentials`() {
        assertEquals(StepState.TODO, states(true, CredentialState.VALID, null)[SetupStepId.TEST])
        assertEquals(StepState.BLOCKED, states(true, CredentialState.MISSING, null)[SetupStepId.TEST])
        assertEquals(StepState.BLOCKED, states(false, CredentialState.VALID, null)[SetupStepId.TEST])
        assertEquals("expired credentials cannot be tested", StepState.BLOCKED, states(true, CredentialState.EXPIRED, true)[SetupStepId.TEST])
    }

    @Test fun `credentials about to expire or expired need attention, expiring ones can still be tested`() {
        assertEquals(StepState.ATTENTION, states(true, CredentialState.EXPIRING, null)[SetupStepId.CREDENTIALS])
        assertEquals(StepState.ATTENTION, states(true, CredentialState.EXPIRED, null)[SetupStepId.CREDENTIALS])
        assertEquals(StepState.TODO, states(true, CredentialState.EXPIRING, null)[SetupStepId.TEST])
    }

    @Test fun `a failed connection test is attention, a passed one is done`() {
        assertEquals(StepState.ATTENTION, states(true, CredentialState.VALID, false)[SetupStepId.TEST])
        assertEquals(StepState.DONE, states(true, CredentialState.VALID, true)[SetupStepId.TEST])
    }

    @Test fun `connected means every step is done and nothing less`() {
        assertTrue(SetupChecklist.isConnected(SetupChecklist.of(true, CredentialState.VALID, true)))
        assertFalse(SetupChecklist.isConnected(SetupChecklist.of(true, CredentialState.VALID, null)))
        assertFalse(SetupChecklist.isConnected(SetupChecklist.of(true, CredentialState.EXPIRING, true)))
        assertFalse(SetupChecklist.isConnected(SetupChecklist.of(false, CredentialState.VALID, true)))
    }

    @Test fun `the tour has six pages in a fixed order that ends with the first backup`() {
        assertEquals(
            listOf(OnboardingPage.WELCOME, OnboardingPage.FEATURES, OnboardingPage.STORAGE, OnboardingPage.PHOTOS, OnboardingPage.S3, OnboardingPage.FIRST_BACKUP),
            OnboardingPage.entries,
        )
    }
}
