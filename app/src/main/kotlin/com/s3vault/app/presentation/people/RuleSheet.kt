package com.s3vault.app.presentation.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.s3vault.app.AppContainer
import com.s3vault.app.R
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.faces.DecisionReason
import com.s3vault.app.domain.faces.RulePolicy
import com.s3vault.app.domain.faces.RuleState
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.theme.PhotoOverlay
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.ChoiceChip
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SectionHeader
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone

/** A photo thumbnail at a fixed size (Coil decodes at that size, never the original). */
@Composable
fun PhotoThumb(item: MediaItem, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    AsyncImage(
        model = androidx.compose.runtime.remember(item.uri) { ImageRequest.Builder(ctx).data(item.uri).build() },
        contentDescription = item.displayName, contentScale = ContentScale.Crop,
        error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh), modifier = modifier,
    )
}

/**
 * Turning a person's automatic backup on, off or adjusting it. Turning it on is a deliberate two-step choice: what to cover and how
 * sure to be, then a plain summary of what will happen (with the count and size when existing photos are included). Nothing is
 * saved or queued until the final button, and the result comes with "Deshacer".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleSheet(
    vm: PersonDetailViewModel, s: PersonDetailState, container: AppContainer, label: String,
    onDismiss: () -> Unit, onUndo: () -> Unit, onRemove: () -> Unit, onOpenS3Settings: () -> Unit,
) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val rule = s.rule
    val unconfirmed = s.person?.state == com.s3vault.app.domain.faces.PersonState.PROVISIONAL
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.screen).padding(bottom = Space.l)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(
                    stringResource(R.string.people_sheet_title, label), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                if (rule == null) {
                    if (unconfirmed) StatusBanner(
                        Tone.Warning, stringResource(R.string.people_sheet_unconfirmed_title), stringResource(R.string.people_sheet_unconfirmed_body),
                        stringResource(R.string.people_confirm_group), vm::confirmAndPreview,
                    )
                    if (!s.s3Configured) StatusBanner(Tone.Warning, stringResource(R.string.people_rule_needs_s3_title), stringResource(R.string.people_sheet_needs_s3_body), stringResource(R.string.people_rule_needs_s3_action), onOpenS3Settings)
                    if (draft.step == RuleDraft.Step.OPTIONS) NewRuleOptions(vm, draft, s, label) else NewRuleConfirm(draft, label)
                } else {
                    ActiveRule(vm, s, rule.state, rule.policy, onUndo, onRemove)
                }
            }
            Spacer(Modifier.size(Space.m))
            if (rule == null) {
                if (draft.step == RuleDraft.Step.OPTIONS) {
                    PrimaryButton(stringResource(R.string.people_sheet_continue), vm::goToConfirm, Modifier.fillMaxWidth(), enabled = !draft.loading && !unconfirmed)
                    QuietButton(stringResource(R.string.people_cancel), onDismiss, Modifier.fillMaxWidth())
                } else {
                    PrimaryButton(stringResource(R.string.people_sheet_activate), { vm.confirmRule(); onDismiss() }, Modifier.fillMaxWidth())
                    SecondaryButton(stringResource(R.string.people_back), vm::backToOptions, Modifier.fillMaxWidth())
                }
            } else {
                QuietButton(stringResource(R.string.people_close), onDismiss, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun NewRuleOptions(vm: PersonDetailViewModel, d: RuleDraft, s: PersonDetailState, label: String) {
    Text(stringResource(R.string.people_sheet_intro, label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SectionHeader(stringResource(R.string.people_sheet_which))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        ChoiceChip(!d.includeExisting, stringResource(R.string.people_sheet_new_only), { vm.setIncludeExisting(false) })
        ChoiceChip(d.includeExisting, stringResource(R.string.people_sheet_with_existing), { vm.setIncludeExisting(true) })
    }
    if (!d.includeExisting) {
        Text(stringResource(R.string.people_sheet_new_only_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        ExistingPreview(vm, d, s)
    }
    SectionHeader(stringResource(R.string.people_sheet_how_sure))
    PolicyRow(d.policy == RulePolicy.STRICT, R.string.people_policy_strict, R.string.people_policy_strict_body) { vm.setPolicy(RulePolicy.STRICT) }
    PolicyRow(d.policy == RulePolicy.INCLUDE_PROBABLE, R.string.people_policy_probable, R.string.people_policy_probable_body) { vm.setPolicy(RulePolicy.INCLUDE_PROBABLE) }
    Text(stringResource(R.string.people_sheet_ambiguous_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The count the user is about to agree to, and the means to take photos out of it, before anything is queued. */
