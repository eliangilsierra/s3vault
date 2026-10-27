package com.s3vault.app.presentation.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.R
import com.s3vault.app.domain.onboarding.OnboardingPage
import com.s3vault.app.domain.onboarding.SetupStep
import com.s3vault.app.domain.onboarding.SetupStepId
import com.s3vault.app.domain.onboarding.StepState
import com.s3vault.app.presentation.mediaPermissions
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone
import com.s3vault.app.presentation.ui.rememberReducedMotion
import com.s3vault.app.sync.MediaAccess

/** Official pages cited by the tour. They are opened in the browser only when the user taps them. */
object Links {
    const val AWS_CREATE_BUCKET = "https://docs.aws.amazon.com/AmazonS3/latest/userguide/creating-bucket.html"
    const val AWS_CREATE_IAM_USER = "https://docs.aws.amazon.com/IAM/latest/UserGuide/id_users_create.html"
    const val AWS_S3_PRICING = "https://aws.amazon.com/s3/pricing/"
    const val IAM_POLICY_DOC = "https://github.com/eliangilsierra/s3vault/blob/main/docs/iam-policy.md"
    const val ISSUES = "https://github.com/eliangilsierra/s3vault/issues"
}

@Composable
fun rememberLinkOpener(): (String) -> Unit {
    val handler = LocalUriHandler.current
    return remember(handler) { { url -> runCatching { handler.openUri(url) } } }
}

/**
 * The first-run tour (and, with [replay], the same tour reopened from Help). It never blocks the local gallery: every page can be
 * skipped, nothing is required, and connecting to S3 only reads what is already saved by the real Settings screens, so no secret is
 * typed, stored or passed around here.
 */
