package com.s3vault.app.presentation.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.R
import com.s3vault.app.domain.faces.PeoplePhase
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.formatCount
import com.s3vault.app.presentation.settings.SettingsScaffold
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.GroupDivider
import com.s3vault.app.presentation.ui.LabeledValue
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SectionHeader
import com.s3vault.app.presentation.ui.SettingsGroup
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.SwitchRow
import com.s3vault.app.presentation.ui.Tone

/**
 * Where the user turns the face analysis on and off, chooses how it uses the phone, reads again what it stores, and erases it.
 * Erasing is one clearly named action that says what it does and what it does not touch.
 */
@Composable
fun FaceSettingsScreen(vm: PeopleViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }
    var intro by remember { mutableStateOf(false) }

    SettingsScaffold(stringResource(R.string.people_settings_title), onBack) {
        if (s.phase == PeoplePhase.INDEX_UNREADABLE) StatusBanner(Tone.Warning, stringResource(R.string.people_unreadable_title), stringResource(R.string.people_unreadable_body))
        SettingsGroup {
            SwitchRow(
                stringResource(R.string.people_settings_enable), stringResource(if (s.enabled) R.string.people_settings_enable_on else R.string.people_settings_enable_off), s.enabled,
                onChange = { on -> if (on) { if (s.introSeen) vm.enable() else intro = true } else vm.disable() },
            )
        }

        if (s.enabled) {
            SectionHeader(stringResource(R.string.people_settings_status))
            SettingsGroup {
                Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    val total = (s.analyzed + s.pending).coerceAtLeast(1)
                    SlimProgress(if (s.pending == 0) 1f else (s.analyzed.toFloat() / total).coerceIn(0f, 1f))
                    Text(
                        if (s.pending == 0) stringResource(R.string.people_settings_done, formatCount(s.analyzed))
                        else stringResource(R.string.people_progress, formatCount(s.analyzed), formatCount(total)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (s.unreadable > 0) Text(pluralStringResource(R.plurals.people_incomplete_title, s.unreadable, s.unreadable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.pending > 0) {
                        if (s.running) SecondaryButton(stringResource(R.string.people_pause), vm::pause, Modifier.fillMaxWidth(), icon = Icons.Filled.Pause)
                        else PrimaryButton(stringResource(R.string.people_analyze_now), vm::analyzeNow, Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow)
                    }
                }
            }
            SectionHeader(stringResource(R.string.people_settings_resources))
            SettingsGroup {
                SwitchRow(stringResource(R.string.people_settings_charging), stringResource(R.string.people_settings_charging_body), s.onlyCharging, vm::setOnlyCharging)
                GroupDivider(Space.l)
                SwitchRow(stringResource(R.string.people_settings_gentle), stringResource(R.string.people_settings_gentle_body), s.gentle, vm::setGentle)
            }
            Text(stringResource(R.string.people_settings_resources_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionHeader(stringResource(R.string.people_settings_privacy))
        FacePrivacyExplainer()

        SectionHeader(stringResource(R.string.people_settings_data))
        SettingsGroup {
            Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                LabeledValue(stringResource(R.string.people_settings_faces), formatCount(s.faces))
                LabeledValue(stringResource(R.string.people_settings_groups), formatCount(s.people.size + s.hidden.size))
                LabeledValue(stringResource(R.string.people_settings_size), formatBytes(s.indexBytes))
                LabeledValue(stringResource(R.string.people_settings_rules), formatCount(s.activeRules))
            }
        }
        SecondaryButton(stringResource(R.string.people_settings_rebuild), { confirmRebuild = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh, enabled = s.faces > 0 || s.analyzed > 0)
        SecondaryButton(stringResource(R.string.people_settings_delete), { confirmDelete = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.DeleteForever, enabled = s.faces > 0 || s.analyzed > 0 || s.enabled)
        Text(stringResource(R.string.people_settings_delete_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (intro) FaceIntroDialog(enabled = false, onAccept = { intro = false; vm.enable() }, onDismiss = { intro = false })
    if (confirmDelete) ConfirmDialog(
        stringResource(R.string.people_delete_index_title), stringResource(R.string.people_delete_index_body),
        stringResource(R.string.people_delete_index_confirm), onConfirm = { confirmDelete = false; vm.deleteIndex() }, onDismiss = { confirmDelete = false }, destructive = true,
    )
    if (confirmRebuild) ConfirmDialog(
        stringResource(R.string.people_rebuild_title), stringResource(R.string.people_rebuild_body),
        stringResource(R.string.people_rebuild_confirm), onConfirm = { confirmRebuild = false; vm.deleteIndex(reanalyze = true) }, onDismiss = { confirmRebuild = false }, destructive = true,
    )
}
