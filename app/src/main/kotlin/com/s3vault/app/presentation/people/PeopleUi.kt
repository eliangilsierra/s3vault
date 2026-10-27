package com.s3vault.app.presentation.people

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.s3vault.app.AppContainer
import com.s3vault.app.R
import com.s3vault.app.data.local.FacePlace
import com.s3vault.app.data.local.PersonRow
import com.s3vault.app.domain.faces.RuleState
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status

/** "Ana", or the neutral "Persona 3" until the user names the group: the app never makes a name up. */
@Composable
fun personLabel(name: String?, ordinal: Int): String = name ?: stringResource(R.string.people_default_name, ordinal)

@Composable
fun PersonRow.label(): String = personLabel(name, ordinal)

/** The face of a photo, cut out of it when drawn (see FaceCrops: nothing is stored). While it loads, or if it cannot, a plain face icon. */
@Composable
fun FaceCrop(place: FacePlace?, container: AppContainer, size: Dp, modifier: Modifier = Modifier, description: String? = null) {
    val bmp by produceState<Bitmap?>(null, place?.faceId) { value = place?.let { container.faceCrops.get(it) } }
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), description, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.Face, description, Modifier.size(size / 2), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A short line that says whether a person's automatic backup is on: icon and words, never colour alone. */
@Composable
fun RuleIndicator(state: RuleState?, modifier: Modifier = Modifier) {
    if (state == null) return
    val active = state == RuleState.ACTIVE
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (active) Icons.Filled.Cloud else Icons.Filled.PauseCircle, null, Modifier.size(14.dp),
            tint = if (active) MaterialTheme.status.success else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Space.xs))
        Text(
            stringResource(if (active) R.string.people_rule_on_short else R.string.people_rule_paused_short),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

/** The sentence for a message, from string resources. [undo] is the label of the one that offers to take the action back. */
@Composable
fun peopleMessageText(m: PeopleMessage): String {
    val n = m.n
    return when (m.kind) {
        PeopleMessage.Kind.ANALYSIS_STARTED -> stringResource(R.string.people_msg_analysis_started)
        PeopleMessage.Kind.ANALYSIS_STOPPED -> stringResource(R.string.people_msg_analysis_stopped)
        PeopleMessage.Kind.INDEX_DELETED -> stringResource(R.string.people_msg_index_deleted)
        PeopleMessage.Kind.TURNED_OFF -> stringResource(R.string.people_msg_turned_off)
        PeopleMessage.Kind.CONFIRMED -> stringResource(R.string.people_msg_confirmed)
        PeopleMessage.Kind.HIDDEN -> stringResource(R.string.people_msg_hidden)
        PeopleMessage.Kind.UNHIDDEN -> stringResource(R.string.people_msg_unhidden)
        PeopleMessage.Kind.GROUP_DELETED -> stringResource(R.string.people_msg_group_deleted)
        PeopleMessage.Kind.MERGED -> pluralStringResource(R.plurals.people_msg_merged, n, n)
        PeopleMessage.Kind.PHOTOS_REMOVED -> pluralStringResource(R.plurals.people_msg_removed, n, n)
        PeopleMessage.Kind.MOVED ->
            if (m.m > 0) stringResource(R.string.people_msg_moved_some, n, m.m) else pluralStringResource(R.plurals.people_msg_moved, n, n)
        PeopleMessage.Kind.SPLIT -> pluralStringResource(R.plurals.people_msg_split, n, n)
        PeopleMessage.Kind.RULE_ON -> when {
            n > 0 && m.m > 0 -> pluralStringResource(R.plurals.people_msg_rule_on_queued_review, n, n, m.m)
            n > 0 -> pluralStringResource(R.plurals.people_msg_rule_on_queued, n, n)
            else -> stringResource(R.string.people_msg_rule_on)
        }
        PeopleMessage.Kind.RULE_NEEDS_S3 -> stringResource(R.string.people_msg_rule_needs_s3)
        PeopleMessage.Kind.RULE_PAUSED -> stringResource(R.string.people_msg_rule_paused)
        PeopleMessage.Kind.RULE_RESUMED -> stringResource(R.string.people_msg_rule_resumed)
        PeopleMessage.Kind.RULE_OFF -> stringResource(R.string.people_msg_rule_off)
        PeopleMessage.Kind.RULE_UNDONE -> pluralStringResource(R.plurals.people_msg_rule_undone, n, n)
        PeopleMessage.Kind.EXCLUDED -> pluralStringResource(R.plurals.people_msg_excluded, n, n)
        PeopleMessage.Kind.REVIEW_APPROVED -> pluralStringResource(R.plurals.people_msg_review_approved, n, n)
        PeopleMessage.Kind.REVIEW_DISMISSED -> pluralStringResource(R.plurals.people_msg_review_dismissed, n, n)
        PeopleMessage.Kind.BACKUP_QUEUED -> pluralStringResource(R.plurals.people_msg_backup_queued, n, n)
        PeopleMessage.Kind.BACKUP_NOTHING_NEW -> stringResource(R.string.people_msg_backup_nothing)
        PeopleMessage.Kind.SUGGESTIONS_ACCEPTED -> pluralStringResource(R.plurals.people_msg_sugg_accepted, n, n)
        PeopleMessage.Kind.SUGGESTIONS_REJECTED -> pluralStringResource(R.plurals.people_msg_sugg_rejected, n, n)
        PeopleMessage.Kind.KEPT_APART -> stringResource(R.string.people_msg_kept_apart)
        PeopleMessage.Kind.ASSIGNED -> pluralStringResource(R.plurals.people_msg_assigned, n, n)
    }
}

/** Shows a message as a snackbar once, offering "Deshacer" when the message carries something to take back. */
@Composable
fun MessageEffect(message: PeopleMessage?, snackbar: androidx.compose.material3.SnackbarHostState, onDismiss: () -> Unit, onUndo: (Long) -> Unit = {}) {
    val text = message?.let { peopleMessageText(it) }
    val undoLabel = stringResource(R.string.people_undo)
    LaunchedEffect(message) {
        if (message != null && text != null) {
            val undo = message.undoPersonId
            val r = snackbar.showSnackbar(text, actionLabel = if (undo != null) undoLabel else null, duration = androidx.compose.material3.SnackbarDuration.Long)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed && undo != null) onUndo(undo)
            onDismiss()
        }
    }
}

/** A count of photos in words: "1 foto", "12 fotos". */
@Composable
fun photoCountText(n: Int): String = pluralStringResource(R.plurals.people_photos, n, n)

/**
 * The way into People from the Albums tab: always one quiet row, so the feature is found without adding a tab. While the analysis
 * is off it is an invitation, not a nag: it only opens the explanation.
 */
@Composable
fun PeopleStrip(count: Int?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.xs).heightIn(min = Space.touch)
            .clip(MaterialTheme.shapes.large).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen)
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Face, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.people_albums_strip), style = MaterialTheme.typography.titleSmall)
            Text(
                if (count == null || count == 0) stringResource(R.string.people_tool_subtitle) else pluralStringResource(R.plurals.people_count, count, count),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
            )
        }
        Text(stringResource(R.string.people_albums_strip_see), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