@Composable
private fun ExistingPreview(vm: PersonDetailViewModel, d: RuleDraft, s: PersonDetailState) {
    val plan = d.plan
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (d.loading || plan == null) {
            SlimProgress(null)
            Text(stringResource(R.string.people_sheet_counting), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                if (plan.willQueue.isEmpty()) stringResource(R.string.people_preview_none)
                else pluralStringResource(R.plurals.people_preview_queue, plan.willQueue.size, plan.willQueue.size, formatBytes(plan.bytes)),
                style = MaterialTheme.typography.titleSmall,
            )
            if (plan.review.isNotEmpty()) Text(pluralStringResource(R.plurals.people_preview_review, plan.review.size, plan.review.size), style = MaterialTheme.typography.bodySmall)
            if (plan.alreadyHandled > 0) Text(pluralStringResource(R.plurals.people_preview_handled, plan.alreadyHandled, plan.alreadyHandled), style = MaterialTheme.typography.bodySmall)
            if (plan.deletedFromS3OnPurpose > 0) Text(pluralStringResource(R.plurals.people_preview_deleted, plan.deletedFromS3OnPurpose, plan.deletedFromS3OnPurpose), style = MaterialTheme.typography.bodySmall)
            if (plan.willQueue.size >= BIG) StatusBanner(Tone.Warning, stringResource(R.string.people_preview_big_title), stringResource(R.string.people_preview_big_body))
            if (plan.willQueue.isNotEmpty()) {
                Text(stringResource(R.string.people_preview_exclude_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val byId = remember(s.photos) { s.photos.orEmpty().associateBy { it.mediaStoreId } }
                val shown = plan.willQueue.take(SHOWN).mapNotNull { byId[it.mediaStoreId] }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(76.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(shown, key = { it.mediaStoreId }) { m -> ExcludableThumb(m, m.mediaStoreId !in d.excluded) { vm.toggleDraftExclusion(m.mediaStoreId) } }
                }
                if (plan.willQueue.size > SHOWN) Text(pluralStringResource(R.plurals.people_preview_more, plan.willQueue.size - SHOWN, plan.willQueue.size - SHOWN), style = MaterialTheme.typography.bodySmall)
                if (d.excluded.isNotEmpty()) Text(pluralStringResource(R.plurals.people_preview_excluded, d.excluded.size, d.excluded.size), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ExcludableThumb(item: MediaItem, included: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (included) R.string.people_included else R.string.people_excluded)
    Box(
        Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.small)
            .selectable(selected = included, role = Role.Checkbox, onClick = onToggle)
            .semantics { contentDescription = item.displayName; stateDescription = state },
    ) {
        PhotoThumb(item, Modifier.fillMaxSize())
        if (!included) Box(Modifier.fillMaxSize().background(PhotoOverlay.strongScrim))
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(if (included) MaterialTheme.colorScheme.primary else PhotoOverlay.scrim)
                .border(1.5.dp, if (included) MaterialTheme.colorScheme.primary else PhotoOverlay.onScrim, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(if (included) Icons.Filled.Check else Icons.Filled.Close, null, Modifier.size(13.dp), tint = if (included) MaterialTheme.colorScheme.onPrimary else PhotoOverlay.onScrim) }
    }
}

@Composable
private fun PolicyRow(selected: Boolean, title: Int, body: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(MaterialTheme.shapes.medium).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = Space.xs),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = Space.m))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NewRuleConfirm(d: RuleDraft, label: String) {
    val plan = d.plan
    Text(stringResource(R.string.people_confirm_intro, label), style = MaterialTheme.typography.bodyLarge)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Bullet(stringResource(R.string.people_confirm_new, label))
        if (d.includeExisting && plan != null) {
            Bullet(
                if (plan.willQueue.isEmpty()) stringResource(R.string.people_confirm_existing_none)
                else pluralStringResource(R.plurals.people_confirm_existing, plan.willQueue.size, plan.willQueue.size, formatBytes(plan.bytes)),
            )
        }
        Bullet(stringResource(if (d.policy == RulePolicy.STRICT) R.string.people_confirm_strict else R.string.people_confirm_probable))
        Bullet(stringResource(R.string.people_confirm_whole))
        Bullet(stringResource(R.string.people_confirm_control))
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•", Modifier.width(18.dp), style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActiveRule(vm: PersonDetailViewModel, s: PersonDetailState, state: RuleState, policy: RulePolicy, onUndo: () -> Unit, onRemove: () -> Unit) {
    if (state == RuleState.ACTIVE) StatusBanner(Tone.Success, stringResource(R.string.people_active_title), pluralStringResource(R.plurals.people_active_body, s.waitingByRule, s.waitingByRule, s.backedUpByRule))
    else StatusBanner(Tone.Neutral, stringResource(R.string.people_paused_title), stringResource(R.string.people_paused_body))
    if (s.reviewPhotos.isNotEmpty()) {
        SectionHeader(pluralStringResource(R.plurals.people_rule_review_title, s.reviewPhotos.size, s.reviewPhotos.size))
        Text(stringResource(R.string.people_review_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReviewStrip(s.reviewPhotos, onApprove = { vm.approveReview(listOf(it)) }, onDismiss = { vm.dismissReview(listOf(it)) })
    }
    SectionHeader(stringResource(R.string.people_sheet_how_sure))
    PolicyRow(policy == RulePolicy.STRICT, R.string.people_policy_strict, R.string.people_policy_strict_body) { vm.changePolicy(RulePolicy.STRICT) }
    PolicyRow(policy == RulePolicy.INCLUDE_PROBABLE, R.string.people_policy_probable, R.string.people_policy_probable_body) { vm.changePolicy(RulePolicy.INCLUDE_PROBABLE) }
    if (state == RuleState.ACTIVE) SecondaryButton(stringResource(R.string.people_pause_rule), vm::pauseRule, Modifier.fillMaxWidth(), icon = Icons.Filled.Pause)
    else PrimaryButton(stringResource(R.string.people_resume_rule), vm::resumeRule, Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow)
    if (state == RuleState.PAUSED) Text(stringResource(R.string.people_resume_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (s.waitingByRule > 0) SecondaryButton(pluralStringResource(R.plurals.people_undo_button, s.waitingByRule, s.waitingByRule), onUndo, Modifier.fillMaxWidth(), icon = Icons.Filled.Undo)
    QuietButton(stringResource(R.string.people_remove_rule), onRemove, Modifier.fillMaxWidth(), destructive = true)
    Text(stringResource(R.string.people_remove_rule_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Photos the rule would not decide on its own, each with a clear yes and a clear no. Shared with the review screen. */
@Composable
internal fun ReviewStrip(photos: List<MediaItem>, onApprove: (Long) -> Unit, onDismiss: (Long) -> Unit, reasons: Map<Long, DecisionReason> = emptyMap()) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        rowItems(photos, key = { it.mediaStoreId }) { m ->
            Column(Modifier.width(104.dp), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                PhotoThumb(m, Modifier.size(104.dp).clip(MaterialTheme.shapes.small))
                reasons[m.mediaStoreId]?.let { Text(reasonText(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { onApprove(m.mediaStoreId) }) { Icon(Icons.Filled.Check, stringResource(R.string.people_review_yes_for, m.displayName), tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { onDismiss(m.mediaStoreId) }) { Icon(Icons.Filled.Close, stringResource(R.string.people_review_no_for, m.displayName), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
internal fun reasonText(r: DecisionReason): String = stringResource(
    when (r) {
        DecisionReason.AMBIGUOUS -> R.string.people_reason_ambiguous
        DecisionReason.LOW_CONFIDENCE -> R.string.people_reason_low_confidence
        DecisionReason.GROUP_NOT_CONFIRMED -> R.string.people_reason_group
        DecisionReason.LOW_QUALITY -> R.string.people_reason_quality
        DecisionReason.NONE, DecisionReason.USER -> R.string.people_reason_other
    },
)

private const val BIG = 200
private const val SHOWN = 60
