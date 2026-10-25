package com.s3vault.app

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import com.s3vault.app.domain.onboarding.OnboardingProgress
import com.s3vault.app.settings.AppSettings
import kotlinx.coroutines.runBlocking

/**
 * The UI tests start the app on the gallery. A clean device would show the first-run tour instead, so the runner records the tour
 * as seen before the app starts; `OnboardingUiTest` resets that on purpose and puts it back.
 */
class S3VaultTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        runBlocking { AppSettings(app).setOnboardingProgress(OnboardingProgress.DONE) }
        super.callApplicationOnCreate(app)
    }
}