@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel,
    replay: Boolean,
    onOpenS3Settings: () -> Unit,
    onOpenCredentials: () -> Unit,
    onFinished: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pages = OnboardingPage.entries
    val last = page == pages.lastIndex
    val reduced = rememberReducedMotion()
    val open = rememberLinkOpener()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccess() }
    BackHandler(enabled = page > 0) { page-- }
    // Skipping or finishing the first-run tour records it as done; a replay changes nothing.
    fun leave() { if (replay) onFinished() else vm.complete(onFinished) }

    // A Surface (not just a background) so text without an explicit colour follows the theme: black on the dark theme was unreadable.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = Space.maxContent).fillMaxWidth().weight(1f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.onb_step_of, page + 1, pages.size),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (!last) QuietButton(stringResource(if (replay) R.string.onb_close_replay else R.string.onb_skip), ::leave)
            }
            SlimProgress((page + 1f) / pages.size, Modifier.padding(horizontal = Space.screen))
            Crossfade(page, Modifier.weight(1f), animationSpec = tween(if (reduced) 0 else 180), label = "onboardingPage") { p ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    when (pages[p]) {
                        OnboardingPage.WELCOME -> Welcome()
                        OnboardingPage.FEATURES -> Features()
                        OnboardingPage.STORAGE -> Storage(open)
                        OnboardingPage.PHOTOS -> PhotosPage(state.access, vm::refreshAccess)
                        OnboardingPage.S3 -> S3Setup(state, vm, open, onOpenS3Settings, onOpenCredentials)
                        OnboardingPage.FIRST_BACKUP -> FirstBackup(state.connected)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page > 0) SecondaryButton(stringResource(R.string.onb_back), { page-- }, Modifier.weight(1f))
                PrimaryButton(
                    stringResource(
                        when {
                            last && replay -> R.string.onb_close_replay
                            last -> R.string.onb_finish
                            page == 0 -> R.string.onb_start
                            else -> R.string.onb_next
                        },
                    ),
                    { if (last) leave() else page++ }, Modifier.weight(if (page > 0) 1f else 1f),
                )
            }
        }
    }
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun PageHeader(icon: ImageVector, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        // Decorative: the title right below says the same in words.
        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Bullet(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, body: String) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LinkButton(textRes: Int, url: String, open: (String) -> Unit) {
    QuietButton(stringResource(textRes), { open(url) })
}

// ------------------------------------------------------------------ pages

@Composable
private fun Welcome() {
    PageHeader(Icons.Filled.PhotoLibrary, stringResource(R.string.onb_welcome_title))
    Body(stringResource(R.string.onb_welcome_body))
    Bullet(Icons.Filled.Image, stringResource(R.string.onb_welcome_b1))
    Bullet(Icons.Filled.CloudQueue, stringResource(R.string.onb_welcome_b2))
    Bullet(Icons.Filled.Lock, stringResource(R.string.onb_welcome_b3))
    StatusBanner(Tone.Info, stringResource(R.string.onb_welcome_note_title), stringResource(R.string.onb_welcome_note))
}

@Composable
private fun Features() {
    PageHeader(Icons.Filled.Dashboard, stringResource(R.string.onb_features_title))
    Body(stringResource(R.string.onb_features_intro))
    InfoRow(Icons.Filled.PhotoLibrary, stringResource(R.string.onb_f_gallery_title), stringResource(R.string.onb_f_gallery_body))
    InfoRow(Icons.Filled.CloudUpload, stringResource(R.string.onb_f_backup_title), stringResource(R.string.onb_f_backup_body))
    InfoRow(Icons.Filled.CheckCircle, stringResource(R.string.onb_f_status_title), stringResource(R.string.onb_f_status_body))
    InfoRow(Icons.Filled.CloudQueue, stringResource(R.string.onb_f_cloud_title), stringResource(R.string.onb_f_cloud_body))
    if (com.s3vault.app.data.faces.FaceFeature.available) {
        InfoRow(Icons.Filled.Face, stringResource(R.string.onb_f_people_title), stringResource(R.string.onb_f_people_body))
    }
    InfoRow(Icons.Filled.Dashboard, stringResource(R.string.onb_f_tools_title), stringResource(R.string.onb_f_tools_body))
}

@Composable
private fun Storage(open: (String) -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    PageHeader(Icons.Filled.Storage, stringResource(R.string.onb_storage_title))
    InfoRow(Icons.Filled.PhotoLibrary, stringResource(R.string.onb_storage_local_title), stringResource(R.string.onb_storage_local_body))
    InfoRow(Icons.Filled.CloudQueue, stringResource(R.string.onb_storage_cloud_title), stringResource(R.string.onb_storage_cloud_body))
    InfoRow(Icons.Filled.WarningAmber, stringResource(R.string.onb_storage_delete_title), stringResource(R.string.onb_storage_delete_body))
    InfoRow(Icons.Filled.Storage, stringResource(R.string.onb_storage_cost_title), stringResource(R.string.onb_storage_cost_body))
    InfoRow(Icons.Filled.Lock, stringResource(R.string.onb_storage_perm_title), stringResource(R.string.onb_storage_perm_body))
    QuietButton(stringResource(R.string.onb_storage_adv_toggle) + if (advanced) " ▲" else " ▼", { advanced = !advanced })
    if (advanced) {
        Body(stringResource(R.string.onb_storage_adv_body))
        LinkButton(R.string.onb_storage_link_pricing, Links.AWS_S3_PRICING, open)
        LinkButton(R.string.onb_storage_link_iam, Links.IAM_POLICY_DOC, open)
    }
}

@Composable
internal fun PhotosPage(access: MediaAccess, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true; onChanged() }
    PageHeader(Icons.Filled.Image, stringResource(R.string.onb_photos_title))
    Body(stringResource(R.string.onb_photos_body))
    when (access) {
        MediaAccess.FULL -> StatusBanner(Tone.Success, stringResource(R.string.onb_photos_granted_title), stringResource(R.string.onb_photos_granted))
        MediaAccess.PARTIAL -> {
            StatusBanner(Tone.Info, stringResource(R.string.onb_photos_partial_title), stringResource(R.string.onb_photos_partial))
            SecondaryButton(stringResource(R.string.onb_photos_manage), { launcher.launch(mediaPermissions()) }, Modifier.fillMaxWidth())
        }
        MediaAccess.NONE -> {
            StatusBanner(Tone.Neutral, stringResource(R.string.onb_photos_denied_title), stringResource(R.string.onb_photos_denied))
            PrimaryButton(stringResource(R.string.onb_photos_allow), { launcher.launch(mediaPermissions()) }, Modifier.fillMaxWidth())
            // After a refusal Android may not show the dialog again: the app settings are the way back.
            if (asked) SecondaryButton(
                stringResource(R.string.onb_photos_settings),
                { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun S3Setup(state: OnboardingUiState, vm: OnboardingViewModel, open: (String) -> Unit, onConfig: () -> Unit, onCreds: () -> Unit) {
    PageHeader(Icons.Filled.CloudQueue, stringResource(R.string.onb_s3_title))
    Body(stringResource(R.string.onb_s3_optional))
    val byId = state.steps.associateBy { it.id }
    StepRow(
        byId.getValue(SetupStepId.CONFIG), stringResource(R.string.onb_s3_step_config),
        stringResource(if (byId.getValue(SetupStepId.CONFIG).state == StepState.DONE) R.string.onb_s3_btn_config_edit else R.string.onb_s3_btn_config), onConfig,
        detail = state.config?.let { "${it.bucket} · ${it.region}" },
    )
    StepRow(
        byId.getValue(SetupStepId.CREDENTIALS), stringResource(R.string.onb_s3_step_creds),
        stringResource(if (byId.getValue(SetupStepId.CREDENTIALS).state == StepState.TODO) R.string.onb_s3_btn_creds else R.string.onb_s3_btn_creds_edit), onCreds,
    )
    val test = byId.getValue(SetupStepId.TEST)
    StepRow(test, stringResource(R.string.onb_s3_step_test), stringResource(R.string.onb_s3_btn_test), vm::checkConnection, enabled = test.state != StepState.BLOCKED && !state.check.running)
    if (state.check.running) {
        Text(stringResource(R.string.onb_s3_testing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SlimProgress(null)
    }
    state.check.message?.let { StatusBanner(Tone.Error, stringResource(R.string.onb_s3_error_title), it) }
    Text(stringResource(R.string.onb_s3_test_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    InfoRow(Icons.Filled.CloudQueue, stringResource(R.string.onb_s3_help_title), stringResource(R.string.onb_s3_help_body))
    LinkButton(R.string.onb_s3_link_bucket, Links.AWS_CREATE_BUCKET, open)
    LinkButton(R.string.onb_s3_link_iam, Links.AWS_CREATE_IAM_USER, open)
}

@Composable
private fun StepRow(step: SetupStep, title: String, action: String, onAction: () -> Unit, enabled: Boolean = true, detail: String? = null) {
    val stateText = stringResource(
        when (step.state) {
            StepState.DONE -> R.string.onb_s3_state_done
            StepState.TODO -> R.string.onb_s3_state_todo
            StepState.ATTENTION -> R.string.onb_s3_state_attention
            StepState.BLOCKED -> R.string.onb_s3_state_blocked
        },
    )
    val (icon, tint) = when (step.state) {
        StepState.DONE -> Icons.Filled.CheckCircle to MaterialTheme.status.success
        StepState.ATTENTION -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
        StepState.TODO -> Icons.Filled.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
        StepState.BLOCKED -> Icons.Filled.Lock to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            // The icon never carries the state alone: the words next to it do.
            Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "$title: $stateText" }, horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(24.dp), tint = tint)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(stateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SecondaryButton(action, onAction, Modifier.fillMaxWidth().heightIn(min = Space.touch), enabled = enabled)
        }
    }
}

@Composable
private fun FirstBackup(connected: Boolean) {
    PageHeader(Icons.Filled.CloudUpload, stringResource(R.string.onb_backup_title))
    NumberedStep(1, stringResource(R.string.onb_backup_s1))
    NumberedStep(2, stringResource(R.string.onb_backup_s2))
    NumberedStep(3, stringResource(R.string.onb_backup_s3))
    NumberedStep(4, stringResource(R.string.onb_backup_s4))
    if (connected) StatusBanner(Tone.Success, stringResource(R.string.onb_backup_connected_title), stringResource(R.string.onb_backup_connected))
    else StatusBanner(Tone.Info, stringResource(R.string.onb_backup_not_connected_title), stringResource(R.string.onb_backup_not_connected))
    Text(stringResource(R.string.onb_backup_replay_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NumberedStep(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(n.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 2.dp))
    }
}
