package com.s3vault.app.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.auth.AuthStatus
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.onboarding.CredentialState
import com.s3vault.app.domain.onboarding.OnboardingPolicy
import com.s3vault.app.domain.onboarding.OnboardingProgress
import com.s3vault.app.domain.onboarding.SetupChecklist
import com.s3vault.app.domain.onboarding.SetupStep
import com.s3vault.app.security.SafeLog
import com.s3vault.app.sync.MediaAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decides, once per launch, whether the first-run tour opens. A new installation is marked IN_PROGRESS (so closing the app
 * mid-tour shows it again); an existing one is marked DONE silently, so an upgrade never interrupts someone already using the app.
 */
suspend fun shouldShowOnboarding(c: AppContainer): Boolean = withContext(Dispatchers.IO) {
    val progress = c.settings.onboardingProgress.first()
    val show = OnboardingPolicy.shouldShow(
        progress = progress,
        hasS3Config = c.settings.currentS3Config() != null,
        hasCredentials = c.credentials.status.value !is AuthStatus.Missing,
        hasIndexedPhotos = c.db.media().hasAnyRow(),
    )
    if (progress == OnboardingProgress.NOT_STARTED) c.settings.setOnboardingProgress(if (show) OnboardingProgress.IN_PROGRESS else OnboardingProgress.DONE)
    show
}

data class ConnectionCheck(val running: Boolean = false, val ok: Boolean? = null, val message: String? = null)

data class OnboardingUiState(
    val access: MediaAccess,
    val config: S3Config?,
    val steps: List<SetupStep>,
    val check: ConnectionCheck = ConnectionCheck(),
) {
    val connected: Boolean get() = SetupChecklist.isConnected(steps)
}

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    private val access = MutableStateFlow(c.mediaAccess())
    private val check = MutableStateFlow(ConnectionCheck())

    val state: StateFlow<OnboardingUiState> = combine(c.settings.s3Config, c.credentials.status, c.settings.connection, access, check) { cfg, auth, conn, acc, chk ->
        val creds = when (auth) {
            AuthStatus.Missing -> CredentialState.MISSING
            is AuthStatus.Valid -> CredentialState.VALID
            is AuthStatus.ExpiringSoon -> CredentialState.EXPIRING
            is AuthStatus.Expired -> CredentialState.EXPIRED
        }
        OnboardingUiState(acc, cfg, SetupChecklist.of(cfg != null, creds, conn.lastResultOk), chk)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState(c.mediaAccess(), null, SetupChecklist.of(false, CredentialState.MISSING, null)))

    /** Photo access can change outside the app (system settings) or through the permission dialog. */
    fun refreshAccess() { access.value = c.mediaAccess() }

    /** One cheap, read-only request (ListObjectsV2 with a single key under the prefix): validates region, bucket and credentials. */
    fun checkConnection() {
        if (check.value.running) return
        viewModelScope.launch {
            val cfg = c.settings.currentS3Config() ?: return@launch
            check.value = ConnectionCheck(running = true)
            try {
                c.connectionTester.checkConnection(cfg)
                c.settings.saveConnectionResult(true, "Conexión correcta", System.currentTimeMillis())
                check.value = ConnectionCheck(ok = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteException) {
                c.settings.saveConnectionResult(false, e.kind.name, System.currentTimeMillis())
                val region = if (e.kind == RemoteErrorKind.WRONG_REGION && e.expectedRegion != null) " Región del bucket: ${e.expectedRegion}." else ""
                check.value = ConnectionCheck(ok = false, message = e.kind.userMessage + region)
            } catch (e: Exception) {
                SafeLog.e("OnboardingViewModel", "Connection check failed unexpectedly", e)
                c.settings.saveConnectionResult(false, RemoteErrorKind.UNKNOWN.name, System.currentTimeMillis())
                check.value = ConnectionCheck(ok = false, message = RemoteErrorKind.UNKNOWN.userMessage)
            }
        }
    }

    /** Marks the tour as finished (also when it is skipped): it is not shown automatically again. Replays do not touch it. */
    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            c.settings.setOnboardingProgress(OnboardingProgress.DONE)
            onDone()
        }
    }

    fun dismissCheck() { check.update { ConnectionCheck() } }
}
