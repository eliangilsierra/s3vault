package com.s3vault.app.presentation.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.AppContainer
import com.s3vault.app.R
import com.s3vault.app.data.local.PersonRow
import com.s3vault.app.domain.faces.PeoplePhase
import com.s3vault.app.domain.faces.PersonState
import com.s3vault.app.presentation.formatCount
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone

/**
 * The grid of people. It starts with what the analysis is doing, because that decides what there is to show; the groups themselves
 * are always presented as estimates the user can correct.
 */
@Composable
fun PeopleScreen(
    vm: PeopleViewModel,
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenReview: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var intro by remember { mutableStateOf(false) }
    var hiddenSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    MessageEffect(message, snackbar, vm::dismissMessage)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                stringResource(R.string.people_title),
                subtitle = when (s.phase) {
                    PeoplePhase.READY, PeoplePhase.INCOMPLETE, PeoplePhase.OUTDATED -> pluralStringResource(R.plurals.people_count, s.people.size, s.people.size)
                    else -> null
                },
                navigation = { BackButton(onBack) },
                actions = {
                    if (s.toReview > 0) {
                        IconButton(onClick = onOpenReview) {
                            BadgedBox(badge = { Badge { Text(formatCount(s.toReview)) } }) {
                                Icon(Icons.AutoMirrored.Filled.FactCheck, pluralStringResource(R.plurals.people_review_action_count, s.toReview, s.toReview))
                            }
                        }
                    } else if (s.people.isNotEmpty()) {
                        IconButton(onClick = onOpenReview) { Icon(Icons.AutoMirrored.Filled.FactCheck, stringResource(R.string.people_review_action)) }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.people_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.people_menu_settings)) }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                onClick = { menu = false; onOpenSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.people_menu_privacy)) }, leadingIcon = { Icon(Icons.Filled.Shield, null) },
                                onClick = { menu = false; intro = true },
                            )
                            if (s.hidden.isNotEmpty()) DropdownMenuItem(
                                text = { Text(pluralStringResource(R.plurals.people_menu_hidden, s.hidden.size, s.hidden.size)) },
                                leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) }, onClick = { menu = false; hiddenSheet = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (s.phase) {
                PeoplePhase.NO_PHOTOS -> EmptyState(Icons.Filled.PhotoLibrary, stringResource(R.string.people_empty_library_title), body = stringResource(R.string.people_empty_library_body))
                PeoplePhase.NOT_STARTED -> if (!s.loaded) Unit else EmptyState(
                    Icons.Filled.Face, stringResource(R.string.people_start_title), body = stringResource(R.string.people_start_body),
                ) {
                    PrimaryButton(stringResource(R.string.people_start_action), { intro = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.Face)
                    QuietButton(stringResource(R.string.people_start_how), { intro = true })
                }
                PeoplePhase.MODEL_UNAVAILABLE -> EmptyState(
                    Icons.Filled.Warning, stringResource(R.string.people_model_title), body = stringResource(R.string.people_model_body), tone = Tone.Error,
                )
                PeoplePhase.INDEX_UNREADABLE -> EmptyState(
                    Icons.Filled.Lock, stringResource(R.string.people_unreadable_title), body = stringResource(R.string.people_unreadable_body), tone = Tone.Warning,
                ) { PrimaryButton(stringResource(R.string.people_unreadable_action), { vm.deleteIndex(reanalyze = true) }, Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh) }
                PeoplePhase.ANALYZING -> AnalysisProgress(s, vm, Modifier.fillMaxSize())
                PeoplePhase.NO_RELIABLE_GROUPS -> EmptyState(
                    Icons.Filled.Face,
                    stringResource(if (s.faces > 0) R.string.people_none_title else R.string.people_nofaces_title),
                    body = if (s.faces > 0) pluralStringResource(R.plurals.people_none_body, s.faces, s.faces) else stringResource(R.string.people_nofaces_body),
                ) {
                    if (s.faces > 0) SecondaryButton(stringResource(R.string.people_none_review), onOpenReview, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.FactCheck)
                    QuietButton(stringResource(R.string.people_menu_settings), onOpenSettings)
                }
                PeoplePhase.READY, PeoplePhase.INCOMPLETE, PeoplePhase.OUTDATED -> Grid(s, vm, container, onOpenPerson, onOpenReview)
            }
        }
    }
    if (intro) FaceIntroDialog(
        enabled = s.enabled,
        onAccept = { intro = false; vm.enable() },
        onDismiss = { intro = false },
    )
    if (hiddenSheet) HiddenPeopleDialog(s.hidden, container, onShow = vm::unhide, onDismiss = { hiddenSheet = false })
}

