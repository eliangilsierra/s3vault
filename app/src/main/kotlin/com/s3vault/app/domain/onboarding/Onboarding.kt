package com.s3vault.app.domain.onboarding

/** Where the first-run tour stands. Persisted, so the tour is shown once and resumes if the app is closed in the middle. */
enum class OnboardingProgress { NOT_STARTED, IN_PROGRESS, DONE }

/** The pages of the tour, in order. Only the last one finishes it; every page can be skipped. */
enum class OnboardingPage { WELCOME, FEATURES, STORAGE, PHOTOS, S3, FIRST_BACKUP }

object OnboardingPolicy {
    /**
     * Whether to show the tour at launch.
     *  - Done: never again (it can be reopened from Help).
     *  - In progress: the previous run was closed before the end, so it starts again.
     *  - Not started: only on what looks like a new installation. An existing installation (an S3 configuration, saved credentials,
     *    or a photo library already indexed by an earlier version) is not a newcomer and is not interrupted.
     */
    fun shouldShow(progress: OnboardingProgress, hasS3Config: Boolean, hasCredentials: Boolean, hasIndexedPhotos: Boolean): Boolean =
        when (progress) {
            OnboardingProgress.DONE -> false
            OnboardingProgress.IN_PROGRESS -> true
            OnboardingProgress.NOT_STARTED -> !hasS3Config && !hasCredentials && !hasIndexedPhotos
        }
}

enum class CredentialState { MISSING, VALID, EXPIRING, EXPIRED }

enum class StepState {
    DONE,
    TODO,

    /** Done but something needs the user (credentials about to expire or expired, or the last connection test failed). */
    ATTENTION,

    /** Cannot be done yet because an earlier step is missing. */
    BLOCKED,
}

enum class SetupStepId { CONFIG, CREDENTIALS, TEST }

data class SetupStep(val id: SetupStepId, val state: StepState)

/**
 * The state of the optional "connect to Amazon S3" part of the tour, read from what is really saved: nothing here is a flag the
 * tour keeps for itself, so a step set up from Settings counts as done too.
 */
object SetupChecklist {
    fun of(hasConfig: Boolean, credentials: CredentialState, lastConnectionOk: Boolean?): List<SetupStep> {
        val config = SetupStep(SetupStepId.CONFIG, if (hasConfig) StepState.DONE else StepState.TODO)
        val creds = SetupStep(
            SetupStepId.CREDENTIALS,
            when (credentials) {
                CredentialState.MISSING -> StepState.TODO
                CredentialState.VALID -> StepState.DONE
                CredentialState.EXPIRING, CredentialState.EXPIRED -> StepState.ATTENTION
            },
        )
        val usable = hasConfig && (credentials == CredentialState.VALID || credentials == CredentialState.EXPIRING)
        val test = SetupStep(
            SetupStepId.TEST,
            when {
                !usable -> StepState.BLOCKED
                lastConnectionOk == true -> StepState.DONE
                lastConnectionOk == false -> StepState.ATTENTION
                else -> StepState.TODO
            },
        )
        return listOf(config, creds, test)
    }

    /** True when the connection was verified with what is saved now. */
    fun isConnected(steps: List<SetupStep>): Boolean = steps.all { it.state == StepState.DONE }
}
