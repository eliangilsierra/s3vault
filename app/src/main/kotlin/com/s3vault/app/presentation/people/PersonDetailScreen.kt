package com.s3vault.app.presentation.people

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.AppContainer
import com.s3vault.app.R
import com.s3vault.app.data.local.PersonRow
import com.s3vault.app.domain.faces.DecisionReason
import com.s3vault.app.domain.faces.PersonState
import com.s3vault.app.domain.faces.RulePolicy
import com.s3vault.app.domain.faces.RuleState
import com.s3vault.app.presentation.MediaCell
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.formatCount
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SectionHeader
import com.s3vault.app.presentation.ui.SettingsGroup
import com.s3vault.app.presentation.ui.SettingsRow
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone

private sealed interface Dialog {
    data object None : Dialog
    data object Rename : Dialog
    data object Menu : Dialog
    data object ConfirmDelete : Dialog
    data object ConfirmHide : Dialog
    data object ConfirmRemove : Dialog
    data object PickMove : Dialog
    data object ConfirmSplit : Dialog
    data object PickMerge : Dialog
    data class ConfirmMerge(val other: PersonRow) : Dialog
    data class ConfirmMove(val target: PersonRow) : Dialog
    data object RuleSheet : Dialog
    data object ConfirmUndo : Dialog
    data object ConfirmRuleOff : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    personId: Long,
    vm: PersonDetailViewModel,
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPhoto: (Long) -> Unit,
    onOpenS3Settings: () -> Unit,
    onOpenCredentials: () -> Unit,
) {
    LaunchedEffect(personId) { vm.open(personId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selection.collectAsStateWithLifecycle()
    val uploads by vm.uploads.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<Dialog>(Dialog.None) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }
    MessageEffect(message, snackbar, vm::dismissMessage, onUndo = vm::undoRule)

    val p = s.person
    // The group disappears when its last photo is taken out or it is merged away: leave instead of showing an empty shell.
    LaunchedEffect(s.missing, p) { if (s.missing && vm.personId == personId && s.photos != null && p == null) onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                var more by remember { mutableStateOf(false) }
                AppTopBar(
                    title = pluralStringResource(R.plurals.people_selected, selected.size, selected.size), compact = true,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    navigation = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, stringResource(R.string.people_cancel_selection)) } },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, stringResource(R.string.people_select_all)) }
                        IconButton(onClick = vm::backupSelected) { Icon(Icons.Filled.CloudUpload, stringResource(R.string.people_backup_selected)) }
                        IconButton(onClick = { dialog = Dialog.ConfirmRemove }) { Icon(Icons.Filled.PersonRemove, stringResource(R.string.people_remove_from_group)) }
                        Box {
                            IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.people_more)) }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.people_move_to_other)) }, leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) },
                                    onClick = { more = false; dialog = Dialog.PickMove },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.people_split_selected)) }, leadingIcon = { Icon(Icons.Filled.CallSplit, null) },
                                    onClick = { more = false; dialog = Dialog.ConfirmSplit },
                                )
                                if (s.rule != null) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.people_exclude_from_rule)) }, leadingIcon = { Icon(Icons.Filled.Close, null) },
                                    onClick = { more = false; vm.excludeSelectedFromRule() },
                                )
                            }
                        }
                    },
                )
            } else {
                AppTopBar(
                    title = p?.label() ?: stringResource(R.string.people_title),
                    subtitle = p?.let { photoCountText(it.photos) },
                    navigation = { BackButton(onBack) },
                    actions = {
                        IconButton(onClick = { dialog = Dialog.Rename }, enabled = p != null) { Icon(Icons.Filled.Edit, stringResource(R.string.people_rename)) }
                        Box {
                            IconButton(onClick = { dialog = Dialog.Menu }, enabled = p != null) { Icon(Icons.Filled.MoreVert, stringResource(R.string.people_more)) }
                            DropdownMenu(expanded = dialog == Dialog.Menu, onDismissRequest = { dialog = Dialog.None }) {
                                if (p?.state == PersonState.PROVISIONAL) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.people_confirm_group)) }, leadingIcon = { Icon(Icons.Filled.VerifiedUser, null) },
                                    onClick = { dialog = Dialog.None; vm.confirm() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.people_merge_with)) }, leadingIcon = { Icon(Icons.Filled.CallMerge, null) },
                                    enabled = s.others.isNotEmpty(), onClick = { dialog = Dialog.PickMerge },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.people_hide_group)) }, leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) },
                                    onClick = { dialog = Dialog.ConfirmHide },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.people_delete_group), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { dialog = Dialog.ConfirmDelete },
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val photos = s.photos
            when {
                p == null && photos == null -> Unit
                p == null -> EmptyState(Icons.Filled.Close, stringResource(R.string.people_gone_title), body = stringResource(R.string.people_gone_body))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(bottom = Space.xxl),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (!selecting) item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                        Header(p, s, container, onConfirm = vm::confirm, onManageRule = { vm.beginRule(); dialog = Dialog.RuleSheet }, onOpenS3Settings = onOpenS3Settings, onOpenCredentials = onOpenCredentials)
                    }
                    item(key = "photos-title", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.people_photos_of, p.label()), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.s).semantics { heading() },
                        )
                    }
                    items(photos.orEmpty(), key = { it.mediaStoreId }) { item ->
                        MediaCell(
                            item, item.mediaStoreId in selected, selecting, uploads[item.mediaStoreId],
                            onClick = { if (selecting) vm.toggle(it.mediaStoreId) else onOpenPhoto(it.mediaStoreId) },
                            onLongClick = { vm.toggle(it.mediaStoreId) },
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs
    val label = p?.label().orEmpty()
    when (val d = dialog) {
        Dialog.Rename -> RenameDialog(p?.name.orEmpty(), onConfirm = { vm.rename(it); dialog = Dialog.None }, onDismiss = { dialog = Dialog.None })
        Dialog.ConfirmDelete -> ConfirmDialog(
            stringResource(R.string.people_delete_title, label), stringResource(R.string.people_delete_body),
            stringResource(R.string.people_delete_confirm), onConfirm = { dialog = Dialog.None; vm.deleteGroup(onBack) }, onDismiss = { dialog = Dialog.None }, destructive = true,
        )
        Dialog.ConfirmHide -> ConfirmDialog(
            stringResource(R.string.people_hide_title, label), stringResource(R.string.people_hide_body),
            stringResource(R.string.people_hide_confirm), onConfirm = { dialog = Dialog.None; vm.hide(); onBack() }, onDismiss = { dialog = Dialog.None },
        )
        Dialog.ConfirmRemove -> ConfirmDialog(
            pluralStringResource(R.plurals.people_remove_title, selected.size, selected.size), stringResource(R.string.people_remove_body, label),
            stringResource(R.string.people_remove_confirm), onConfirm = { dialog = Dialog.None; vm.removeSelected() }, onDismiss = { dialog = Dialog.None }, destructive = true,
        )
        Dialog.ConfirmSplit -> ConfirmDialog(
            pluralStringResource(R.plurals.people_split_title, selected.size, selected.size), stringResource(R.string.people_split_body, label),
            stringResource(R.string.people_split_confirm), onConfirm = { dialog = Dialog.None; vm.moveSelected(null) }, onDismiss = { dialog = Dialog.None },
        )
        Dialog.PickMove -> PersonPicker(
            title = stringResource(R.string.people_move_pick_title), people = s.others, container = container, allowNew = true,
            onPick = { dialog = Dialog.ConfirmMove(it) }, onNew = { dialog = Dialog.None; vm.moveSelected(null) }, onDismiss = { dialog = Dialog.None },
        )
        is Dialog.ConfirmMove -> ConfirmDialog(
            pluralStringResource(R.plurals.people_move_title, selected.size, selected.size, d.target.label()), stringResource(R.string.people_move_body),
            stringResource(R.string.people_move_confirm), onConfirm = { dialog = Dialog.None; vm.moveSelected(d.target.id) }, onDismiss = { dialog = Dialog.None },
        )
        Dialog.PickMerge -> PersonPicker(
            title = stringResource(R.string.people_merge_pick_title, label), people = s.others, container = container, allowNew = false,
            onPick = { dialog = Dialog.ConfirmMerge(it) }, onNew = {}, onDismiss = { dialog = Dialog.None },
        )
        is Dialog.ConfirmMerge -> ConfirmDialog(
            stringResource(R.string.people_merge_title, d.other.label(), label),
            buildString {
                append(pluralStringResource(R.plurals.people_merge_body, d.other.photos, d.other.photos, d.other.label(), label))
                if (d.other.ruleState != null) append("\n\n").append(stringResource(R.string.people_merge_rule_note, d.other.label()))
            },
            stringResource(R.string.people_merge_confirm), onConfirm = { dialog = Dialog.None; vm.absorb(d.other.id) }, onDismiss = { dialog = Dialog.None },
        )
        Dialog.RuleSheet -> RuleSheet(vm, s, container, label, onDismiss = { dialog = Dialog.None }, onUndo = { dialog = Dialog.ConfirmUndo }, onRemove = { dialog = Dialog.ConfirmRuleOff }, onOpenS3Settings = onOpenS3Settings)
        Dialog.ConfirmUndo -> ConfirmDialog(
            stringResource(R.string.people_undo_title), pluralStringResource(R.plurals.people_undo_body, s.waitingByRule, s.waitingByRule),
            stringResource(R.string.people_undo_confirm), onConfirm = { dialog = Dialog.None; p?.let { vm.undoRule(it.id) } }, onDismiss = { dialog = Dialog.None },
        )
        Dialog.ConfirmRuleOff -> ConfirmDialog(
            stringResource(R.string.people_ruleoff_title, label), stringResource(R.string.people_ruleoff_body),
            stringResource(R.string.people_ruleoff_confirm), onConfirm = { dialog = Dialog.None; vm.removeRule() }, onDismiss = { dialog = Dialog.None },
        )
        Dialog.Menu, Dialog.None -> Unit
    }
}

@Composable
private fun Header(
    p: PersonRow, s: PersonDetailState, container: AppContainer, onConfirm: () -> Unit, onManageRule: () -> Unit,
    onOpenS3Settings: () -> Unit, onOpenCredentials: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (s.faces.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.xs)) {
            rowItems(s.faces.take(14), key = { it.faceId }) { f -> FaceCrop(f, container, 56.dp) }
        }
        if (p.state == PersonState.PROVISIONAL) StatusBanner(
            Tone.Info, stringResource(R.string.people_unreviewed_title), stringResource(R.string.people_unreviewed_body),
            actionLabel = stringResource(R.string.people_confirm_group), onAction = onConfirm,
        ) else Text(stringResource(R.string.people_estimates_note_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RuleCard(p, s, onManageRule, onOpenS3Settings, onOpenCredentials)
    }
}

/** The entry to this person's automatic backup: what it is, whether it is on, and what it has done so far. */
@Composable
private fun RuleCard(p: PersonRow, s: PersonDetailState, onManage: () -> Unit, onS3: () -> Unit, onCredentials: () -> Unit) {
    val rule = s.rule
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        SectionHeader(stringResource(R.string.people_rule_section))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.people_rule_title),
                subtitle = when {
                    rule == null -> stringResource(R.string.people_rule_off_subtitle)
                    rule.state == RuleState.PAUSED -> stringResource(R.string.people_rule_paused_subtitle)
                    else -> pluralStringResource(R.plurals.people_rule_active_subtitle, s.waitingByRule, s.waitingByRule, s.backedUpByRule)
                },
                icon = Icons.Filled.CloudUpload, onClick = onManage,
                trailing = {
                    Text(
                        stringResource(if (rule == null) R.string.people_rule_state_off else if (rule.state == RuleState.ACTIVE) R.string.people_rule_state_on else R.string.people_rule_state_paused),
                        style = MaterialTheme.typography.labelLarge, color = if (rule?.state == RuleState.ACTIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        if (rule != null && s.reviewPhotos.isNotEmpty()) StatusBanner(
            Tone.Warning, pluralStringResource(R.plurals.people_rule_review_title, s.reviewPhotos.size, s.reviewPhotos.size), stringResource(R.string.people_rule_review_body),
            actionLabel = stringResource(R.string.people_rule_review_action), onAction = onManage,
        )
        if (rule != null && !s.s3Configured) StatusBanner(Tone.Warning, stringResource(R.string.people_rule_needs_s3_title), stringResource(R.string.people_rule_needs_s3_body), stringResource(R.string.people_rule_needs_s3_action), onS3)
        else if (rule != null && s.needsCredentials) StatusBanner(Tone.Warning, stringResource(R.string.people_rule_needs_creds_title), stringResource(R.string.people_rule_needs_creds_body), stringResource(R.string.people_rule_needs_creds_action), onCredentials)
        else if (s.globalAutoBackup) StatusBanner(Tone.Info, stringResource(R.string.people_global_backup_title), stringResource(R.string.people_global_backup_body))
    }
}

@Composable
internal fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.people_rename_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(stringResource(R.string.people_rename_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(60) }, singleLine = true, label = { Text(stringResource(R.string.people_rename_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { QuietButton(stringResource(R.string.people_save), { onConfirm(text) }) },
        dismissButton = { QuietButton(stringResource(R.string.people_cancel), onDismiss) },
    )
}

/** Chooses another group (or a new one). Each row says who it is and how many photos it has, so the user does not choose blind. */
@Composable
internal fun PersonPicker(
    title: String, people: List<PersonRow>, container: AppContainer, allowNew: Boolean,
    onPick: (PersonRow) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (allowNew) Row(
                    Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button, onClick = onNew).padding(vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CallSplit, null, Modifier.size(40.dp).padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(Space.m))
                    Text(stringResource(R.string.people_new_person), style = MaterialTheme.typography.bodyLarge)
                }
                people.forEach { o ->
                    val cover by androidx.compose.runtime.produceState<com.s3vault.app.data.local.FacePlace?>(null, o.coverFaceId) {
                        value = o.coverFaceId?.let { id -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.db.faces().places(listOf(id)).firstOrNull() } }
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button, onClick = { onPick(o) }).padding(vertical = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FaceCrop(cover, container, 40.dp)
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(o.label(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(photoCountText(o.photos), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { QuietButton(stringResource(R.string.people_cancel), onDismiss) },
    )
}