@Composable
private fun Grid(s: PeopleState, vm: PeopleViewModel, container: AppContainer, onOpen: (Long) -> Unit, onReview: () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.l),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "notes", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                // The one sentence that must never be missing: these are estimates.
                Text(
                    stringResource(R.string.people_estimates_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Space.xs),
                )
                when (s.attention) {
                    PeopleState.Attention.QUOTA -> StatusBanner(Tone.Warning, stringResource(R.string.people_quota_title), stringResource(R.string.people_quota_body))
                    PeopleState.Attention.LOW_STORAGE -> StatusBanner(Tone.Warning, stringResource(R.string.people_storage_title), stringResource(R.string.people_storage_body))
                    PeopleState.Attention.NONE -> Unit
                }
                if (s.pending > 0 && s.phase != PeoplePhase.OUTDATED && s.attention == PeopleState.Attention.NONE) InlineProgress(s, vm)
                if (s.phase == PeoplePhase.OUTDATED) StatusBanner(Tone.Info, stringResource(R.string.people_outdated_title), stringResource(R.string.people_outdated_body))
                if (s.phase == PeoplePhase.INCOMPLETE) StatusBanner(
                    Tone.Warning, pluralStringResource(R.plurals.people_incomplete_title, s.unreadable, s.unreadable), stringResource(R.string.people_incomplete_body),
                )
                if (s.toReview > 0) StatusBanner(
                    Tone.Info, pluralStringResource(R.plurals.people_review_banner, s.toReview, s.toReview), stringResource(R.string.people_review_banner_body),
                    actionLabel = stringResource(R.string.people_review_action), onAction = onReview,
                )
                if (s.globalAutoBackup && s.activeRules > 0) StatusBanner(Tone.Info, stringResource(R.string.people_global_backup_title), stringResource(R.string.people_global_backup_body))
            }
        }
        items(s.people, key = { it.id }) { p -> PersonCell(p, container) { onOpen(p.id) } }
    }
}

@Composable
private fun PersonCell(p: PersonRow, container: AppContainer, onClick: () -> Unit) {
    val label = p.label()
    val photos = photoCountText(p.photos)
    val status = stringResource(if (p.state == PersonState.CONFIRMED) R.string.people_state_confirmed else R.string.people_state_unreviewed)
    val describe = buildString { append(label).append(", ").append(photos).append(", ").append(status) }
    val covers by androidx.compose.runtime.produceState<com.s3vault.app.data.local.FacePlace?>(null, p.coverFaceId) {
        value = p.coverFaceId?.let { id -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.db.faces().places(listOf(id)).firstOrNull() } }
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button, onClick = onClick, onClickLabel = stringResource(R.string.people_open))
            .semantics(mergeDescendants = true) { contentDescription = describe }.padding(vertical = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FaceCrop(covers, container, 92.dp)
        Spacer(Modifier.size(Space.xs))
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(photos, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        // Words as well as colour: whether anyone has looked at this group, and whether its photos go to the backup by themselves.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (p.state == PersonState.CONFIRMED) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Space.xs))
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        RuleIndicator(p.ruleState)
    }
}

/** The state of a running or waiting analysis, with real numbers: photos analysed out of the photos there are. */
@Composable
private fun AnalysisProgress(s: PeopleState, vm: PeopleViewModel, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = Space.xl, vertical = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.m), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Face, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(if (s.running) R.string.people_analyzing_title else R.string.people_waiting_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        InlineProgress(s, vm, showActions = false)
        when (s.attention) {
            PeopleState.Attention.QUOTA -> StatusBanner(Tone.Warning, stringResource(R.string.people_quota_title), stringResource(R.string.people_quota_body))
            PeopleState.Attention.LOW_STORAGE -> StatusBanner(Tone.Warning, stringResource(R.string.people_storage_title), stringResource(R.string.people_storage_body))
            PeopleState.Attention.NONE -> Unit
        }
        Text(
            stringResource(if (s.running) R.string.people_analyzing_body else if (s.onlyCharging) R.string.people_waiting_body_charging else R.string.people_waiting_body),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        if (s.running) SecondaryButton(stringResource(R.string.people_pause), vm::pause, Modifier.fillMaxWidth(), icon = Icons.Filled.Pause)
        else PrimaryButton(stringResource(R.string.people_analyze_now), vm::analyzeNow, Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow)
    }
}

@Composable
private fun InlineProgress(s: PeopleState, vm: PeopleViewModel, showActions: Boolean = true) {
    val total = (s.analyzed + s.pending).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        SlimProgress((s.analyzed.toFloat() / total).coerceIn(0f, 1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.people_progress, formatCount(s.analyzed), formatCount(total)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (showActions) {
                if (s.running) QuietButton(stringResource(R.string.people_pause), vm::pause) else QuietButton(stringResource(R.string.people_analyze_now), vm::analyzeNow)
            }
        }
    }
}

@Composable
private fun HiddenPeopleDialog(hidden: List<PersonRow>, container: AppContainer, onShow: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.people_hidden_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(stringResource(R.string.people_hidden_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                hidden.forEach { p ->
                    Row(Modifier.fillMaxWidth().heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Block, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(Space.m))
                        Text(p.label(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        QuietButton(stringResource(R.string.people_show_again), { onShow(p.id) })
                    }
                }
            }
        },
        confirmButton = { QuietButton(stringResource(R.string.people_close), onDismiss) },
    )
}
